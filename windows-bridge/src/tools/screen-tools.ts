import { desktopCapturer, nativeImage, screen, type Rectangle } from 'electron';
import type { NativeHelperClient } from '../native/native-helper-client';
import type { ScreenCapturePolicyConfig } from '../permissions/security-policy';
import { ToolInvocationError, type LocalToolResult } from './tool-result';

interface Bounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface WindowMetadata {
  id: string;
  windowId: string;
  label: string;
  title: string;
  processId?: number;
  processName: string;
  state: 'normal' | 'maximized' | 'minimized' | 'unknown';
  visible: boolean;
  focused: boolean;
  zIndex: number;
  bounds: Bounds;
  screenshotBounds: Bounds | null;
  center: { x: number; y: number };
  titleBarPoint: { x: number; y: number };
  closeButtonApprox: { x: number; y: number };
  clientAreaApprox: Bounds;
  overlappedBy: Array<{ id: string; label: string; title: string; processName: string }>;
}

/**
 * screen.capture — capture the primary display (or a specific index) using
 * Electron's desktopCapturer API. The result includes real image data plus
 * coordinate/window metadata so the agent can map visual targets to reliable
 * mouse/window tool calls without separately calling window.list first.
 */
export function captureScreenFactory(
  defaults: ScreenCapturePolicyConfig,
  helper: NativeHelperClient | null = null
) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const format = parseFormat(args['format'], defaults.defaultFormat);
    const quality = parseQuality(args['quality'], defaults.defaultQuality);
    const maxWidth = parseMaxWidth(args['maxWidth'], defaults.defaultMaxWidth);
    const displayIndex = parseDisplayIndex(args['displayIndex']);
    const includeWindows = parseBoolean(args['includeWindows'], true);

    const displays = screen.getAllDisplays();
    if (displays.length === 0) {
      throw new ToolInvocationError('EXECUTION_FAILED', 'No displays detected.');
    }
    if (displayIndex >= displays.length) {
      throw new ToolInvocationError(
        'INVALID_ARGS',
        `displayIndex ${displayIndex} is out of range (have ${displays.length} displays).`
      );
    }
    const display = displays[displayIndex]!;
    const displayBounds = toBounds(display.bounds);
    const virtualBounds = unionBounds(displays.map((d) => toBounds(d.bounds)));

    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: {
        width: display.size.width,
        height: display.size.height
      }
    });

    const match =
      sources.find((s) => s.display_id === String(display.id)) ??
      sources[displayIndex] ??
      sources[0];

    if (!match) {
      throw new ToolInvocationError(
        'EXECUTION_FAILED',
        'desktopCapturer returned no sources.'
      );
    }

    let thumb = match.thumbnail;
    if (thumb.isEmpty()) {
      throw new ToolInvocationError(
        'EXECUTION_FAILED',
        'Captured thumbnail is empty.'
      );
    }

    const original = thumb.getSize();
    if (maxWidth !== null && original.width > maxWidth) {
      // Preserve aspect ratio — nativeImage.resize handles the math when
      // only one dimension is provided.
      const resized = thumb.resize({ width: maxWidth });
      if (!resized.isEmpty()) {
        thumb = resized;
      }
    }

    const buffer =
      format === 'jpeg' ? thumb.toJPEG(clampQuality(quality)) : thumb.toPNG();
    const size = thumb.getSize();
    const cursor = screen.getCursorScreenPoint();
    const rawWindows = includeWindows ? await readWindows(helper) : [];
    const windows = buildWindowMetadata(rawWindows, displayBounds, size.width, size.height);
    const activeWindow = windows.find((w) => w.focused) ?? windows[0] ?? null;

    return {
      status: 'success',
      result: {
        imageBase64: buffer.toString('base64'),
        width: size.width,
        height: size.height,
        format,
        displayIndex,
        originalWidth: original.width,
        originalHeight: original.height,
        quality: format === 'jpeg' ? clampQuality(quality) : undefined,
        accessibility: {
          captureId: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
          capturedAt: Date.now(),
          coordinateGuide: {
            origin: 'top-left of captured display in Windows virtual-screen coordinates',
            axes: 'x increases right; y increases down',
            mouseToolsUse: 'Windows virtual-screen coordinates',
            screenshotCoordinatesUse: 'top-left of returned screenshot image',
            clickFormula:
              'screenX = displayBounds.x + screenshotX * screenshotToScreenScale.x; screenY = displayBounds.y + screenshotY * screenshotToScreenScale.y',
            reverseFormula:
              'screenshotX = (screenX - displayBounds.x) * screenToScreenshotScale.x; screenshotY = (screenY - displayBounds.y) * screenToScreenshotScale.y',
            verifyAfterAction: true
          },
          displayBounds,
          virtualBounds,
          workArea: toBounds(display.workArea),
          displayScaleFactor: display.scaleFactor,
          screenshotToScreenScale: {
            x: displayBounds.width / size.width,
            y: displayBounds.height / size.height
          },
          screenToScreenshotScale: {
            x: size.width / displayBounds.width,
            y: size.height / displayBounds.height
          },
          cursorPosition: {
            x: cursor.x,
            y: cursor.y,
            screenshotX: Math.round((cursor.x - displayBounds.x) * (size.width / displayBounds.width)),
            screenshotY: Math.round((cursor.y - displayBounds.y) * (size.height / displayBounds.height)),
            onCapturedDisplay: pointInBounds(cursor.x, cursor.y, displayBounds)
          },
          windows,
          activeWindow,
          visualLabels: windows.map((w) => ({
            label: w.label,
            windowId: w.windowId,
            title: w.title,
            processName: w.processName,
            state: w.state,
            focused: w.focused,
            zIndex: w.zIndex,
            bounds: w.bounds,
            screenshotBounds: w.screenshotBounds
          })),
          actionHints: {
            focus: 'Use window.focus(windowId), wait briefly, then screen.capture before sending input.',
            close: 'Use window.close(windowId) when a windowId is known; prefer it over Alt+F4.',
            click:
              'Derive mouse.click x/y from the latest screenshot using coordinateGuide and prefer a focused target window.'
          }
        }
      }
    };
  };
}

