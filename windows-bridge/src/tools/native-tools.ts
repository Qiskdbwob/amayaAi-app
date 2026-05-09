import type { NativeHelperClient } from '../native/native-helper-client';
import { mapToBridgeErrorCode, NativeHelperError } from '../native/native-helper-errors';
import { ToolInvocationError, type LocalToolResult } from './tool-result';

/**
 * Thin wrappers that forward bridge tool calls to the native helper and map any
 * helper error onto a structured [[ToolInvocationError]] so the websocket server
 * can surface a clean `BridgeToolErrorCode` to the Android agent.
 *
 * Validation is done up-front so we never spam the helper with obviously bad
 * payloads.
 */
export function listWindowsReal(helper: NativeHelperClient) {
  return async (): Promise<LocalToolResult> => {
    const result = await invoke(helper, 'window.list', {});
    return { status: 'success', result };
  };
}

export function focusWindow(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const windowId = asString(args['windowId']);
    if (!windowId) throw invalid('windowId is required');
    const result = await invoke(helper, 'window.focus', { windowId });
    return { status: 'success', result };
  };
}

export function mouseClick(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) {
      throw invalid('x and y are required integers');
    }
    const button = asEnum(args['button'], ['left', 'right', 'middle']) ?? 'left';
    const clicks = asInteger(args['clicks']) ?? 1;
    if (clicks < 1 || clicks > 2) throw invalid('clicks must be 1 or 2');

    const result = await invoke(helper, 'mouse.click', { x, y, button, clicks });
    return { status: 'success', result };
  };
}

export function keyboardType(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const text = asString(args['text']);
    if (text === undefined) throw invalid('text is required');
    if (text.length > 5000) throw invalid('text exceeds 5000 chars');
    const intervalMs = clampInt(asInteger(args['intervalMs']) ?? 5, 0, 100);
    const result = await invoke(helper, 'keyboard.type', { text, intervalMs });
    // Do NOT echo text back. Helper already returns length only.
    return { status: 'success', result };
  };
}

export function keyboardHotkey(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const clean = normalizeHotkeyKeys(args);
    if (clean.length === 0) {
      throw invalid('keys or combo must be a non-empty hotkey');
    }
    if (clean.length > 4) throw invalid('hotkey exceeds 4 keys');
    const result = await invoke(helper, 'keyboard.hotkey', { keys: clean });
    return { status: 'success', result };
  };
}

// ── helpers ─────────────────────────────────────────────────────────────────

async function invoke(
  helper: NativeHelperClient,
  method: string,
  params: Record<string, unknown>
): Promise<Record<string, unknown>> {
  try {
    return await helper.invoke(method, params);
  } catch (err) {
    if (err instanceof NativeHelperError) {
      throw new ToolInvocationError(
        mapToBridgeErrorCode(err.code),
        err.message,
        err.details,
        err.recoverable
      );
    }
    throw new ToolInvocationError(
      'EXECUTION_FAILED',
      (err as Error).message ?? 'native helper failed',
      {},
      true
    );
  }
}

function invalid(message: string): ToolInvocationError {
  return new ToolInvocationError('INVALID_ARGS', message);
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function asInteger(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'string') {
    const n = Number(value);
    if (Number.isFinite(n)) return Math.trunc(n);
  }
  return undefined;
}

function asEnum<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  if (typeof value !== 'string') return undefined;
  const lower = value.toLowerCase();
  return (allowed as readonly string[]).includes(lower) ? (lower as T) : undefined;
}

function normalizeHotkeyKeys(args: Record<string, unknown>): string[] {
  return normalizeHotkeyValue(
    args['keys'] ?? args['combo'] ?? args['hotkey'] ?? args['shortcut']
  );
}

function normalizeHotkeyValue(value: unknown): string[] {
  if (typeof value === 'string') return splitHotkeyString(value);
  if (Array.isArray(value)) return value.flatMap((item) => normalizeHotkeyValue(item));
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    if ('keys' in obj || 'combo' in obj || 'hotkey' in obj || 'shortcut' in obj) {
      return normalizeHotkeyValue(obj['keys'] ?? obj['combo'] ?? obj['hotkey'] ?? obj['shortcut']);
    }
    return Object.keys(obj)
      .sort(compareMaybeNumericKeys)
      .flatMap((key) => normalizeHotkeyValue(obj[key]));
  }
  return [];
}

function compareMaybeNumericKeys(a: string, b: string): number {
  const an = Number(a);
  const bn = Number(b);
  const ai = Number.isFinite(an);
  const bi = Number.isFinite(bn);
  if (ai && bi) return an - bn;
  if (ai) return -1;
  if (bi) return 1;
  return a.localeCompare(b);
}

function splitHotkeyString(value: string): string[] {
  const trimmed = value.trim();
  if (!trimmed) return [];
  const separator = trimmed.includes('+') ? /\s*\+\s*/ : /\s*,\s*|\s+/;
  return trimmed
    .split(separator)
    .map((part) => part.trim())
    .filter(Boolean);
}

function clampInt(value: number, min: number, max: number): number {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}
