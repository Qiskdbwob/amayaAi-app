import { BridgeRiskLevel } from './bridge-risk';

export interface BridgeToolCall {
  id: string;
  sessionId: string;
  tool: string;
  args: Record<string, unknown>;
  risk: BridgeRiskLevel;
  requiresApproval: boolean;
  createdAt: number;
  timeoutMs?: number | null;
  metadata?: Record<string, string>;
}

export type BridgeToolResultStatus = 'success' | 'cancelled' | 'timeout';

export interface BridgeToolResult {
  id: string;
  toolCallId: string;
  sessionId: string;
  tool: string;
  status: BridgeToolResultStatus;
  result: Record<string, unknown>;
  startedAt: number;
  finishedAt: number;
  durationMs: number;
  metadata?: Record<string, string>;
}

export type BridgeToolErrorCode =
  | 'INVALID_ARGS'
  | 'PERMISSION_DENIED'
  | 'APP_NOT_ALLOWED'
  | 'PATH_NOT_ALLOWED'
  | 'COMMAND_BLOCKED'
  | 'APPROVAL_REQUIRED'
  | 'APPROVAL_REJECTED'
  | 'EXECUTION_FAILED'
  | 'TIMEOUT'
  | 'SESSION_CLOSED'
  | 'UNKNOWN';

export interface BridgeToolError {
  id: string;
  toolCallId: string;
  sessionId: string;
  tool: string;
  code: BridgeToolErrorCode;
  message: string;
  details?: Record<string, unknown>;
  recoverable: boolean;
  timestamp: number;
}
