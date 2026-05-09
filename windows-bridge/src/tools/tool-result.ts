import type {
  BridgeToolErrorCode,
  BridgeToolResultStatus
} from '../protocol/bridge-tool';

export interface LocalToolResult {
  status: BridgeToolResultStatus;
  result: Record<string, unknown>;
}

export interface LocalToolError {
  code: BridgeToolErrorCode;
  message: string;
  details?: Record<string, unknown>;
  recoverable?: boolean;
}

export class ToolInvocationError extends Error {
  constructor(
    public readonly code: BridgeToolErrorCode,
    message: string,
    public readonly details: Record<string, unknown> = {},
    public readonly recoverable = false
  ) {
    super(message);
    this.name = 'ToolInvocationError';
  }
}
