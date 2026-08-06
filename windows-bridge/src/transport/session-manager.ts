import { EventEmitter } from 'node:events';
import { newId } from '../shared/ids';
import { nowMs } from '../shared/time';

export type SessionStatus =
  | 'disconnected'
  | 'pairing'
  | 'connected'
  | 'agent_control'
  | 'view_only'
  | 'paused'
  | 'closed'
  | 'error';

export interface SessionSnapshot {
  sessionId: string | null;
  deviceId: string | null;
  status: SessionStatus;
  agentControlEnabled: boolean;
  viewOnly: boolean;
  connectedAt: number | null;
  lastSeenAt: number | null;
  emergencyStopped: boolean;
  incomingSeq: number;
  outgoingSeq: number;
}

/**
 * MVP single-session store. Only one Android device is active at a time.
 * A new connection may replace the previous one **only** if the previous session
 * has already transitioned to `closed` / `disconnected` / `error`.
 */
export class SessionManager extends EventEmitter {
  private snap: SessionSnapshot = blank();

  get snapshot(): SessionSnapshot {
    return { ...this.snap };
  }

  /**
   * Attach (or start) a session for [deviceId]. Returns the fresh or reused
   * session id, or throws when another live session is still connected.
   */
  attach(deviceId: string | null): { sessionId: string; reused: boolean } {
    const existing = this.snap;
    const canReuse =
      existing.sessionId !== null &&
      existing.deviceId === deviceId &&
      !this.isTerminal(existing.status);
    if (canReuse) {
      this.snap = {
        ...existing,
        status: 'connected',
        lastSeenAt: nowMs()
      };
      this.emitSnapshot();
      return { sessionId: existing.sessionId!, reused: true };
    }
    if (existing.sessionId && !this.isTerminal(existing.status)) {
      // Reject — previous session still live.
      throw new Error(
        'SESSION_IN_USE: another device is already connected to this bridge.'
      );
    }
    const sessionId = newId();
    this.snap = {
      sessionId,
      deviceId,
      status: 'connected',
      agentControlEnabled: false,
      viewOnly: false,
      connectedAt: nowMs(),
      lastSeenAt: nowMs(),
      emergencyStopped: false,
      incomingSeq: -1,
      outgoingSeq: 0
    };
    this.emitSnapshot();
    return { sessionId, reused: false };
  }

  touch(): void {
    if (!this.snap.sessionId) return;
    this.snap = { ...this.snap, lastSeenAt: nowMs() };
  }

  nextOutgoingSeq(): number {
    this.snap = { ...this.snap, outgoingSeq: this.snap.outgoingSeq + 1 };
    return this.snap.outgoingSeq;
  }

  /** Returns false if the incoming seq is a duplicate and should be dropped. */
  noteIncomingSeq(seq: number): boolean {
    if (!Number.isFinite(seq) || seq <= 0) return true;
    if (seq <= this.snap.incomingSeq) return false;
    this.snap = { ...this.snap, incomingSeq: seq, lastSeenAt: nowMs() };
    return true;
  }

  setAgentControl(enabled: boolean): void {
    if (!this.snap.sessionId) return;
    this.snap = {
      ...this.snap,
      agentControlEnabled: enabled,
      status: enabled ? 'agent_control' : 'connected'
    };
    this.emitSnapshot();
  }

  setViewOnly(viewOnly: boolean): void {
    if (!this.snap.sessionId) return;
    this.snap = {
      ...this.snap,
      viewOnly,
      status: viewOnly ? 'view_only' : 'connected'
    };
    this.emitSnapshot();
  }

  emergencyStop(): void {
    if (!this.snap.sessionId) return;
    this.snap = {
      ...this.snap,
      emergencyStopped: true,
      status: 'paused'
    };
    this.emitSnapshot();
  }

  resume(): void {
    if (!this.snap.sessionId) return;
    this.snap = {
      ...this.snap,
      emergencyStopped: false,
      status: this.snap.agentControlEnabled ? 'agent_control' : 'connected'
    };
    this.emitSnapshot();
  }

  close(reason?: string): void {
    if (!this.snap.sessionId) return;
    this.snap = {
      ...this.snap,
      status: 'closed'
    };
    this.emitSnapshot();
    this.emit('closed', reason);
  }

  disconnect(reason?: string): void {
    if (!this.snap.sessionId) return;
    this.snap = { ...this.snap, status: 'disconnected' };
    this.emitSnapshot();
    this.emit('disconnected', reason);
  }

  errored(reason?: string): void {
    if (!this.snap.sessionId) return;
    this.snap = { ...this.snap, status: 'error' };
    this.emitSnapshot();
    this.emit('errored', reason);
  }

  private emitSnapshot(): void {
    this.emit('snapshot', this.snapshot);
  }

  private isTerminal(status: SessionStatus): boolean {
    return status === 'disconnected' || status === 'closed' || status === 'error';
  }
}

function blank(): SessionSnapshot {
  return {
    sessionId: null,
    deviceId: null,
    status: 'disconnected',
    agentControlEnabled: false,
    viewOnly: false,
    connectedAt: null,
    lastSeenAt: null,
    emergencyStopped: false,
    incomingSeq: -1,
    outgoingSeq: 0
  };
}
