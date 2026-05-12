// Mirror of app/src/main/java/com/amaya/intelligence/domain/bridge/AgentRuntime.kt.
// Keep wire values identical to the Kotlin side.

export const AgentRuntimeIds = {
  OPENCODE: 'opencode',
  CLAUDE_CODE: 'claude_code',
  CODEX: 'codex'
} as const;
export type AgentRuntimeId = (typeof AgentRuntimeIds)[keyof typeof AgentRuntimeIds];

export const AgentRuntimeStatus = {
  STOPPED: 'stopped',
  STARTING: 'starting',
  READY: 'ready',
  DEGRADED: 'degraded',
  ERROR: 'error'
} as const;
export type AgentRuntimeStatus =
  (typeof AgentRuntimeStatus)[keyof typeof AgentRuntimeStatus];

export interface AgentRuntimeInfo {
  runtimeId: string;
  displayName: string;
  status: AgentRuntimeStatus;
  version?: string | null;
  baseUrl?: string | null;
  binaryPath?: string | null;
  configPath?: string | null;
  lastError?: string | null;
  updatedAt: number;
  capabilities?: string[];
}

export interface AgentProviderEntry {
  providerId: string;
  displayName: string;
  source?: string;
  authenticated: boolean;
  defaultModelId?: string | null;
  models: AgentModelEntry[];
}

export interface AgentModelEntry {
  modelId: string;
  displayName: string;
  providerId: string;
  contextWindowTokens?: number | null;
  maxOutputTokens?: number | null;
  supportsImages?: boolean;
  tagTitle?: string | null;
}

export interface AgentModelRef {
  providerId: string;
  modelId: string;
}

export interface AgentMcpEntry {
  name: string;
  type: string;
  enabled: boolean;
  connected: boolean;
  toolCount: number;
}

export interface AgentSessionSummary {
  sessionId: string;
  title?: string | null;
  createdAt: number;
  updatedAt: number;
  agent?: string | null;
  modelId?: string | null;
  providerId?: string | null;
}

export const AgentModes = {
  BUILD: 'build',
  PLAN: 'plan'
} as const;
export type AgentMode = (typeof AgentModes)[keyof typeof AgentModes] | string;

export interface AgentMessagePart {
  type: 'text' | 'file' | 'image' | string;
  text?: string;
  url?: string;
  mime?: string;
  filename?: string;
  dataBase64?: string;
}

export const AgentEventKind = {
  MESSAGE_PART_TEXT: 'message.part.text',
  MESSAGE_PART_THOUGHT: 'message.part.thought',
  MESSAGE_PART_TOOL: 'message.part.tool',
  TOOL_CALL_UPDATE: 'tool.call.update',
  PLAN_UPDATE: 'plan.update',
  TODO_UPDATE: 'todo.update',
  PERMISSION_ASKED: 'permission.asked',
  PERMISSION_REPLIED: 'permission.replied',
  QUESTION_ASKED: 'question.asked',
  QUESTION_REPLIED: 'question.replied',
  SESSION_STATUS: 'session.status',
  SESSION_IDLE: 'session.idle',
  SESSION_ERROR: 'session.error',
  SESSION_DIFF: 'session.diff',
  MCP_CHANGED: 'mcp.changed',
  MODEL_CHANGED: 'model.changed',
  INSTALLATION_UPDATE: 'installation.update'
} as const;
export type AgentEventKind =
  (typeof AgentEventKind)[keyof typeof AgentEventKind] | string;

// ── Request / response payload shapes ──────────────────────────────────────

export interface AgentRuntimeStatusRequestPayload {
  runtimeId: string;
}

export interface AgentRuntimeStartPayload {
  runtimeId: string;
  /** Optional raw JSON config sent as OPENCODE_CONFIG_CONTENT on spawn. */
  configJson?: string;
  /** Optional port override (0 = auto). */
  port?: number;
  hostname?: string;
}

export interface AgentRuntimeStopPayload {
  runtimeId: string;
}

export interface AgentSessionCreatePayload {
  runtimeId: string;
  title?: string;
  agent?: AgentMode;
  model?: AgentModelRef;
  parentSessionId?: string;
}

export interface AgentSessionPromptPayload {
  runtimeId: string;
  sessionId: string;
  parts: AgentMessagePart[];
  agent?: AgentMode;
  model?: AgentModelRef;
  async?: boolean;
}

export interface AgentSessionAbortPayload {
  runtimeId: string;
  sessionId: string;
}

export interface AgentSessionDeletePayload {
  runtimeId: string;
  sessionId: string;
}

export interface AgentPermissionReplyPayload {
  runtimeId: string;
  sessionId: string;
  permissionId: string;
  reply: 'once' | 'always' | 'reject';
}

export interface AgentQuestionReplyPayload {
  runtimeId: string;
  sessionId: string;
  questionId: string;
  reply: string;
}

// ── Bridge → Android payloads ──────────────────────────────────────────────

export interface AgentRuntimeStatusPayload extends AgentRuntimeInfo {
  runtimes?: AgentRuntimeInfo[];
}

export interface AgentProviderListPayload {
  runtimeId: string;
  providers: AgentProviderEntry[];
}

export interface AgentModelListPayload {
  runtimeId: string;
  models: AgentModelEntry[];
  defaultModel?: AgentModelRef | null;
}

export interface AgentMcpListPayload {
  runtimeId: string;
  servers: AgentMcpEntry[];
}

export interface AgentSessionListPayload {
  runtimeId: string;
  sessions: AgentSessionSummary[];
}

export interface AgentSessionCreatedPayload {
  runtimeId: string;
  session: AgentSessionSummary;
}

export interface AgentSessionDeletedPayload {
  runtimeId: string;
  sessionId: string;
}

export interface AgentEventPayload {
  runtimeId: string;
  sessionId?: string | null;
  kind: AgentEventKind;
  /** Discriminator-specific body; see AgentEventKind. */
  data: Record<string, unknown>;
  timestamp: number;
}

export interface AgentConfigPayload {
  runtimeId: string;
  /** Redacted JSON string of the active config (secrets removed). */
  configJson: string;
  configPath?: string | null;
}
