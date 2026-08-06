import type { NativeHelperErrorCode } from './native-helper-errors';

export interface NativeHelperRequest {
  id: string;
  method: string;
  params: Record<string, unknown>;
}

export interface NativeHelperSuccess {
  id: string;
  ok: true;
  result: Record<string, unknown>;
}

export interface NativeHelperFailure {
  id: string;
  ok: false;
  error: {
    code: NativeHelperErrorCode | string;
    message: string;
    recoverable?: boolean;
    details?: Record<string, unknown>;
  };
}

export type NativeHelperResponse = NativeHelperSuccess | NativeHelperFailure;
