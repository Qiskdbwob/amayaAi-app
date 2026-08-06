import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { logger } from '../shared/logger';
import type { BridgeAuditEvent } from '../protocol/bridge-audit';

export interface AuditSummaryEntry {
  timestamp: number;
  actor: string;
  eventType: string;
  tool: string | null;
  outcome: string;
  summary: string;
}

const DEFAULT_LOG_PATH = resolve(process.cwd(), 'logs', 'audit.log');

/**
 * Read the last [limit] audit events from the JSONL log and produce a redacted
 * summary suitable for the status window. Never returns typed text, base64
 * payloads, tokens, or secrets — those fields are dropped here in case the
 * writer ever regressed its own redaction.
 */
export async function readRecentAudit(
  limit: number = 20,
  logPath: string = DEFAULT_LOG_PATH
): Promise<AuditSummaryEntry[]> {
  if (!existsSync(logPath)) return [];
  try {
    const raw = await readFile(logPath, 'utf-8');
    const lines = raw.split(/\r?\n/).filter((line) => line.trim().length > 0);
    const tail = lines.slice(-Math.max(1, limit));
    const out: AuditSummaryEntry[] = [];
    for (const line of tail) {
      try {
        const ev = JSON.parse(line) as BridgeAuditEvent;
        out.push(summarize(ev));
      } catch (err) {
        logger.debug('audit', 'skipping malformed audit line', (err as Error).message);
      }
    }
    return out.reverse();
  } catch (err) {
    logger.warn('audit', 'failed to read audit log', (err as Error).message);
    return [];
  }
}

function summarize(ev: BridgeAuditEvent): AuditSummaryEntry {
  const tool = ev.tool ?? null;
  const outcome = outcomeLabel(ev.eventType);
  const detail = redactedDetail(ev);
  const summary = detail
    ? `${tool ?? ev.eventType} ${outcome}${detail ? ` (${detail})` : ''}`
    : `${tool ?? ev.eventType} ${outcome}`;
  return {
    timestamp: ev.timestamp,
    actor: ev.actor,
    eventType: ev.eventType,
    tool,
    outcome,
    summary
  };
}

function outcomeLabel(eventType: BridgeAuditEvent['eventType']): string {
  switch (eventType) {
    case 'tool_requested':
      return 'requested';
    case 'tool_started':
      return 'running';
    case 'tool_succeeded':
      return 'succeeded';
    case 'tool_failed':
      return 'failed';
    case 'tool_cancelled':
      return 'cancelled';
    case 'approval_requested':
      return 'awaiting approval';
    case 'approval_accepted':
      return 'approved';
    case 'approval_rejected':
      return 'rejected';
    case 'session_paused':
      return 'paused';
    case 'session_resumed':
      return 'resumed';
    case 'session_closed':
      return 'closed';
    default:
      return String(eventType);
  }
}

function redactedDetail(ev: BridgeAuditEvent): string | null {
  if (ev.eventType === 'tool_failed') {
    const preview = ev.resultPreview ?? {};
    const code = preview['code'];
    return typeof code === 'string' ? code : null;
  }
  if (ev.tool === 'keyboard.type') {
    const length = ev.argsPreview?.['length'];
    return typeof length === 'number' ? `length=${length}` : null;
  }
  if (ev.tool === 'keyboard.hotkey') {
    const keys = ev.argsPreview?.['keys'];
    return Array.isArray(keys) ? `keys=${keys.join('+')}` : null;
  }
  if (ev.tool === 'mouse.click') {
    const x = ev.argsPreview?.['x'];
    const y = ev.argsPreview?.['y'];
    if (typeof x === 'number' && typeof y === 'number') return `@${x},${y}`;
    return null;
  }
  if (ev.tool === 'window.focus') {
    const wid = ev.argsPreview?.['windowId'];
    return typeof wid === 'string' ? `id=${wid}` : null;
  }
  return null;
}