async function readWindows(helper: NativeHelperClient | null): Promise<Record<string, unknown>[]> {
  if (!helper) return [];
  try {
    const result = await helper.invoke('window.list', {}, 3_000);
    const windows = result['windows'];
    return Array.isArray(windows)
      ? windows.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
      : [];
  } catch {
    return [];
  }
}

function buildWindowMetadata(
  rawWindows: Record<string, unknown>[],
  displayBounds: Bounds,
  screenshotWidth: number,
  screenshotHeight: number
): WindowMetadata[] {
  const base = rawWindows
    .map((raw, index) => normalizeWindow(raw, index, displayBounds, screenshotWidth, screenshotHeight))
    .filter((w): w is WindowMetadata => w !== null);

  return base.map((window, index) => ({
    ...window,
    overlappedBy: base
      .slice(0, index)
      .filter((front) => intersects(front.bounds, window.bounds))
      .slice(0, 5)
      .map((front) => ({
        id: front.id,
        label: front.label,
        title: front.title,
        processName: front.processName
      }))
  }));
}

function normalizeWindow(
  raw: Record<string, unknown>,
  index: number,
  displayBounds: Bounds,
  screenshotWidth: number,
  screenshotHeight: number
): WindowMetadata | null {
  const bounds = parseBounds(raw['bounds']);
  if (!bounds || bounds.width <= 0 || bounds.height <= 0) return null;
  if (!intersects(bounds, displayBounds)) return null;

  const id = asString(raw['id']) ?? asString(raw['windowId']) ?? '';
  if (!id) return null;
  const label = `W${index + 1}`;
  const title = asString(raw['title']) ?? '';
  const processName = asString(raw['processName']) ?? '';
  const state = parseWindowState(raw['state']);
  const focused = raw['focused'] === true;
  const visible = raw['visible'] !== false;
  const processId = typeof raw['processId'] === 'number' ? raw['processId'] : undefined;
  const center = {
    x: Math.round(bounds.x + bounds.width / 2),
    y: Math.round(bounds.y + bounds.height / 2)
  };
  const titleBarPoint = {
    x: center.x,
    y: Math.round(bounds.y + Math.min(18, Math.max(8, bounds.height * 0.03)))
  };
  const closeButtonApprox = {
    x: Math.round(bounds.x + bounds.width - 24),
    y: titleBarPoint.y
  };
  const clientTopInset = Math.min(96, Math.max(32, Math.round(bounds.height * 0.08)));
  const clientAreaApprox = {
    x: bounds.x,
    y: bounds.y + clientTopInset,
    width: bounds.width,
    height: Math.max(0, bounds.height - clientTopInset)
  };

  return {
    id,
    windowId: id,
    label,
    title,
    processId,
    processName,
    state,
    visible,
    focused,
    zIndex: index,
    bounds,
    screenshotBounds: toScreenshotBounds(bounds, displayBounds, screenshotWidth, screenshotHeight),
    center,
    titleBarPoint,
    closeButtonApprox,
    clientAreaApprox,
    overlappedBy: []
  };
}

function parseWindowState(value: unknown): WindowMetadata['state'] {
  if (value === 'normal' || value === 'maximized' || value === 'minimized') return value;
  return 'unknown';
}

