import { EventEmitter } from 'node:events';
import { spawn, ChildProcessByStdio } from 'node:child_process';
import type { Readable } from 'node:stream';
import { logger } from '../../shared/logger';
import { resolveOpencodeBinary, type OpencodeBinary } from './opencode-binary';

const SCOPE = 'opencode.server';

export type OpencodeServerPhase =
  | 'stopped'
  | 'starting'
  | 'ready'
  | 'degraded'
  | 'error';

export interface OpencodeServerState {
  phase: OpencodeServerPhase;
  baseUrl: string | null;
  pid: number | null;
  binary: OpencodeBinary | null;
  lastError: string | null;
  updatedAt: number;
}

export interface OpencodeServerOptions {
  /** Bind host. Default 127.0.0.1. */
  hostname?: string;
  /** Bind port (0 = auto). */
  port?: number;
  /** Raw JSON passed via OPENCODE_CONFIG_CONTENT for per-run overrides. */
  configJson?: string;
  /** Override config directory (OPENCODE_CONFIG_DIR). */
  configDir?: string;
  /** Override binary path (OPENCODE_BIN_PATH). */
  binaryPath?: string;
  /** Optional HTTP basic auth password. */
  serverPassword?: string;
  /** Startup wait budget. */
  startupTimeoutMs?: number;
  /** Force DEBUG log level when true. */
  debugLogs?: boolean;
  /** Inject autoupdate=disable into OPENCODE_CONFIG_CONTENT. Default true. */
  disableAutoupdate?: boolean;
}

const DEFAULT_TIMEOUT = 20_000;
const MAX_STDERR_BUFFER = 8_192;

const LISTEN_PATTERN = /opencode server listening on\s+(https?:\/\/[^\s]+)/i;

/**
 * Owns a single `opencode serve` child process and exposes a StateEmitter.
 *
 * Lifecycle:
 *  - `start(options)` spawns the process, parses the "listening on" banner,
 *    and resolves when the HTTP endpoint is known.
 *  - A crash triggers phase = 'error'. Callers decide whether to restart.
 *  - `stop()` SIGTERMs the process and waits for exit.
 */
export class OpencodeServerManager extends EventEmitter {
  private process: ChildProcessByStdio<null, Readable, Readable> | null = null;
  private state: OpencodeServerState = {
    phase: 'stopped',
    baseUrl: null,
    pid: null,
    binary: null,
    lastError: null,
    updatedAt: Date.now()
  };
  private stderrBuffer = '';
  private startupTimer: NodeJS.Timeout | null = null;
  private startupResolve: ((baseUrl: string) => void) | null = null;
  private startupReject: ((err: Error) => void) | null = null;

  snapshot(): OpencodeServerState {
    return { ...this.state };
  }

  async start(options: OpencodeServerOptions = {}): Promise<OpencodeServerState> {
    if (this.process) {
      return this.snapshot();
    }

    const binary = resolveBinary(options.binaryPath);
    if (!binary) {
      this.updateState({
        phase: 'error',
        lastError: 'opencode binary not found. Install via `npm i -g opencode-ai`.',
        baseUrl: null,
        pid: null
      });
      throw new Error('OPENCODE_BINARY_NOT_FOUND');
    }

    this.updateState({
      phase: 'starting',
      binary,
      lastError: null,
      baseUrl: null,
      pid: null
    });

    const hostname = options.hostname ?? '127.0.0.1';
    const port = options.port ?? 0;
    const args = ['serve', `--hostname=${hostname}`, `--port=${port}`, '--print-logs'];
    if (options.debugLogs) {
      args.push('--log-level=DEBUG');
    }

    const configJson = buildConfigJson(options);

    const env: NodeJS.ProcessEnv = {
      ...process.env,
      OPENCODE_CONFIG_CONTENT: configJson
    };
    if (options.configDir) env.OPENCODE_CONFIG_DIR = options.configDir;
    if (options.serverPassword) env.OPENCODE_SERVER_PASSWORD = options.serverPassword;

    const child = spawn(binary.path, args, {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    }) as ChildProcessByStdio<null, Readable, Readable>;

    this.process = child;
    this.stderrBuffer = '';

    const readyPromise = new Promise<string>((resolve, reject) => {
      this.startupResolve = resolve;
      this.startupReject = reject;
      this.startupTimer = setTimeout(() => {
        this.finishStartup(
          null,
          new Error(`opencode serve did not report a listening URL within ${options.startupTimeoutMs ?? DEFAULT_TIMEOUT} ms`)
        );
        this.stop().catch(() => undefined);
      }, options.startupTimeoutMs ?? DEFAULT_TIMEOUT);
    });

    child.stdout?.setEncoding('utf-8');
    child.stdout?.on('data', (chunk: string) => {
      this.onStdoutData(chunk);
    });
    child.stderr?.setEncoding('utf-8');
    child.stderr?.on('data', (chunk: string) => {
      this.appendStderrBuffer(chunk);
    });
    child.on('error', (err) => {
      logger.error(SCOPE, `process error: ${err.message}`);
      this.finishStartup(null, err);
    });
    child.on('exit', (code, signal) => {
      const wasReady = this.state.phase === 'ready';
      logger.info(
        SCOPE,
        `process exited code=${code} signal=${signal ?? '-'} wasReady=${wasReady}`
      );
      this.process = null;
      const reason = code === 0
        ? null
        : `opencode serve exited with code=${code}${signal ? ` signal=${signal}` : ''}`;
      this.updateState({
        phase: code === 0 && !wasReady ? 'stopped' : wasReady ? 'stopped' : 'error',
        pid: null,
        baseUrl: null,
        lastError: reason
      });
      if (!wasReady) {
        this.finishStartup(
          null,
          new Error(reason ?? 'opencode serve exited before becoming ready')
        );
      }
    });

    try {
      const baseUrl = await readyPromise;
      this.updateState({
        phase: 'ready',
        baseUrl,
        pid: child.pid ?? null,
        lastError: null
      });
      return this.snapshot();
    } catch (err) {
      this.updateState({
        phase: 'error',
        lastError: (err as Error).message,
        baseUrl: null,
        pid: null
      });
      throw err;
    }
  }

