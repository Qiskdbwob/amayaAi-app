import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { newId } from '../shared/ids';
import { logger } from '../shared/logger';
import { NativeHelperError } from './native-helper-errors';
import type {
  NativeHelperRequest,
  NativeHelperResponse
} from './native-helper-protocol';

const SCOPE = 'helper';
const DEFAULT_TIMEOUT_MS = 15_000;
const RESTART_BACKOFF_MS = 1_500;

export interface HelperStatus {
  running: boolean;
  pid: number | null;
  /** true when the helper process has Administrator rights (High/System integrity). */
  elevated: boolean | null;
  /** Mandatory integrity label of the helper process. */
  integrity: 'unknown' | 'untrusted' | 'low' | 'medium' | 'high' | 'system';
  lastError: string | null;
  startedAt: number | null;
}

export class NativeHelperClient extends EventEmitter {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private pending = new Map<
    string,
    {
      resolve: (value: Record<string, unknown>) => void;
      reject: (err: NativeHelperError) => void;
      timer: NodeJS.Timeout;
    }
  >();
  private buffer = '';
  private disposed = false;
  private status: HelperStatus = {
    running: false,
    pid: null,
    elevated: null,
    integrity: 'unknown',
    lastError: null,
    startedAt: null
  };

  constructor(private readonly helperPath: string = resolveHelperPath()) {
    super();
  }

  get snapshot(): HelperStatus {
    return { ...this.status };
  }

  start(): void {
    if (this.disposed) return;
    if (this.proc && !this.proc.killed) return;
    if (!existsSync(this.helperPath)) {
      this.status = {
        ...this.status,
        running: false,
        lastError: `helper not found at ${this.helperPath}`
      };
      logger.warn(SCOPE, this.status.lastError!);
      this.emit('status', this.snapshot);
      return;
    }

    logger.info(SCOPE, `spawning ${this.helperPath}`);
    const child = spawn(this.helperPath, [], {
      cwd: dirname(this.helperPath),
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });
    this.proc = child;
    this.buffer = '';
    this.status = {
      running: true,
      pid: child.pid ?? null,
      elevated: null,
      integrity: 'unknown',
      lastError: null,
      startedAt: Date.now()
    };
    this.emit('status', this.snapshot);

    // Kick off a diagnostics probe so the status window can show whether the
    // helper is elevated. A small delay lets the child attach its stdout first.
    setTimeout(() => {
      this.invoke('diagnostics', {}, 3_000)
        .then((result) => {
          const elevated = result['elevated'] === true;
          const integrity =
            (result['selfIntegrity'] as HelperStatus['integrity']) ?? 'unknown';
          this.status = { ...this.status, elevated, integrity };
          this.emit('status', this.snapshot);
        })
        .catch(() => {
          // Older helper builds or temporary failures: leave elevated=null.
        });
    }, 500);

    child.stdout.setEncoding('utf-8');
    child.stdout.on('data', (chunk: string) => this.onStdout(chunk));
    child.stderr.setEncoding('utf-8');
    child.stderr.on('data', (chunk: string) => {
      for (const line of chunk.split(/\r?\n/)) {
        if (line.trim().length) logger.warn(SCOPE, `stderr: ${line}`);
      }
    });
    child.on('exit', (code, signal) => this.onExit(code, signal));
    child.on('error', (err) => {
      this.status = { ...this.status, lastError: err.message };
      logger.error(SCOPE, 'helper error', err.message);
      this.emit('status', this.snapshot);
    });
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    const proc = this.proc;
    this.proc = null;
    this.failAllPending('helper disposed');
    if (!proc || proc.killed) return;
    try {
      proc.kill();
    } catch {
      /* ignore */
    }
  }

