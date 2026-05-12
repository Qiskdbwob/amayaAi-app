// Mirror of app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeMessageType.kt.
// Keep the wire strings identical to the Kotlin side.

export const BridgeMessageType = {
  SESSION_CREATED: 'session.created',
  SESSION_CLOSED: 'session.closed',
  DEVICE_PAIRED: 'device.paired',
  DEVICE_DISCONNECTED: 'device.disconnected',

  SCREEN_FRAME: 'screen.frame',
  SCREEN_CAPTURE_RESULT: 'screen.capture_result',

  TOOL_CALL: 'tool.call',
  TOOL_RESULT: 'tool.result',
  TOOL_ERROR: 'tool.error',

  AGENT_STATUS: 'agent.status',
  AGENT_STEP: 'agent.step',
  AGENT_PAUSED: 'agent.paused',
  AGENT_RESUMED: 'agent.resumed',
  AGENT_CANCELLED: 'agent.cancelled',

  APPROVAL_REQUEST: 'approval.request',
  APPROVAL_ACCEPTED: 'approval.accepted',
  APPROVAL_REJECTED: 'approval.rejected',

  AUDIT_EVENT: 'audit.event',
  ERROR: 'error',

  // CLI Coding Agent runtime (opencode, claude-code, codex, ...)
  // Android → Bridge
  AGENT_RUNTIME_STATUS_REQUEST: 'agent.runtime.status.request',
  AGENT_RUNTIME_START: 'agent.runtime.start',
  AGENT_RUNTIME_STOP: 'agent.runtime.stop',
  AGENT_RUNTIME_RESTART: 'agent.runtime.restart',
  AGENT_CONFIG_REQUEST: 'agent.config.request',
  AGENT_PROVIDER_LIST_REQUEST: 'agent.provider.list.request',
  AGENT_MODEL_LIST_REQUEST: 'agent.model.list.request',
  AGENT_MCP_LIST_REQUEST: 'agent.mcp.list.request',
  AGENT_SESSION_LIST_REQUEST: 'agent.session.list.request',
  AGENT_SESSION_CREATE: 'agent.session.create',
  AGENT_SESSION_DELETE: 'agent.session.delete',
  AGENT_SESSION_PROMPT: 'agent.session.prompt',
  AGENT_SESSION_ABORT: 'agent.session.abort',
  AGENT_PERMISSION_REPLY: 'agent.permission.reply',
  AGENT_QUESTION_REPLY: 'agent.question.reply',

  // Bridge → Android
  AGENT_RUNTIME_STATUS: 'agent.runtime.status',
  AGENT_CONFIG: 'agent.config',
  AGENT_PROVIDER_LIST: 'agent.provider.list',
  AGENT_MODEL_LIST: 'agent.model.list',
  AGENT_MCP_LIST: 'agent.mcp.list',
  AGENT_SESSION_LIST: 'agent.session.list',
  AGENT_SESSION_CREATED: 'agent.session.created',
  AGENT_SESSION_DELETED: 'agent.session.deleted',
  AGENT_EVENT: 'agent.event'
} as const;

export type BridgeMessageType =
  (typeof BridgeMessageType)[keyof typeof BridgeMessageType];

export function isKnownMessageType(value: string): value is BridgeMessageType {
  return (Object.values(BridgeMessageType) as string[]).includes(value);
}
