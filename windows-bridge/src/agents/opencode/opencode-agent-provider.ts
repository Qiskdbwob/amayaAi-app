import { readFileSync } from 'node:fs';
import { AgentProvider } from '../agent-provider';
import {
  AgentEventKind,
  type AgentMcpEntry,
  type AgentModelEntry,
  type AgentModelRef,
  type AgentProviderEntry,
  type AgentRuntimeInfo,
  type AgentRuntimeStartPayload,
  type AgentSessionCreatePayload,
  type AgentSessionPromptPayload,
  type AgentSessionSummary,
  type AgentPermissionReplyPayload,
  type AgentQuestionReplyPayload,
  AgentRuntimeStatus
} from '../../protocol/bridge-agent';
import { logger } from '../../shared/logger';
import {
  OpencodeServerManager,
  type OpencodeServerOptions,
  type OpencodeServerState
} from './opencode-server-manager';
import { OpencodeRestClient } from './opencode-rest-client';
import { OpencodeEventStream } from './opencode-event-stream';
import { mapOpencodeEvent } from './opencode-event-mapper';
import { sanitizeOpencodeConfig } from './opencode-config-sanitizer';

const SCOPE = 'opencode.provider';
const OPENCODE_RUNTIME_ID = 'opencode';

export interface OpencodeAgentProviderOptions {
  autoStart?: boolean;
  configPath?: string | null;
  defaultServerOptions?: OpencodeServerOptions;
}

/**
 * AgentProvider for the opencode CLI backed by `opencode serve` + SSE.
 */
export class OpencodeAgentProvider extends AgentProvider {
  readonly runtimeId = OPENCODE_RUNTIME_ID;
  readonly displayName = 'Opencode';

  private readonly server = new OpencodeServerManager();
  private rest: OpencodeRestClient | null = null;
  private events: OpencodeEventStream | null = null;
  private lastStatus: AgentRuntimeStatus = 'stopped';
  private lastVersion: string | null = null;
  private lastError: string | null = null;

  constructor(private readonly options: OpencodeAgentProviderOptions = {}) {
    super();
    this.server.on('state', (state: OpencodeServerState) => {
      this.lastVersion = state.binary?.version ?? this.lastVersion;
      this.lastError = state.lastError;
      this.lastStatus = mapPhase(state.phase);
      this.emitStatusChanged(this.info());
    });
  }

  info(): AgentRuntimeInfo {
    const state = this.server.snapshot();
    return {
      runtimeId: this.runtimeId,
      displayName: this.displayName,
      status: this.lastStatus,
      version: state.binary?.version ?? this.lastVersion,
      baseUrl: state.baseUrl,
      binaryPath: state.binary?.path ?? null,
      configPath: this.options.configPath ?? null,
      lastError: this.lastError,
      updatedAt: state.updatedAt,
      capabilities: [
        'chat',
        'plan',
        'build',
        'mcp',
        'plugins',
        'session.fork',
        'permissions'
      ]
    };
  }

  async start(payload?: AgentRuntimeStartPayload): Promise<AgentRuntimeInfo> {
    const state = this.server.snapshot();
    if (state.phase === 'ready' && state.baseUrl) {
      return this.info();
    }
    try {
      const merged: OpencodeServerOptions = {
        ...this.options.defaultServerOptions,
        ...payload
      };
      if (payload?.configJson !== undefined) merged.configJson = payload.configJson;
      const next = await this.server.start(merged);
      if (!next.baseUrl) throw new Error('opencode serve returned no URL');
      this.rest = new OpencodeRestClient({ baseUrl: next.baseUrl });
      this.startEventStream();
      return this.info();
    } catch (err) {
      logger.error(SCOPE, `start failed: ${(err as Error).message}`);
      throw err;
    }
  }

  async stop(): Promise<void> {
    this.events?.close('runtime_stop');
    this.events = null;
    this.rest = null;
    await this.server.stop();
  }

  async restart(payload?: AgentRuntimeStartPayload): Promise<AgentRuntimeInfo> {
    await this.stop();
    return this.start(payload);
  }

  async dispose(): Promise<void> {
    await this.stop();
    this.removeAllListeners();
  }

  async getConfig(): Promise<{ configJson: string; configPath?: string | null }> {
    const rest = this.ensureRest();
    const cfg = (await rest.config()) as unknown;
    const raw = typeof cfg === 'string' ? cfg : JSON.stringify(cfg ?? {}, null, 2);
    const sanitized = sanitizeOpencodeConfig(raw);
    return { configJson: sanitized, configPath: this.resolveConfigPath() };
  }

  async listProviders(): Promise<AgentProviderEntry[]> {
    const rest = this.ensureRest();
    const raw = (await rest.providers()) as unknown;
    return mapProviders(raw);
  }