function parseBounds(value: unknown): Bounds | null {
  if (!value || typeof value !== 'object') return null;
  const obj = value as Record<string, unknown>;
  const x = toInteger(obj['x']);
  const y = toInteger(obj['y']);
  const width = toInteger(obj['width']);
  const height = toInteger(obj['height']);
  if (x === undefined || y === undefined || width === undefined || height === undefined) return null;
  return { x, y, width, height };
}

function toScreenshotBounds(
  bounds: Bounds,
  displayBounds: Bounds,
  screenshotWidth: number,
  screenshotHeight: number
): Bounds | null {
  const clipped = intersectBounds(bounds, displayBounds);
  if (!clipped) return null;
  const sx = screenshotWidth / displayBounds.width;
  const sy = screenshotHeight / displayBounds.height;
  return {
    x: Math.round((clipped.x - displayBounds.x) * sx),
    y: Math.round((clipped.y - displayBounds.y) * sy),
    width: Math.round(clipped.width * sx),
    height: Math.round(clipped.height * sy)
  };
}

function toBounds(value: Rectangle): Bounds {
  return { x: value.x, y: value.y, width: value.width, height: value.height };
}

function unionBounds(bounds: Bounds[]): Bounds {
  const left = Math.min(...bounds.map((b) => b.x));
  const top = Math.min(...bounds.map((b) => b.y));
  const right = Math.max(...bounds.map((b) => b.x + b.width));
  const bottom = Math.max(...bounds.map((b) => b.y + b.height));
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function intersects(a: Bounds, b: Bounds): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}

function intersectBounds(a: Bounds, b: Bounds): Bounds | null {
  const x = Math.max(a.x, b.x);
  const y = Math.max(a.y, b.y);
  const right = Math.min(a.x + a.width, b.x + b.width);
  const bottom = Math.min(a.y + a.height, b.y + b.height);
  if (right <= x || bottom <= y) return null;
  return { x, y, width: right - x, height: bottom - y };
}

function pointInBounds(x: number, y: number, bounds: Bounds): boolean {
  return x >= bounds.x && y >= bounds.y && x < bounds.x + bounds.width && y < bounds.y + bounds.height;
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

// ── parsing helpers ─────────────────────────────────────────────────────────

function parseFormat(
  value: unknown,
  fallback: 'png' | 'jpeg'
): 'png' | 'jpeg' {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== 'string') {
    throw new ToolInvocationError('INVALID_ARGS', 'format must be a string');
  }
  const lower = value.toLowerCase();
  if (lower === 'png' || lower === 'jpeg') return lower;
  if (lower === 'jpg') return 'jpeg';
  throw new ToolInvocationError(
    'INVALID_ARGS',
    `format must be 'png' or 'jpeg' (got '${value}')`
  );
}

function parseQuality(value: unknown, fallback: number): number {
  if (value === undefined || value === null) return fallback;
  const n = toInteger(value);
  if (n === undefined) {
    throw new ToolInvocationError('INVALID_ARGS', 'quality must be an integer');
  }
  return n;
}

function parseMaxWidth(value: unknown, fallback: number | null): number | null {
  if (value === undefined || value === null) return fallback;
  const n = toInteger(value);
  if (n === undefined || n <= 0) {
    throw new ToolInvocationError(
      'INVALID_ARGS',
      'maxWidth must be a positive integer'
    );
  }
  return n;
}

function parseDisplayIndex(value: unknown): number {
  if (value === undefined || value === null) return 0;
  const n = toInteger(value);
  if (n === undefined || n < 0) {
    throw new ToolInvocationError(
      'INVALID_ARGS',
      'displayIndex must be a non-negative integer'
    );
  }
  return n;
}

function parseBoolean(value: unknown, fallback: boolean): boolean {
  if (value === undefined || value === null) return fallback;
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') {
    const lower = value.toLowerCase().trim();
    if (lower === 'true' || lower === '1' || lower === 'yes') return true;
    if (lower === 'false' || lower === '0' || lower === 'no') return false;
  }
  throw new ToolInvocationError('INVALID_ARGS', 'boolean argument must be true or false');
}

function toInteger(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'string') {
    const n = Number(value);
    if (Number.isFinite(n)) return Math.trunc(n);
  }
  return undefined;
}

function clampQuality(value: number): number {
  if (!Number.isFinite(value)) return 85;
  if (value < 1) return 1;
  if (value > 100) return 100;
  return Math.trunc(value);
}

// Keep the old export name so callers that imported `captureScreen` still work —
// it produces the same behavior as the factory built with default policy values.
export async function captureScreen(
  args: Record<string, unknown>
): Promise<LocalToolResult> {
  return captureScreenFactory({
    defaultFormat: 'jpeg',
    defaultQuality: 72,
    defaultMaxWidth: 1280
  })(args);
}

// Prevent the `nativeImage` import from tree-shaking out when TS doesn't see
// direct usage — we rely on it for `thumb.resize` which is a method on the
// underlying NativeImage class.
void nativeImage;