  async stop(): Promise<void> {
    const child = this.process;
    this.process = null;
    if (this.startupTimer) {
      clearTimeout(this.startupTimer);
      this.startupTimer = null;
    }
    this.finishStartup(null, new Error('stopped'));
    if (!child) {
      this.updateState({
        phase: 'stopped',
        baseUrl: null,
        pid: null
      });
      return;
    }
    try {
      child.kill('SIGTERM');
    } catch {
      /* ignore */
    }
    await new Promise<void>((resolve) => {
      const resolveOnce = () => {
        resolve();
      };
      if (child.exitCode !== null || child.signalCode) {
        resolveOnce();
        return;
      }
      child.once('exit', resolveOnce);
      setTimeout(() => {
        try {
          child.kill('SIGKILL');
        } catch {
          /* ignore */
        }
        resolveOnce();
      }, 4_000);
    });
    this.updateState({
      phase: 'stopped',
      baseUrl: null,
      pid: null
    });
  }

  async restart(options: OpencodeServerOptions = {}): Promise<OpencodeServerState> {
    await this.stop();
    return this.start(options);
  }

  private onStdoutData(chunk: string): void {
    const lines = chunk.split(/\r?\n/);
    for (const line of lines) {
      if (!line) continue;
      logger.debug(SCOPE, `stdout: ${line}`);
      if (!this.state.baseUrl) {
        const match = line.match(LISTEN_PATTERN);
        if (match && match[1]) {
          this.finishStartup(match[1], null);
        }
      }
    }
  }

  private appendStderrBuffer(chunk: string): void {
    if (this.stderrBuffer.length + chunk.length > MAX_STDERR_BUFFER) {
      const room = Math.max(0, MAX_STDERR_BUFFER - this.stderrBuffer.length);
      this.stderrBuffer += chunk.slice(0, room);
    } else {
      this.stderrBuffer += chunk;
    }
    logger.debug(SCOPE, `stderr: ${chunk.trim()}`);
  }

  private finishStartup(baseUrl: string | null, error: Error | null): void {
    if (this.startupTimer) {
      clearTimeout(this.startupTimer);
      this.startupTimer = null;
    }
    const resolve = this.startupResolve;
    const reject = this.startupReject;
    this.startupResolve = null;
    this.startupReject = null;
    if (baseUrl && resolve) {
      resolve(baseUrl);
      return;
    }
    if (error && reject) {
      reject(error);
    }
  }

  private updateState(patch: Partial<OpencodeServerState>): void {
    this.state = {
      ...this.state,
      ...patch,
      updatedAt: Date.now()
    };
    this.emit('state', this.snapshot());
  }
}

function resolveBinary(override?: string): OpencodeBinary | null {
  if (override) {
    return { path: override, version: null };
  }
  return resolveOpencodeBinary();
}

function buildConfigJson(options: OpencodeServerOptions): string {
  const base = safeParseJson(options.configJson) ?? {};
  if (options.disableAutoupdate !== false) {
    (base as Record<string, unknown>).autoupdate = 'disable';
  }
  try {
    return JSON.stringify(base);
  } catch {
    return '{}';
  }
}

function safeParseJson(value: string | undefined): unknown {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