  async listModels(): Promise<{ models: AgentModelEntry[]; defaultModel?: AgentModelRef | null }> {
    const providers = await this.listProviders();
    const models = providers.flatMap((p) => p.models);
    const defaultProvider = providers.find((p) => p.defaultModelId);
    const defaultModel: AgentModelRef | null = defaultProvider
      ? {
          providerId: defaultProvider.providerId,
          modelId: defaultProvider.defaultModelId!
        }
      : null;
    return { models, defaultModel };
  }

  async listMcp(): Promise<AgentMcpEntry[]> {
    const rest = this.ensureRest();
    try {
      const raw = (await rest.mcpList()) as unknown;
      return mapMcp(raw);
    } catch (err) {
      logger.warn(SCOPE, `mcpList failed: ${(err as Error).message}`);
      return [];
    }
  }

  async listSessions(): Promise<AgentSessionSummary[]> {
    const rest = this.ensureRest();
    const raw = (await rest.sessions()) as unknown;
    return mapSessions(raw);
  }

  async createSession(payload: AgentSessionCreatePayload): Promise<AgentSessionSummary> {
    const rest = this.ensureRest();
    const body: Record<string, unknown> = {};
    if (payload.title) body.title = payload.title;
    if (payload.parentSessionId) body.parentID = payload.parentSessionId;
    const raw = (await rest.createSession(body)) as Record<string, unknown>;
    const data = (raw?.data ?? raw) as Record<string, unknown>;
    const summary = toSessionSummary(data);
    return summary;
  }

  async deleteSession(sessionId: string): Promise<void> {
    const rest = this.ensureRest();
    await rest.deleteSession(sessionId);
  }

  async prompt(payload: AgentSessionPromptPayload): Promise<void> {
    const rest = this.ensureRest();
    const body: Record<string, unknown> = {
      parts: payload.parts.map((part) => ({
        type: part.type,
        text: part.text,
        url: part.url,
        mime: part.mime,
        filename: part.filename,
        dataBase64: part.dataBase64
      }))
    };
    if (payload.agent) body.agent = payload.agent;
    if (payload.model) body.model = payload.model;
    await rest.promptAsync(payload.sessionId, body);
  }

  async abort(sessionId: string): Promise<void> {
    const rest = this.ensureRest();
    await rest.abortSession(sessionId);
  }

  async replyPermission(payload: AgentPermissionReplyPayload): Promise<void> {
    const rest = this.ensureRest();
    await rest.replyPermission(payload.sessionId, payload.permissionId, {
      reply: payload.reply
    });
  }

  async replyQuestion(payload: AgentQuestionReplyPayload): Promise<void> {
    const rest = this.ensureRest();
    await rest.replyQuestion(payload.questionId, { reply: payload.reply });
  }

  // ── Internals ────────────────────────────────────────────────────────────

  private ensureRest(): OpencodeRestClient {
    const state = this.server.snapshot();
    if (!this.rest || state.phase !== 'ready' || !state.baseUrl) {
      throw new Error('OPENCODE_NOT_READY');
    }
    return this.rest;
  }

  private startEventStream(): void {
    const state = this.server.snapshot();
    if (!this.rest || !state.baseUrl) return;
    this.events?.close('reconnecting');
    const stream = new OpencodeEventStream({
      url: this.rest.eventStreamUrl(),
      authHeader: this.rest.authHeaderValue()
    });
    stream.on('event', (evt) => {
      const mapped = mapOpencodeEvent(evt);
      if (mapped) this.emitAgentEvent(mapped);
    });
    stream.on('close', (reason) => {
      logger.info(SCOPE, `event stream closed: ${String(reason)}`);
      if (reason !== 'runtime_stop') {
        this.emitAgentEvent({
          runtimeId: this.runtimeId,
          sessionId: null,
          kind: AgentEventKind.SESSION_ERROR,
          data: { reason: String(reason) },
          timestamp: Date.now()
        });
      }
    });
    stream.start();
    this.events = stream;
  }

  private resolveConfigPath(): string | null {
    if (this.options.configPath) return this.options.configPath;
    try {
      const envDir = process.env.OPENCODE_CONFIG_DIR;
      if (envDir) return envDir;
      const envFile = process.env.OPENCODE_CONFIG;
      if (envFile) return envFile;
      return null;
    } catch {
      return null;
    }
  }
}

function mapPhase(phase: OpencodeServerState['phase']): AgentRuntimeStatus {
  switch (phase) {
    case 'ready':
      return 'ready';
    case 'starting':
      return 'starting';
    case 'degraded':
      return 'degraded';
    case 'stopped':
      return 'stopped';
    default:
      return 'error';
  }
}

