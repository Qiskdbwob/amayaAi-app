import { ToolInvocationError, type LocalToolResult } from './tool-result';

/**
 * Phase 4 input tools are deliberately stubs. They report a structured
 * `EXECUTION_FAILED` so the Android client surfaces a clear error instead of
 * silently succeeding. Real mouse/keyboard input arrives in Phase 5 through the
 * native helper.
 */
export async function mouseClickStub(): Promise<LocalToolResult> {
  throw new ToolInvocationError(
    'EXECUTION_FAILED',
    'Native input helper is not implemented yet (mouse.click).'
  );
}

export async function keyboardTypeStub(): Promise<LocalToolResult> {
  throw new ToolInvocationError(
    'EXECUTION_FAILED',
    'Native input helper is not implemented yet (keyboard.type).'
  );
}

export async function keyboardHotkeyStub(): Promise<LocalToolResult> {
  throw new ToolInvocationError(
    'EXECUTION_FAILED',
    'Native input helper is not implemented yet (keyboard.hotkey).'
  );
}

export async function clipboardWriteStub(): Promise<LocalToolResult> {
  throw new ToolInvocationError(
    'EXECUTION_FAILED',
    'Clipboard write is stubbed in Phase 4.'
  );
}
