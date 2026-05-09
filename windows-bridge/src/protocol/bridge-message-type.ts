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
  ERROR: 'error'
} as const;

export type BridgeMessageType =
  (typeof BridgeMessageType)[keyof typeof BridgeMessageType];

export function isKnownMessageType(value: string): value is BridgeMessageType {
  return (Object.values(BridgeMessageType) as string[]).includes(value);
}
