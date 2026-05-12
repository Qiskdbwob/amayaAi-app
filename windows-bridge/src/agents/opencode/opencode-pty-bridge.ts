import { EventEmitter } from 'node:events';
import { WebSocket } from 'ws';
import { logger } from '../../shared/logger';

const SCOPE = 'opencode.pty';

export interface OpencodePtyBridgeOptions {
  baseUrl: string;
  authHeader?: string | null;
}

export interface OpencodePtyCreatePayload {
  command: string;
  args?: string[];
  cwd?: string;
  title?: string;
  env?: Record<string, string>;
  cols?: number;
  rows?: number;
}

interface PtySession {
  ptyId: string;
  socket: WebSocket;
}

/**
 * Pipes opencode PTY sessions to the Windows Bridge. Each `agent.pty.open`
 * envelope starts a PTY via the opencode REST API and then proxies raw bytes
 * through `agent.pty.output` envelopes. Input from Android flows back via
 * `agent.pty.input` and is forwarded to the opencode WebSocket.
 */
export class OpencodePtyBridge extends EventEmitter {
  private sessions = new Map<string, PtySession>();

  constructor(private readonly options: OpencodePtyBridgeOptions) {
    super();
  }

  setOptions(options: OpencodePtyBridgeOptions) {
    (this as unknown as { options: OpencodePtyBridgeOptions }).options = options;
  }

  async open(payload: OpencodePtyCreatePayload): Promise<{ ptyId: string }> {
    const baseUrl = this.options.baseUrl;
    if (!baseUrl) throw new Error('OPENCODE_NOT_READY');

    const body: Record<string, unknown> = {
      command: payload.command,
      args: payload.args ?? [],
      cwd: payload.cwd,
      title: payload.title,
      env: payload.env
    };
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.options.authHeader) headers.Authorization = this.options.authHeader;

    const response = await fetch(`${baseUrl}/pty`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      throw new Error(`opencode /pty POST failed: HTTP ${response.status}`);
    }
    const data = (await response.json()) as { data?: { id?: string }; id?: string };
    const ptyId = data?.data?.id ?? data?.id;
    if (!ptyId) throw new Error('opencode /pty returned no PTY id');

    const wsUrl = baseUrl.replace(/^http/, 'ws') + `/pty/${encodeURIComponent(ptyId)}/connect`;
    const wsHeaders: Record<string, string> = {};
    if (this.options.authHeader) wsHeaders.Authorization = this.options.authHeader;

    const socket = new WebSocket(wsUrl, { headers: wsHeaders });
    socket.on('open', () => {
      logger.info(SCOPE, `opened pty=${ptyId}`);
      this.emit('opened', { ptyId, title: payload.title ?? payload.command });
    });
    socket.on('message', (data: Buffer | ArrayBuffer | Buffer[]) => {
      const base64 = toBase64(data);
      this.emit('output', { ptyId, dataBase64: base64 });
    });
    socket.on('close', (code, reason) => {
      logger.info(SCOPE, `pty closed id=${ptyId} code=${code} reason=${reason?.toString()}`);
      this.sessions.delete(ptyId);
      this.emit('closed', { ptyId, code, reason: reason?.toString() ?? '' });
    });
    socket.on('error', (err) => {
      logger.warn(SCOPE, `pty error id=${ptyId}: ${err.message}`);
    });

    this.sessions.set(ptyId, { ptyId, socket });
    return { ptyId };
  }

  async resize(ptyId: string, cols: number, rows: number): Promise<void> {
    const baseUrl = this.options.baseUrl;
    if (!baseUrl) throw new Error('OPENCODE_NOT_READY');
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.options.authHeader) headers.Authorization = this.options.authHeader;
    await fetch(`${baseUrl}/pty/${encodeURIComponent(ptyId)}`, {
      method: 'PATCH',
      headers,
      body: JSON.stringify({ cols, rows })
    });
  }

  input(ptyId: string, dataBase64: string): void {
    const session = this.sessions.get(ptyId);
    if (!session) return;
    try {
      const buffer = Buffer.from(dataBase64, 'base64');
      session.socket.send(buffer);
    } catch (err) {
      logger.warn(SCOPE, `input send failed id=${ptyId}: ${(err as Error).message}`);
    }
  }

  async close(ptyId: string): Promise<void> {
    const session = this.sessions.get(ptyId);
    if (session) {
      try {
        session.socket.close();
      } catch {
        /* ignore */
      }
      this.sessions.delete(ptyId);
    }
    const baseUrl = this.options.baseUrl;
    if (!baseUrl) return;
    const headers: Record<string, string> = {};
    if (this.options.authHeader) headers.Authorization = this.options.authHeader;
    try {
      await fetch(`${baseUrl}/pty/${encodeURIComponent(ptyId)}`, {
        method: 'DELETE',
        headers
      });
    } catch {
      /* ignore — process may already be gone */
    }
  }

  disposeAll(): void {
    for (const [id, session] of this.sessions) {
      try {
        session.socket.close();
      } catch {
        /* ignore */
      }
      this.sessions.delete(id);
    }
  }
}

function toBase64(data: Buffer | ArrayBuffer | Buffer[]): string {
  if (Array.isArray(data)) {
    const total = data.reduce((n, buf) => n + buf.length, 0);
    const joined = Buffer.concat(data, total);
    return joined.toString('base64');
  }
  if (Buffer.isBuffer(data)) return data.toString('base64');
  return Buffer.from(data as ArrayBuffer).toString('base64');
}
