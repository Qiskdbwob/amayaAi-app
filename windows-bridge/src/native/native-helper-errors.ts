import type { BridgeToolErrorCode } from '../protocol/bridge-tool';

/** Stable error codes exchanged with the native helper. */
export type NativeHelperErrorCode =
  | 'INVALID_REQUEST'
  | 'INVALID_ARGS'
  | 'UNKNOWN_METHOD'
  | 'EXECUTION_FAILED'
  | 'NOT_FOUND'
  | 'PERMISSION_DENIED'
  | 'UNSUPPORTED'
  | 'TIMEOUT'
  | 'HELPER_UNAVAILABLE';

export class NativeHelperError extends Error {
  constructor(
    public readonly code: NativeHelperErrorCode,
    message: string,
    public readonly recoverable = true,
    public readonly details: Record<string, unknown> = {}
  ) {
    super(message);
    this.name = 'NativeHelperError';
  }
}

export function mapToBridgeErrorCode(code: NativeHelperErrorCode): BridgeToolErrorCode {
  switch (code) {
    case 'INVALID_ARGS':
      return 'INVALID_ARGS';
    case 'PERMISSION_DENIED':
      return 'PERMISSION_DENIED';
    case 'NOT_FOUND':
      return 'EXECUTION_FAILED';
    case 'UNKNOWN_METHOD':
    case 'UNSUPPORTED':
      return 'COMMAND_BLOCKED';
    case 'TIMEOUT':
      return 'TIMEOUT';
    case 'HELPER_UNAVAILABLE':
      return 'SESSION_CLOSED';
    case 'INVALID_REQUEST':
    case 'EXECUTION_FAILED':
    default:
      return 'EXECUTION_FAILED';
  }
}
