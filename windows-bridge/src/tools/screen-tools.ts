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
  focusable: boolean;
  zIndex: number;
  monitorIndex: number;
  scaleFactor: number;
  /** Window rect in physical pixels, Windows virtual-screen origin. */
  bounds: Bounds;
  /** GetClientRect + ClientToScreen, physical pixels. Null when helper does not report. */
  clientBounds: Bounds | null;
  /** Window rect clipped and projected into the captured image, pixel space of the image. */
  imageBounds: Bounds | null;
  /** Back-compat alias for imageBounds (older clients). */
  screenshotBounds: Bounds | null;
  center: { x: number; y: number };
  titleBarPoint: { x: number; y: number };
  closeButtonPoint: { x: number; y: number };
  /** Back-compat alias for closeButtonPoint. */
  closeButtonApprox: { x: number; y: number };
  /** Back-compat approximate client area. Prefer clientBounds when available. */
  clientAreaApprox: Bounds;
  /** 0..1 fraction of the window area covered by windows with a smaller zIndex. */
  overlapRatio: number;
  overlappedBy: Array<{ id: string; label: string; title: string; processName: string }>;
}

/**
 * screen.capture — capture the primary display (or a specific index) using
 * Electron's desktopCapturer API. The result includes real image data plus
 * coordinate/window metadata so the agent can map visual targets to reliable
 * mouse/window tool calls without separately calling window.list first.
 *
 * All coordinates in the result are in Windows PHYSICAL pixels, rooted at the
 * virtual-screen origin. Electron reports display bounds/cursor in DIPs, so
 * every such value is multiplied by display.scaleFactor before being emitted.
 * The native helper already runs PerMonitorV2, so window bounds arriving from
 * it are physical pixels already and pass through unchanged.
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
    const includeCursor = parseBoolean(args['includeCursor'], true);

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
    const displayScale = display.scaleFactor || 1;
    // Convert Electron DIP bounds → Windows physical pixels.
    const displayBounds = scaleBoundsToPhysical(toBounds(display.bounds), displayScale);
    const workArea = scaleBoundsToPhysical(toBounds(display.workArea), displayScale);
    const virtualBounds = unionBounds(
      displays.map((d) => scaleBoundsToPhysical(toBounds(d.bounds), d.scaleFactor || 1))
    );

    const thumbSize = {
      width: Math.max(1, Math.round(display.size.width * displayScale)),
      height: Math.max(1, Math.round(display.size.height * displayScale))
    };
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: thumbSize
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
    // imageToScreen = physical px per image px. When the image matches the
    // native display we always get 1:1; maxWidth downscaling changes this.
    const imageToScreen = {
      x: displayBounds.width / size.width,
      y: displayBounds.height / size.height
    };
    const screenToImage = {
      x: size.width / displayBounds.width,
      y: size.height / displayBounds.height
    };

    const cursorPoint = includeCursor ? screen.getCursorScreenPoint() : { x: 0, y: 0 };
    const cursor = includeCursor
      ? {
          x: Math.round(cursorPoint.x * displayScale),
          y: Math.round(cursorPoint.y * displayScale),
          raw: { x: cursorPoint.x, y: cursorPoint.y }
        }
      : null;

    const rawWindows = includeWindows ? await readWindows(helper) : [];
    const windows = buildWindowMetadata(
      rawWindows,
      displayBounds,
      displayIndex,
      displayScale,
      size.width,
      size.height
    );
    const activeWindow = windows.find((w) => w.focused) ?? windows[0] ?? null;
    const recommendedWindowId = pickRecommendedWindow(windows, cursor);

    const captureId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
    const capturedAt = Date.now();
    const cursorPayload = cursor
      ? {
          x: cursor.x,
          y: cursor.y,
          imageX: Math.round((cursor.x - displayBounds.x) * screenToImage.x),
          imageY: Math.round((cursor.y - displayBounds.y) * screenToImage.y),
          // Back-compat aliases
          screenshotX: Math.round((cursor.x - displayBounds.x) * screenToImage.x),
          screenshotY: Math.round((cursor.y - displayBounds.y) * screenToImage.y),
          onCaptured: pointInBounds(cursor.x, cursor.y, displayBounds),
          onCapturedDisplay: pointInBounds(cursor.x, cursor.y, displayBounds)
        }
      : null;

    return {
      status: 'success',
      result: {
        imageBase64: buffer.toString('base64'),
        width: size.width,
        height: size.height,
        format,
        captureMode: 'display',
        displayIndex,
        originalWidth: original.width,
        originalHeight: original.height,
        quality: format === 'jpeg' ? clampQuality(quality) : undefined,
        accessibility: {
          unit: 'physical_px',
          captureId,
          capturedAt,
          dpi: {
            displayIndex,
            scaleFactor: displayScale,
            effectiveDpi: Math.round(96 * displayScale)
          },
          coordinateGuide: {
            unit: 'physical_px',
            origin: 'top-left of captured display in Windows virtual-screen physical pixels',
            axes: 'x increases right; y increases down',
            mouseToolsUse: 'physical pixels in Windows virtual-screen coordinates',
            screenshotCoordinatesUse: 'top-left of returned screenshot image',
            imageToScreenFormula:
              'screenX = displayBounds.x + imageX * imageToScreenScale.x; screenY = displayBounds.y + imageY * imageToScreenScale.y',
            screenToImageFormula:
              'imageX = (screenX - displayBounds.x) * screenToImageScale.x; imageY = (screenY - displayBounds.y) * screenToImageScale.y',
            // Back-compat formula names referring to screenshot instead of image.
            clickFormula:
              'screenX = displayBounds.x + screenshotX * screenshotToScreenScale.x; screenY = displayBounds.y + screenshotY * screenshotToScreenScale.y',
            reverseFormula:
              'screenshotX = (screenX - displayBounds.x) * screenToScreenshotScale.x; screenshotY = (screenY - displayBounds.y) * screenToScreenshotScale.y',
            verifyAfterAction: true
          },
          captureBounds: displayBounds,
          displayBounds,
          virtualBounds,
          workArea,
          displayScaleFactor: displayScale,
          imageToScreenScale: imageToScreen,
          screenToImageScale: screenToImage,
          // Back-compat aliases
          screenshotToScreenScale: imageToScreen,
          screenToScreenshotScale: screenToImage,
          cursor: cursorPayload,
          cursorPosition: cursorPayload,
          windows,
          activeWindow,
          recommendedWindowId,
          visualLabels: windows.map((w) => ({
            label: w.label,
            windowId: w.windowId,
            title: w.title,
            processName: w.processName,
            state: w.state,
            focused: w.focused,
            zIndex: w.zIndex,
            bounds: w.bounds,
            imageBounds: w.imageBounds,
            screenshotBounds: w.imageBounds
          })),
          hints: {
            focus:
              'Pass recommendedWindowId (or activeWindow.windowId) as focusWindowId on mouse.click / mouse.scroll / mouse.drag to force foreground before input.',
            verify:
              'After each UI-changing action call screen.capture again (mode=region recommended) over the affected bounds to confirm the UI updated.',
            coordinates:
              'All coordinates are Windows physical pixels. Use coordinateGuide to convert between image and screen.'
          },
          actionHints: {
            focus: 'Use window.focus(windowId), wait briefly, then screen.capture before sending input.',
            close: 'Use window.close(windowId) when a windowId is known; prefer it over Alt+F4.',
            click:
              'Derive mouse.click x/y from the latest screenshot using coordinateGuide and prefer a focused target window.'
          },
          limits: {
            windowsTruncated: false,
            partial: false,
            reason: null as string | null
          }
        }
      }
    };
  };
}

function pickRecommendedWindow(
  windows: WindowMetadata[],
  cursor: { x: number; y: number } | null
): string | null {
  if (windows.length === 0) return null;
  const focused = windows.find((w) => w.focused && w.visible);
  if (focused && focused.overlapRatio < 0.3) return focused.windowId;
  const underCursor = cursor
    ? windows.find((w) => w.visible && pointInBounds(cursor.x, cursor.y, w.bounds))
    : null;
  if (underCursor) return underCursor.windowId;
  const topUnobstructed = windows.find(
    (w) => w.visible && w.focusable && w.overlapRatio === 0
  );
  if (topUnobstructed) return topUnobstructed.windowId;
  return focused?.windowId ?? windows[0]?.windowId ?? null;
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
  displayIndex: number,
  displayScale: number,
  screenshotWidth: number,
  screenshotHeight: number
): WindowMetadata[] {
  const base = rawWindows
    .map((raw, index) =>
      normalizeWindow(raw, index, displayBounds, displayIndex, displayScale, screenshotWidth, screenshotHeight)
    )
    .filter((w): w is WindowMetadata => w !== null);

  return base.map((window, index) => {
    const frontals = base.slice(0, index).filter((front) => intersects(front.bounds, window.bounds));
    const overlapRatio = computeOverlapRatio(window.bounds, frontals.map((f) => f.bounds));
    return {
      ...window,
      overlapRatio,
      overlappedBy: frontals.slice(0, 5).map((front) => ({
        id: front.id,
        label: front.label,
        title: front.title,
        processName: front.processName
      }))
    };
  });
}

function computeOverlapRatio(target: Bounds, covers: Bounds[]): number {
  const area = Math.max(1, target.width * target.height);
  if (covers.length === 0) return 0;
  // Approximate: sum of intersection areas capped at target area. Fine for
  // a heuristic — the agent mostly cares about "is the window visible enough
  // to click into without focusing first?".
  let covered = 0;
  for (const c of covers) {
    const inter = intersectBounds(target, c);
    if (inter) covered += inter.width * inter.height;
    if (covered >= area) return 1;
  }
  return Math.min(1, covered / area);
}

function normalizeWindow(
  raw: Record<string, unknown>,
  index: number,
  displayBounds: Bounds,
  displayIndex: number,
  displayScale: number,
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
  const focusable = raw['focusable'] !== false;
  const processId = typeof raw['processId'] === 'number' ? raw['processId'] : undefined;
  const rawScale =
    typeof raw['scaleFactor'] === 'number' && Number.isFinite(raw['scaleFactor'] as number)
      ? (raw['scaleFactor'] as number)
      : displayScale;
  const clientBoundsRaw = parseBounds(raw['clientBounds']);
  const center = {
    x: Math.round(bounds.x + bounds.width / 2),
    y: Math.round(bounds.y + bounds.height / 2)
  };
  const titleBarPoint = {
    x: center.x,
    y: Math.round(bounds.y + Math.min(18, Math.max(8, bounds.height * 0.03)))
  };
  const closeButtonPoint = {
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

  const imageBounds = toImageBounds(bounds, displayBounds, screenshotWidth, screenshotHeight);
  const helperZIndex = typeof raw['zIndex'] === 'number' ? (raw['zIndex'] as number) : null;
  const zIndex = helperZIndex ?? index;

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
    focusable,
    zIndex,
    monitorIndex: displayIndex,
    scaleFactor: rawScale,
    bounds,
    clientBounds: clientBoundsRaw,
    imageBounds,
    screenshotBounds: imageBounds,
    center,
    titleBarPoint,
    closeButtonPoint,
    closeButtonApprox: closeButtonPoint,
    clientAreaApprox,
    overlapRatio: 0,
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

function toImageBounds(
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

/** @deprecated use toImageBounds. Kept so older call sites keep compiling. */
function toScreenshotBounds(
  bounds: Bounds,
  displayBounds: Bounds,
  screenshotWidth: number,
  screenshotHeight: number
): Bounds | null {
  return toImageBounds(bounds, displayBounds, screenshotWidth, screenshotHeight);
}

function scaleBoundsToPhysical(bounds: Bounds, scale: number): Bounds {
  if (scale === 1) return bounds;
  return {
    x: Math.round(bounds.x * scale),
    y: Math.round(bounds.y * scale),
    width: Math.round(bounds.width * scale),
    height: Math.round(bounds.height * scale)
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