  async invoke(
    method: string,
    params: Record<string, unknown> = {},
    timeoutMs: number = DEFAULT_TIMEOUT_MS
  ): Promise<Record<string, unknown>> {
    if (!this.proc || this.proc.killed || !this.status.running) {
      throw new NativeHelperError(
        'HELPER_UNAVAILABLE',
        'Native helper is not running.',
        true
      );
    }
    const proc = this.proc;
    const id = newId();
    const request: NativeHelperRequest = { id, method, params };
    const payload = JSON.stringify(request) + '\n';

    return new Promise<Record<string, unknown>>((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectPromise(
          new NativeHelperError(
            'TIMEOUT',
            `Native helper timed out after ${timeoutMs}ms (method=${method})`,
            true
          )
        );
      }, timeoutMs);
      this.pending.set(id, { resolve: resolvePromise, reject: rejectPromise, timer });
      try {
        proc.stdin.write(payload, (err) => {
          if (err) {
            clearTimeout(timer);
            this.pending.delete(id);
            rejectPromise(
              new NativeHelperError('HELPER_UNAVAILABLE', err.message, true)
            );
          }
        });
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(id);
        rejectPromise(
          new NativeHelperError('HELPER_UNAVAILABLE', (err as Error).message, true)
        );
      }
    });
  }

  async health(): Promise<Record<string, unknown>> {
    return this.invoke('health.ping', {}, 5_000);
  }

  async activeWindow(): Promise<{
    id?: string;
    title?: string;
    processId?: number;
    processName?: string;
    focused?: boolean;
  } | null> {
    try {
      const res = await this.invoke('window.active', {}, 3_000);
      const w = res['window'] as Record<string, unknown> | null | undefined;
      if (!w) return null;
      return {
        id: typeof w['id'] === 'string' ? (w['id'] as string) : undefined,
        title: typeof w['title'] === 'string' ? (w['title'] as string) : undefined,
        processId: typeof w['processId'] === 'number' ? (w['processId'] as number) : undefined,
        processName:
          typeof w['processName'] === 'string' ? (w['processName'] as string) : undefined,
        focused: w['focused'] === true
      };
    } catch {
      return null;
    }
  }

  // ── internals ───────────────────────────────────────────────────────────

  private onStdout(chunk: string): void {
    this.buffer += chunk;
    while (true) {
      const newline = this.buffer.indexOf('\n');
      if (newline < 0) return;
      const line = this.buffer.slice(0, newline).replace(/\r$/, '');
      this.buffer = this.buffer.slice(newline + 1);
      if (!line.length) continue;
      this.handleLine(line);
    }
  }

  private handleLine(line: string): void {
    let parsed: NativeHelperResponse;
    try {
      parsed = JSON.parse(line) as NativeHelperResponse;
    } catch (err) {
      logger.warn(SCOPE, 'invalid helper response line', (err as Error).message);
      return;
    }
    const entry = this.pending.get(parsed.id);
    if (!entry) {
      logger.debug(SCOPE, `response for unknown id=${parsed.id}`);
      return;
    }
    clearTimeout(entry.timer);
    this.pending.delete(parsed.id);
    if (parsed.ok) {
      entry.resolve(parsed.result ?? {});
    } else {
      entry.reject(
        new NativeHelperError(
          (parsed.error?.code as never) ?? 'EXECUTION_FAILED',
          parsed.error?.message ?? 'helper failure',
          parsed.error?.recoverable ?? true,
          parsed.error?.details ?? {}
        )
      );
    }
  }

  private onExit(code: number | null, signal: NodeJS.Signals | null): void {
    const wasRunning = this.status.running;
    const reason = `helper exited code=${code ?? '-'} signal=${signal ?? '-'}`;
    logger.warn(SCOPE, reason);
    this.failAllPending(reason);
    this.proc = null;
    this.status = {
      ...this.status,
      running: false,
      pid: null,
      elevated: null,
      integrity: 'unknown',
      lastError: reason
    };
    this.emit('status', this.snapshot);
    if (!this.disposed && wasRunning) {
      setTimeout(() => this.start(), RESTART_BACKOFF_MS);
    }
  }

  private failAllPending(reason: string): void {
    for (const [, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(new NativeHelperError('HELPER_UNAVAILABLE', reason, true));
    }
    this.pending.clear();
  }
}

function resolveHelperPath(): string {
  const override = process.env.AMAYA_BRIDGE_HELPER_PATH?.trim();
  if (override && existsSync(override)) return override;

  // __dirname when compiled lives at dist/native/native-helper-client.js.
  const here = __dirname;

  const candidates = [
    resolve(here, '..', 'native', 'AmayaBridgeHelper.exe'),
    resolve(here, '..', '..', 'native-helper', 'bin', 'Release', 'net10.0-windows', 'win-x64', 'publish', 'AmayaBridgeHelper.exe'),
    resolve(here, '..', '..', '..', 'native-helper', 'bin', 'Release', 'net10.0-windows', 'win-x64', 'publish', 'AmayaBridgeHelper.exe')
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  // Return the first candidate so callers can log the expected location.
  return candidates[0]!;
}

export { resolveHelperPath };
