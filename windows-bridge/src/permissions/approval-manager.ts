import { EventEmitter } from 'node:events';
import { newId } from '../shared/ids';
import { nowMs } from '../shared/time';
import { logger } from '../shared/logger';
import type { BridgeRiskLevel } from '../protocol/bridge-risk';
import type { ApprovalRequest } from '../protocol/bridge-approval';

export type ApprovalOutcome =
  | { status: 'approved'; reason?: string | null }
  | { status: 'rejected'; reason?: string | null }
  | { status: 'expired' };

export interface ApprovalOpenOptions {
  sessionId: string;
  toolCallId: string;
  tool: string;
  risk: BridgeRiskLevel;
  reason: string;
  argsPreview: Record<string, unknown>;
  timeoutMs: number;
}

/**
 * Tracks pending approval requests for HIGH-risk / MEDIUM-require-approval
 * tool-calls. Emits `requested` when a new approval is opened so the websocket
 * layer can forward an `approval.request` envelope to Android.
 */
export class ApprovalManager extends EventEmitter {
  private readonly pending = new Map<
    string,
    {
      request: ApprovalRequest;
      resolve: (value: ApprovalOutcome) => void;
      timer: NodeJS.Timeout;
    }
  >();

  constructor(private readonly defaultTimeoutMs: number = 30_000) {
    super();
  }

  /** Open a new approval request. Resolves with the final outcome. */
  open(options: ApprovalOpenOptions): Promise<ApprovalOutcome> {
    const requestedAt = nowMs();
    const timeout = options.timeoutMs > 0 ? options.timeoutMs : this.defaultTimeoutMs;
    const request: ApprovalRequest = {
      id: newId(),
      sessionId: options.sessionId,
      toolCallId: options.toolCallId,
      tool: options.tool,
      risk: options.risk,
      reason: options.reason,
      argsPreview: options.argsPreview,
      requestedAt,
      expiresAt: requestedAt + timeout,
      status: 'pending'
    };

    return new Promise<ApprovalOutcome>((resolvePromise) => {
      const timer = setTimeout(() => {
        const entry = this.pending.get(request.id);
        if (!entry) return;
        this.pending.delete(request.id);
        logger.warn('approval', `timeout id=${request.id} tool=${request.tool}`);
        resolvePromise({ status: 'expired' });
      }, timeout);

      this.pending.set(request.id, { request, resolve: resolvePromise, timer });
      this.emit('requested', request);
    });
  }

  /** Resolve an outstanding approval. Returns true if the id was pending. */
  resolve(requestId: string, outcome: ApprovalOutcome): boolean {
    const entry = this.pending.get(requestId);
    if (!entry) return false;
    this.pending.delete(requestId);
    clearTimeout(entry.timer);
    entry.resolve(outcome);
    return true;
  }

  /**
   * Resolve by tool-call id rather than approval id. Handy when the Android side
   * answered `approval.accepted` without echoing the approval id.
   */
  resolveByToolCallId(toolCallId: string, outcome: ApprovalOutcome): boolean {
    for (const [id, entry] of this.pending) {
      if (entry.request.toolCallId === toolCallId) {
        this.pending.delete(id);
        clearTimeout(entry.timer);
        entry.resolve(outcome);
        return true;
      }
    }
    return false;
  }

  /** Cancel every outstanding approval with `rejected`. Used on session drop. */
  cancelAll(reason: string): void {
    for (const [id, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.resolve({ status: 'rejected', reason });
      this.pending.delete(id);
    }
  }

  /** Snapshot of pending approvals, for the status window. */
  snapshot(): ApprovalRequest[] {
    return Array.from(this.pending.values()).map((e) => ({ ...e.request }));
  }
}