function mapProviders(raw: unknown): AgentProviderEntry[] {
  if (!raw || typeof raw !== 'object') return [];
  const out: AgentProviderEntry[] = [];

  const consume = (providerId: string, value: unknown) => {
    if (!value || typeof value !== 'object') return;
    const obj = value as Record<string, unknown>;
    const displayName =
      typeof obj.name === 'string' ? obj.name : providerId;
    const modelsRaw = obj.models ?? obj.Models ?? {};
    const models: AgentModelEntry[] = [];
    if (modelsRaw && typeof modelsRaw === 'object') {
      for (const [modelId, modelValue] of Object.entries(
        modelsRaw as Record<string, unknown>
      )) {
        if (!modelValue || typeof modelValue !== 'object') continue;
        const m = modelValue as Record<string, unknown>;
        models.push({
          modelId,
          displayName:
            typeof m.name === 'string' ? m.name : modelId,
          providerId,
          contextWindowTokens:
            typeof m.contextWindow === 'number' ? m.contextWindow : null,
          maxOutputTokens:
            typeof m.maxOutput === 'number' ? m.maxOutput : null,
          supportsImages: Boolean(
            (m.modalities as Record<string, unknown> | undefined)?.input &&
              Array.isArray(
                (m.modalities as Record<string, unknown>).input
              ) &&
              ((m.modalities as Record<string, unknown>).input as string[]).includes(
                'image'
              )
          )
        });
      }
    }
    out.push({
      providerId,
      displayName,
      source: typeof obj.source === 'string' ? obj.source : undefined,
      authenticated: true,
      defaultModelId:
        typeof obj.defaultModelID === 'string' ? obj.defaultModelID : null,
      models
    });
  };

  const source = raw as Record<string, unknown>;
  const providers = source.providers ?? source.provider ?? source;
  if (providers && typeof providers === 'object' && !Array.isArray(providers)) {
    for (const [key, value] of Object.entries(
      providers as Record<string, unknown>
    )) {
      consume(key, value);
    }
  } else if (Array.isArray(providers)) {
    for (const entry of providers as Array<Record<string, unknown>>) {
      const id = typeof entry?.id === 'string' ? entry.id : '';
      if (id) consume(id, entry);
    }
  }
  return out;
}

function mapMcp(raw: unknown): AgentMcpEntry[] {
  if (!raw || typeof raw !== 'object') return [];
  const out: AgentMcpEntry[] = [];
  const pushEntry = (name: string, value: Record<string, unknown>) => {
    out.push({
      name,
      type: typeof value.type === 'string' ? value.type : 'unknown',
      enabled: value.enabled !== false,
      connected: value.connected === true || value.status === 'connected',
      toolCount:
        typeof value.toolCount === 'number'
          ? value.toolCount
          : Array.isArray(value.tools)
            ? (value.tools as unknown[]).length
            : 0
    });
  };
  if (Array.isArray(raw)) {
    for (const entry of raw as Array<Record<string, unknown>>) {
      const name = typeof entry?.name === 'string' ? entry.name : '';
      if (name) pushEntry(name, entry);
    }
    return out;
  }
  const obj = raw as Record<string, unknown>;
  const servers = obj.servers ?? obj.mcp ?? obj;
  if (servers && typeof servers === 'object' && !Array.isArray(servers)) {
    for (const [name, value] of Object.entries(
      servers as Record<string, unknown>
    )) {
      if (value && typeof value === 'object') {
        pushEntry(name, value as Record<string, unknown>);
      }
    }
  }
  return out;
}

function mapSessions(raw: unknown): AgentSessionSummary[] {
  if (!raw) return [];
  const list = Array.isArray(raw)
    ? (raw as Array<Record<string, unknown>>)
    : Array.isArray((raw as Record<string, unknown>).sessions)
      ? ((raw as Record<string, unknown>).sessions as Array<Record<string, unknown>>)
      : Array.isArray((raw as Record<string, unknown>).data)
        ? ((raw as Record<string, unknown>).data as Array<Record<string, unknown>>)
        : [];
  return list.map(toSessionSummary);
}

function toSessionSummary(data: Record<string, unknown>): AgentSessionSummary {
  return {
    sessionId:
      typeof data.id === 'string'
        ? data.id
        : typeof data.sessionId === 'string'
          ? data.sessionId
          : '',
    title: (data.title as string) ?? null,
    createdAt:
      typeof data.createdAt === 'number'
        ? data.createdAt
        : typeof (data.time as Record<string, unknown> | undefined)?.created === 'number'
          ? ((data.time as Record<string, unknown>).created as number)
          : Date.now(),
    updatedAt:
      typeof data.updatedAt === 'number'
        ? data.updatedAt
        : typeof (data.time as Record<string, unknown> | undefined)?.updated === 'number'
          ? ((data.time as Record<string, unknown>).updated as number)
          : Date.now(),
    agent: (data.agent as string) ?? null,
    modelId: (data.modelID as string) ?? null,
    providerId: (data.providerID as string) ?? null
  };
}

// Silence unused-import warning for this path — keeps readFileSync wired for
// future config file read path.
void readFileSync;
