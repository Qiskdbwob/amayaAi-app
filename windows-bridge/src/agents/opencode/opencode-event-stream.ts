import { EventEmitter } from 'node:events';
import { logger } from '../../shared/logger';

const SCOPE = 'opencode.events';

export interface OpencodeEventStreamOptions {
  url: string;
  authHeader?: string | null;
}

export interface OpencodeSseEvent {
  type: string;
  properties: unknown;
}

/**
 * Thin SSE consumer for the opencode `/event` stream.
 *
 * Emits `event` for every parsed envelope, `open` on connect, `close` on any
 * disconnect (with `reason` string). The consumer is fire-and-forget: the
 * caller is expected to close it when the runtime stops.
 *
 * Implementation note: Node 20+ `fetch` exposes a Web ReadableStream which we
 * iterate manually to stay dependency-free (no eventsource / undici client
 * import — keeps the bridge tight).
 */
export class OpencodeEventStream extends EventEmitter {
  private controller: AbortController | null = null;
  private closed = false;

  constructor(private readonly options: OpencodeEventStreamOptions) {
    super();
  }

  start(): void {
    if (this.controller) return;
    this.closed = false;
    this.controller = new AbortController();
    this.runLoop().catch((err) => {
      if (!this.closed) {
        logger.warn(SCOPE, `stream terminated: ${(err as Error).message}`);
        this.emit('close', (err as Error).message);
      }
    });
  }

  close(reason = 'stopped'): void {
    if (this.closed) return;
    this.closed = true;
    try {
      this.controller?.abort();
    } catch {
      /* ignore */
    }
    this.controller = null;
    this.emit('close', reason);
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
