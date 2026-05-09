import { desktopCapturer, nativeImage, screen } from 'electron';
import type { ScreenCapturePolicyConfig } from '../permissions/security-policy';
import { ToolInvocationError, type LocalToolResult } from './tool-result';

/**
 * screen.capture — capture the primary display (or a specific index) using
 * Electron's desktopCapturer API. Supports optional `format`, `quality`,
 * `displayIndex`, and `maxWidth` resize so large payloads don't overwhelm
 * Android chat rendering.
 */
export function captureScreenFactory(defaults: ScreenCapturePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const format = parseFormat(args['format'], defaults.defaultFormat);
    const quality = parseQuality(args['quality'], defaults.defaultQuality);
    const maxWidth = parseMaxWidth(args['maxWidth'], defaults.defaultMaxWidth);
    const displayIndex = parseDisplayIndex(args['displayIndex']);

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
        quality: format === 'jpeg' ? clampQuality(quality) : undefined
      }
    };
  };
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
    defaultFormat: 'png',
    defaultQuality: 85,
    defaultMaxWidth: null
  })(args);
}

// Prevent the `nativeImage` import from tree-shaking out when TS doesn't see
// direct usage — we rely on it for `thumb.resize` which is a method on the
// underlying NativeImage class.
void nativeImage;
