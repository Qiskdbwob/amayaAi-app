import { appendFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { logger } from '../shared/logger';
import { newId } from '../shared/ids';
import { nowMs } from '../shared/time';
import type { BridgeAuditEvent } from '../protocol/bridge-audit';

const DEFAULT_LOG_PATH = resolve(process.cwd(), 'logs', 'audit.log');

export class AuditLog {
  constructor(private readonly logPath: string = DEFAULT_LOG_PATH) {}

  async append(event: BridgeAuditEvent): Promise<void> {
    const record: BridgeAuditEvent = {
      ...event,
      id: event.id || newId(),
      timestamp: event.timestamp || nowMs(),
      argsPreview: redact(event.argsPreview),
      resultPreview: redact(event.resultPreview)
    };
    const line = JSON.stringify(record) + '\n';
    try {
      await mkdir(dirname(this.logPath), { recursive: true });
      await appendFile(this.logPath, line, 'utf-8');
    } catch (err) {
      logger.error('audit', 'failed to append audit event', (err as Error).message);
    }
  }
}

/**
 * Redact obviously sensitive or oversized fields so the audit log stays safe.
 * Full payloads belong in memory-only diagnostics, not on disk.
 */
function redact(
  input: Record<string, unknown> | undefined
): Record<string, unknown> {
  if (!input) return {};
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(input)) {
    if (looksSensitive(key)) {
      out[key] = '[redacted]';
      continue;
    }
    if (typeof value === 'string') {
      out[key] = value.length > 256 ? `${value.slice(0, 253)}...` : value;
    } else if (value && typeof value === 'object') {
      out[key] = '[object]';
    } else {
      out[key] = value;
    }
  }
  return out;
}

function looksSensitive(key: string): boolean {
  const k = key.toLowerCase();
  return (
    k.includes('token') ||
    k.includes('password') ||
    k.includes('secret') ||
    k === 'authorization' ||
    k.includes('apikey') ||
    k.includes('api_key') ||
    k === 'imagebase64'
  );
}
