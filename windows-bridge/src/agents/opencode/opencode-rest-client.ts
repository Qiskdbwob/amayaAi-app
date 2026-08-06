import { logger } from '../../shared/logger';

const SCOPE = 'opencode.rest';

export interface OpencodeRestClientOptions {
  baseUrl: string;
  username?: string;
  password?: string;
  /** Default request timeout in ms. */
  timeoutMs?: number;
}

export class OpencodeRestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body: string
  ) {
    super(message);
    this.name = 'OpencodeRestError';
  }
}

/**
 * Minimal fetch wrapper around the opencode REST surface.
 *
 * Only endpoints needed by the bridge are wrapped here. Everything that accepts
 * or returns untyped JSON is typed as `unknown` so callers narrow at the edge.
 */
export class OpencodeRestClient {
  private readonly baseUrl: string;
  private readonly authHeader: string | null;
  private readonly timeoutMs: number;

  constructor(options: OpencodeRestClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.timeoutMs = options.timeoutMs ?? 30_000;
    if (options.password) {
      const token = Buffer.from(
        `${options.username ?? 'opencode'}:${options.password}`,
        'utf-8'
      ).toString('base64');
      this.authHeader = `Basic ${token}`;
    } else {
      this.authHeader = null;
    }
  }

  get url(): string {
    return this.baseUrl;
  }

  // ── High-level helpers ───────────────────────────────────────────────────

  health(): Promise<unknown> {
    return this.get('/global/health');
  }

  config(): Promise<unknown> {
    return this.get('/config');
  }

  providers(): Promise<unknown> {
    return this.get('/config/providers');
  }

  agents(): Promise<unknown> {
    return this.get('/agent');
  }

  mcpList(): Promise<unknown> {
    return this.get('/mcp');
  }

  sessions(): Promise<unknown> {
    return this.get('/session');
  }

  createSession(body: Record<string, unknown>): Promise<unknown> {
    return this.postJson('/session', body);
  }

  deleteSession(sessionId: string): Promise<unknown> {
    return this.delete(`/session/${encodeURIComponent(sessionId)}`);
  }

  promptAsync(sessionId: string, body: Record<string, unknown>): Promise<unknown> {
    return this.postJson(
      `/session/${encodeURIComponent(sessionId)}/prompt_async`,
      body
    );
  }

  abortSession(sessionId: string): Promise<unknown> {
    return this.postJson(
      `/session/${encodeURIComponent(sessionId)}/abort`,
      {}
    );
  }

  replyPermission(
    sessionId: string,
    permissionId: string,
    body: Record<string, unknown>
  ): Promise<unknown> {
    return this.postJson(
      `/session/${encodeURIComponent(sessionId)}/permissions/${encodeURIComponent(permissionId)}`,
      body
    );
  }

  replyQuestion(questionId: string, body: Record<string, unknown>): Promise<unknown> {
    return this.postJson(
      `/question/${encodeURIComponent(questionId)}/reply`,
      body
    );
  }

  // ── Low-level helpers ────────────────────────────────────────────────────

  async get(path: string, signal?: AbortSignal): Promise<unknown> {
    return this.request('GET', path, undefined, signal);
  }

  async postJson(path: string, body: unknown, signal?: AbortSignal): Promise<unknown> {
    return this.request('POST', path, body, signal);
  }

  async delete(path: string, signal?: AbortSignal): Promise<unknown> {
    return this.request('DELETE', path, undefined, signal);
  }

  eventStreamUrl(): string {
    return `${this.baseUrl}/event`;
  }

  authHeaderValue(): string | null {
    return this.authHeader;
  }

  private async request(
    method: string,
    path: string,
    body: unknown,
    signal?: AbortSignal
  ): Promise<unknown> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const combined = combineSignals(signal, controller.signal);

    const headers: Record<string, string> = {
      Accept: 'application/json'
    };
    if (this.authHeader) headers.Authorization = this.authHeader;
    const init: RequestInit = { method, headers, signal: combined };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }

    const url = `${this.baseUrl}${path}`;
    logger.debug(SCOPE, `${method} ${path}`);

    try {
      const response = await fetch(url, init);
      const text = await response.text();
      if (!response.ok) {
        logger.warn(
          SCOPE,
          `${method} ${path} failed HTTP ${response.status} body=${text.slice(0, 512)}`
        );
        throw new OpencodeRestError(
          `opencode ${method} ${path} → HTTP ${response.status}: ${text.slice(0, 256)}`,
          response.status,
          text
        );
      }
      if (!text) return null;
      try {
        return JSON.parse(text);
      } catch {
        return text;
      }
    } finally {
      clearTimeout(timer);
    }
  }
}

function combineSignals(
  a: AbortSignal | undefined,
  b: AbortSignal
): AbortSignal {
  if (!a) return b;
  const controller = new AbortController();
  if (a.aborted || b.aborted) controller.abort();
  const onAbort = () => controller.abort();
  a.addEventListener('abort', onAbort, { once: true });
  b.addEventListener('abort', onAbort, { once: true });
  return controller.signal;
}
