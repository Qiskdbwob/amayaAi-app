import { clipboard } from 'electron';
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

// ── Scroll directions accepted by mouse.scroll ───────────────────────────────
const SCROLL_DIRECTIONS = ['up', 'down', 'left', 'right'] as const;
type ScrollDirection = (typeof SCROLL_DIRECTIONS)[number];

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

export function closeWindow(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const windowId = asString(args['windowId']);
    if (!windowId) throw invalid('windowId is required');
    const result = await invoke(helper, 'window.close', { windowId });
    return { status: 'success', result };
  };
}

export function openApp(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const app = asString(args['app']) ?? asString(args['name']) ?? asString(args['target']);
    if (!app) throw invalid('app is required');
    const params: Record<string, unknown> = { app };
    const launchArgs = asString(args['args']);
    if (launchArgs !== undefined) params['args'] = launchArgs;
    const result = await invoke(helper, 'app.open', params);
    return { status: 'success', result };
  };
}

export function uiTree(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const params: Record<string, unknown> = {};
    const windowId = asString(args['windowId']);
    if (windowId !== undefined) params['windowId'] = windowId;
    const limit = asInteger(args['limit']);
    if (limit !== undefined) params['limit'] = clampInt(limit, 1, 1000);
    const result = await invoke(helper, 'ui.tree', params);
    return { status: 'success', result };
  };
}

export function uiFindText(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const text = asString(args['text']);
    if (!text) throw invalid('text is required');
    const params: Record<string, unknown> = { text };
    const windowId = asString(args['windowId']);
    if (windowId !== undefined) params['windowId'] = windowId;
    const limit = asInteger(args['limit']);
    if (limit !== undefined) params['limit'] = clampInt(limit, 1, 1000);
    const result = await invoke(helper, 'ui.find_text', params);
    return { status: 'success', result };
  };
}

export function uiClickElement(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const elementId = asString(args['elementId']) ?? asString(args['id']) ?? asString(args['handle']);
    if (!elementId) throw invalid('elementId is required');
    const result = await invoke(helper, 'ui.click_element', { elementId });
    return { status: 'success', result };
  };
}

export function mouseClick(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const relativeToWindowId = asString(args['relativeToWindowId']);
    const clientX = asInteger(args['clientX']);
    const clientY = asInteger(args['clientY']);
    let x = asInteger(args['x']);
    let y = asInteger(args['y']);

    if (relativeToWindowId && clientX !== undefined && clientY !== undefined) {
      // helper resolves ClientToScreen; nothing to do here beyond forwarding.
    } else if (x === undefined || y === undefined) {
      throw invalid('x and y are required (or relativeToWindowId + clientX + clientY)');
    }
    const button = asEnum(args['button'], ['left', 'right', 'middle']) ?? 'left';
    const clicks = asInteger(args['clicks']) ?? 1;
    if (clicks < 1 || clicks > 3) throw invalid('clicks must be 1, 2, or 3');
    const focusWindowId = asString(args['focusWindowId']);
    const modifiers = asString(args['modifiers']);

    const params: Record<string, unknown> = { button, clicks };
    if (x !== undefined) params['x'] = x;
    if (y !== undefined) params['y'] = y;
    if (relativeToWindowId) params['relativeToWindowId'] = relativeToWindowId;
    if (clientX !== undefined) params['clientX'] = clientX;
    if (clientY !== undefined) params['clientY'] = clientY;
    if (focusWindowId) params['focusWindowId'] = focusWindowId;
    if (modifiers) params['modifiers'] = modifiers;

    const result = await invoke(helper, 'mouse.click', params);
    return { status: 'success', result };
  };
}

/**
 * mouse.move — move the cursor to (x, y) without clicking.
 * Useful for revealing hover menus, tooltips, and positioning before drag.
 * duration controls how long the movement takes (0 = instant, max 2000 ms).
 */
export function mouseMove(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) {
      throw invalid('x and y are required integers');
    }
    const durationMs = clampInt(asInteger(args['durationMs']) ?? 0, 0, 2000);
    const focusWindowId = asString(args['focusWindowId']);
    const params: Record<string, unknown> = { x, y, durationMs };
    if (focusWindowId) params['focusWindowId'] = focusWindowId;

    const result = await invoke(helper, 'mouse.move', params);
    return { status: 'success', result };
  };
}

/**
 * mouse.scroll — scroll at the given coordinate.
 * direction: 'up' | 'down' | 'left' | 'right'
 * amount: number of scroll ticks (1–50, default 3).
 * Mirrors the Claude computer_use scroll action and OpenAI scroll_y/scroll_x.
 */
export function mouseScroll(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) {
      throw invalid('x and y are required integers');
    }
    const direction = asEnum(args['direction'], SCROLL_DIRECTIONS) ?? 'down';
    const amount = clampInt(asInteger(args['amount']) ?? 3, 1, 50);
    const focusWindowId = asString(args['focusWindowId']);
    const params: Record<string, unknown> = { x, y, direction, amount };
    if (focusWindowId) params['focusWindowId'] = focusWindowId;

    const result = await invoke(helper, 'mouse.scroll', params);
    return { status: 'success', result };
  };
}

/**
 * mouse.drag — press-and-hold at (startX, startY), move to (endX, endY),
 * then release. Supports optional intermediate waypoints via `path`.
 * durationMs controls total movement time (default 400 ms).
 * Mirrors Claude left_click_drag and OpenAI drag (path array).
 */
