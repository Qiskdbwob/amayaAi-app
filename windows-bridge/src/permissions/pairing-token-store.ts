import { randomBytes } from 'node:crypto';
import { logger } from '../shared/logger';
import { nowMs } from '../shared/time';

export interface PairingToken {
  token: string;
  createdAt: number;
  expiresAt: number;
}

const DEFAULT_TTL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Generates short-lived pairing tokens. Tokens live in memory only — they are
 * never persisted to disk. A new token replaces the previous one.
 */
export class PairingTokenStore {
  private current: PairingToken | null = null;

  constructor(private readonly ttlMs: number = DEFAULT_TTL_MS) {}

  /** Generate a fresh pairing token. Invalidates the previous one. */
  generate(): PairingToken {
    const now = nowMs();
    this.current = {
      token: randomBytes(16).toString('hex'),
      createdAt: now,
      expiresAt: now + this.ttlMs
    };
    logger.info('pairing-token', `generated, expires in ${this.ttlMs / 1000}s`);
    return this.current;
  }

  /** Validate a token. Returns true only if it matches and hasn't expired. */
  validate(token: string | null | undefined): boolean {
    if (!token || !this.current) return false;
    if (token !== this.current.token) return false;
    if (nowMs() > this.current.expiresAt) {
      logger.info('pairing-token', 'token expired');
      this.current = null;
      return false;
    }
    return true;
  }

  /** Get the current token info (for display in status UI). Never log the value. */
  snapshot(): { active: boolean; expiresAt: number | null; remainingMs: number } {
    if (!this.current) return { active: false, expiresAt: null, remainingMs: 0 };
    const remaining = Math.max(0, this.current.expiresAt - nowMs());
    if (remaining <= 0) {
      this.current = null;
      return { active: false, expiresAt: null, remainingMs: 0 };
    }
    return { active: true, expiresAt: this.current.expiresAt, remainingMs: remaining };
  }

  /** Get the raw token value — only for building the pairing payload. */
  rawToken(): string | null {
    if (!this.current) return null;
    if (nowMs() > this.current.expiresAt) {
      this.current = null;
      return null;
    }
    return this.current.token;
  }

  invalidate(): void {
    this.current = null;
  }
}
