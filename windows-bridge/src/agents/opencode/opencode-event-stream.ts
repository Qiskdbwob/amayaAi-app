import { EventEmitter } from 'node:events';
import { logger } from '../../shared/logger';

const SCOPE = 'opencode.events';

export interface OpencodeEventStreamOptions {
  url: string;
  authHeader?: string | null;
  /** Retry a failed connection with exponential backoff. Default true. */
  autoReconnect?: boolean;
  /** Initial retry delay in ms. Default 1500. */
  retryDelayMs?: number;
  /** Max retry delay in ms. Default 30_000. */
  retryMaxDelayMs?: number;
}

export interface OpencodeSseEvent {
  type: string;
  properties: unknown;
}

/**
 * Thin SSE consumer for the opencode `/event` stream.
 *
 * Emits `event` for every parsed envelope, `open` on connect, `close` on
 * permanent teardown. While auto-reconnect is on the consumer keeps trying
 * after transient failures with exponential backoff.
 */
export class OpencodeEventStream extends EventEmitter {
  private controller: AbortController | null = null;
  private closed = false;
  private retryAttempt = 0;
  private retryTimer: NodeJS.Timeout | null = null;

  constructor(private readonly options: OpencodeEventStreamOptions) {
    super();
  }

  start(): void {
    if (this.closed) return;
    if (this.controller) return;
    this.runOnce();
  }

  close(reason = 'stopped'): void {
    if (this.closed) return;
    this.closed = true;
    this.clearRetry();
    try {
      this.controller?.abort();
    } catch {
      /* ignore */
    }
    this.controller = null;
    this.emit('close', reason);
  }

  private runOnce(): void {
    if (this.closed) return;
    this.controller = new AbortController();
    this.runLoop()
      .then(() => {
        this.controller = null;
        this.scheduleRetry('stream ended');
      })
      .catch((err) => {
        this.controller = null;
        if (!this.closed) {
          const message = (err as Error)?.message ?? 'stream error';
          logger.warn(SCOPE, `stream terminated: ${message}`);
          this.scheduleRetry(message);
        }
      });
  }

  private scheduleRetry(reason: string): void {
    if (this.closed) return;
    const autoReconnect = this.options.autoReconnect !== false;
    if (!autoReconnect) {
      this.close(reason);
      return;
    }
    const base = this.options.retryDelayMs ?? 1500;
    const cap = this.options.retryMaxDelayMs ?? 30_000;
    const attempt = Math.min(this.retryAttempt, 6);
    const delay = Math.min(cap, base * (1 << attempt));
    this.retryAttempt += 1;
    this.emit('retry', { attempt: this.retryAttempt, delayMs: delay, reason });
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.runOnce();
    }, delay);
  }

  private clearRetry(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }

  private async runLoop(): Promise<void> {
    const controller = this.controller;
    if (!controller) return;
    const headers: Record<string, string> = {
      Accept: 'text/event-stream'
    };
    if (this.options.authHeader) headers.Authorization = this.options.authHeader;

    const response = await fetch(this.options.url, {
      method: 'GET',
      headers,
      signal: controller.signal
    });
    if (!response.ok || !response.body) {
      throw new Error(`opencode /event returned HTTP ${response.status}`);
    }
    // Successful handshake resets backoff.
    this.retryAttempt = 0;
    this.emit('open');

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    try {
      while (!this.closed) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let separatorIndex = buffer.indexOf('\n\n');
        while (separatorIndex !== -1) {
          const chunk = buffer.slice(0, separatorIndex);
          buffer = buffer.slice(separatorIndex + 2);
          this.dispatchChunk(chunk);
          separatorIndex = buffer.indexOf('\n\n');
        }
      }
    } finally {
      try {
        reader.releaseLock();
      } catch {
        /* ignore */
      }
    }
  }

  private dispatchChunk(chunk: string): void {
    if (!chunk) return;
    const dataLines: string[] = [];
    for (const line of chunk.split(/\r?\n/)) {
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (dataLines.length === 0) return;
    const data = dataLines.join('\n').trim();
    if (!data) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch (err) {
      logger.warn(SCOPE, `skipping malformed event: ${(err as Error).message}`);
      return;
    }
    if (typeof parsed !== 'object' || parsed === null) return;
    const obj = parsed as Record<string, unknown>;
    const type = typeof obj.type === 'string' ? obj.type : '';
    if (!type) return;
    const properties = obj.properties ?? {};
    this.emit('event', { type, properties } satisfies OpencodeSseEvent);
  }
}