export function mouseDrag(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const startX = asInteger(args['startX']);
    const startY = asInteger(args['startY']);
    const endX = asInteger(args['endX']);
    const endY = asInteger(args['endY']);
    if (startX === undefined || startY === undefined) {
      throw invalid('startX and startY are required integers');
    }
    if (endX === undefined || endY === undefined) {
      throw invalid('endX and endY are required integers');
    }
    const button = asEnum(args['button'], ['left', 'right', 'middle']) ?? 'left';
    const durationMs = clampInt(asInteger(args['durationMs']) ?? 400, 50, 5000);

    // Optional intermediate waypoints: [{x, y}, ...]
    const rawPath = args['path'];
    const waypoints: Array<{ x: number; y: number }> = [];
    if (Array.isArray(rawPath)) {
      for (const pt of rawPath) {
        if (pt && typeof pt === 'object') {
          const px = asInteger((pt as Record<string, unknown>)['x']);
          const py = asInteger((pt as Record<string, unknown>)['y']);
          if (px !== undefined && py !== undefined) {
            waypoints.push({ x: px, y: py });
          }
        }
      }
    }

    const result = await invoke(helper, 'mouse.drag', {
      startX, startY, endX, endY, button, durationMs, waypoints,
      ...(asString(args['focusWindowId']) ? { focusWindowId: asString(args['focusWindowId']) } : {})
    });
    return { status: 'success', result };
  };
}

/**
 * mouse.press — press a button at (x, y) without releasing. Pair with mouse.release
 * for spreadsheet-style selection and tight drag control.
 */
export function mousePress(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) throw invalid('x and y are required integers');
    const button = asEnum(args['button'], ['left', 'right', 'middle']) ?? 'left';
    const focusWindowId = asString(args['focusWindowId']);
    const params: Record<string, unknown> = { x, y, button };
    if (focusWindowId) params['focusWindowId'] = focusWindowId;
    const result = await invoke(helper, 'mouse.press', params);
    return { status: 'success', result };
  };
}

/**
 * mouse.release — release a mouse button previously pressed via mouse.press.
 */
export function mouseRelease(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const button = asEnum(args['button'], ['left', 'right', 'middle']) ?? 'left';
    const result = await invoke(helper, 'mouse.release', { button });
    return { status: 'success', result };
  };
}

/**
 * mouse.hover — move the cursor to (x, y) and hold there for holdMs so
 * tooltips and hover menus become visible before the next action.
 */
export function mouseHover(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) throw invalid('x and y are required integers');
    const holdMs = clampInt(asInteger(args['holdMs']) ?? 400, 0, 5000);
    const focusWindowId = asString(args['focusWindowId']);
    const params: Record<string, unknown> = { x, y, holdMs };
    if (focusWindowId) params['focusWindowId'] = focusWindowId;
    const result = await invoke(helper, 'mouse.hover', params);
    return { status: 'success', result };
  };
}

/**
 * ui.hit_test — which top-level window is at (x, y)? Used to verify a visual
 * click target before spending an actual click, and as a recovery signal after
 * a click lands in the wrong window.
 */
export function uiHitTest(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const x = asInteger(args['x']);
    const y = asInteger(args['y']);
    if (x === undefined || y === undefined) throw invalid('x and y are required integers');
    const result = await invoke(helper, 'ui.hit_test', { x, y });
    return { status: 'success', result };
  };
}

/**
 * keyboard.hold — press a single key down, wait durationMs, release. Mirrors
 * Claude computer_use hold_key.
 */
export function keyboardHold(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const key = asString(args['key']);
    if (!key) throw invalid('key is required');
    const durationMs = clampInt(asInteger(args['durationMs']) ?? 200, 10, 10_000);
    const result = await invoke(helper, 'keyboard.hold', { key, durationMs });
    return { status: 'success', result };
  };
}

/**
 * diagnostics — report DPI, elevation, Windows build, screen bounds, and
 * supported capture paths. Agents call this once per session so they can
 * refuse tasks that need elevation or a secure desktop.
 */
export function diagnostics(helper: NativeHelperClient) {
  return async (): Promise<LocalToolResult> => {
    const result = await invoke(helper, 'diagnostics', {});
    return { status: 'success', result };
  };
}

/**
 * input.wait — pause execution for a fixed duration.
 * Useful for waiting for animations, loading spinners, or rate-limited UIs.
 * durationMs: 100–10000 ms (default 1000 ms).
 * This is a pure bridge-side sleep — no native helper call needed.
 */
export function inputWait() {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const durationMs = clampInt(asInteger(args['durationMs']) ?? 1000, 100, 10000);
    await new Promise<void>((resolve) => setTimeout(resolve, durationMs));
    return {
      status: 'success',
      result: { waited: true, durationMs }
    };
  };
}

export function keyboardType(helper: NativeHelperClient) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const text = asString(args['text']);
    if (text === undefined) throw invalid('text is required');
    if (text.length > 5000) throw invalid('text exceeds 5000 chars');

    const mode = asEnum(args['mode'], ['auto', 'keys', 'paste']) ?? 'auto';
    const shouldPaste =
      mode === 'paste' ||
      (mode === 'auto' && (text.length > 80 || /\r|\n|\t/.test(text)));

    if (shouldPaste) {
      try {
        clipboard.writeText(text);
      } catch (err) {
        throw new ToolInvocationError(
          'EXECUTION_FAILED',
          (err as Error).message || 'Clipboard write failed before paste.',
          {},
          true
        );
      }
      const result = await invoke(helper, 'keyboard.hotkey', { keys: ['ctrl', 'v'] });
      return {
        status: 'success',
        result: {
          typed: true,
          length: text.length,
          mode: 'paste',
          pasteHotkey: result['keys'] ?? ['ctrl', 'v']
        }
      };
    }

    const intervalMs = clampInt(asInteger(args['intervalMs']) ?? 10, 0, 100);
    const result = await invoke(helper, 'keyboard.type', { text, intervalMs });
    // Do NOT echo text back. Helper already returns length only.
    return { status: 'success', result: { ...result, mode: 'keys' } };
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
