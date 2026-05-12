import { BridgePermissionDecision, BridgeRiskLevel } from './bridge-risk';

export type BridgeAuditEventType =
  | 'tool_requested'
  | 'approval_requested'
  | 'approval_accepted'
  | 'approval_rejected'
  | 'tool_started'
  | 'tool_succeeded'
  | 'tool_failed'
  | 'tool_cancelled'
  | 'session_paused'
  | 'session_resumed'
  | 'session_closed'
  | 'agent_prompt'
  | 'agent_permission_replied';

export type BridgeAuditActor =
  | 'android_user'
  | 'android_agent'
  | 'windows_bridge'
  | 'native_helper'
  | 'system';

export interface BridgeAuditEvent {
  id: string;
  sessionId: string;
  toolCallId?: string | null;
  eventType: BridgeAuditEventType;
  tool?: string | null;
  risk?: BridgeRiskLevel | null;
  decision?: BridgePermissionDecision | null;
  argsPreview?: Record<string, unknown>;
  resultPreview?: Record<string, unknown>;
  timestamp: number;
  actor: BridgeAuditActor;
}
