import { EventEmitter } from 'node:events';
import type {
  AgentEventPayload,
  AgentMcpEntry,
  AgentModelEntry,
  AgentModelRef,
  AgentProviderEntry,
  AgentRuntimeInfo,
  AgentRuntimeStartPayload,
  AgentSessionCreatePayload,
  AgentSessionPromptPayload,
  AgentSessionSummary,
  AgentPermissionReplyPayload,
  AgentQuestionReplyPayload
} from '../protocol/bridge-agent';

/**
 * Abstract contract implemented by every CLI coding-agent runtime (opencode,
 * claude-code, codex, ...). The bridge routes [BridgeMessageType.AGENT_*]
 * envelopes to a matching AgentProvider based on `payload.runtimeId`.
 *
 * Provider implementations:
 *  - own the lifecycle of their underlying CLI / server process
 *  - translate REST / stdio APIs into the neutral shapes in bridge-agent.ts
 *  - emit normalized AgentEventPayload through the EventEmitter surface
 *
 * Provider implementations MUST be non-blocking: all side effects happen in
 * async methods and event emission is the only way state propagates.
 */
export abstract class AgentProvider extends EventEmitter {
  abstract readonly runtimeId: string;
  abstract readonly displayName: string;

  /** Lightweight snapshot. Safe to call at any time. */
  abstract info(): AgentRuntimeInfo;

  abstract start(payload?: AgentRuntimeStartPayload): Promise<AgentRuntimeInfo>;
  abstract stop(): Promise<void>;
  abstract restart(payload?: AgentRuntimeStartPayload): Promise<AgentRuntimeInfo>;

  abstract getConfig(): Promise<{ configJson: string; configPath?: string | null }>;
  abstract listProviders(): Promise<AgentProviderEntry[]>;
  abstract listModels(): Promise<{ models: AgentModelEntry[]; defaultModel?: AgentModelRef | null }>;
  abstract listMcp(): Promise<AgentMcpEntry[]>;

  abstract listSessions(): Promise<AgentSessionSummary[]>;
  abstract createSession(payload: AgentSessionCreatePayload): Promise<AgentSessionSummary>;
  abstract deleteSession(sessionId: string): Promise<void>;
  abstract prompt(payload: AgentSessionPromptPayload): Promise<void>;
  abstract abort(sessionId: string): Promise<void>;

  /**
   * Fetch the full message history for a session. Default implementation
   * returns empty so providers that don't support history lookup (yet) do
   * not have to stub the method.
   */
  async listSessionMessages(_sessionId: string): Promise<unknown[]> {
    return [];
  }

  abstract replyPermission(payload: AgentPermissionReplyPayload): Promise<void>;
  abstract replyQuestion(payload: AgentQuestionReplyPayload): Promise<void>;

  // ── Optional PTY surface. Providers may override to expose a pseudo-terminal
  //     connection to their underlying CLI. Default implementations throw so the
  //     router can surface a clear error message.
  async openPty(_payload: Record<string, unknown>): Promise<{ ptyId: string }> {
    throw new Error(`${this.runtimeId}: PTY not supported`);
  }
  async resizePty(_ptyId: string, _cols: number, _rows: number): Promise<void> {
    throw new Error(`${this.runtimeId}: PTY not supported`);
  }
  async writePty(_ptyId: string, _dataBase64: string): Promise<void> {
    throw new Error(`${this.runtimeId}: PTY not supported`);
  }
  async closePty(_ptyId: string): Promise<void> {
    // no-op default so cleanup on disconnect doesn't throw for non-PTY runtimes.
  }

  /** Called by bridge during shutdown. Must be idempotent. */
  abstract dispose(): Promise<void>;

  /** Helper used by subclasses to emit normalized events. */
  protected emitAgentEvent(event: AgentEventPayload): void {
    this.emit('agent_event', event);
  }

  /** Helper used by subclasses to notify a runtime status change. */
  protected emitStatusChanged(info: AgentRuntimeInfo): void {
    this.emit('status_changed', info);
  }
}
