import { clipboard } from 'electron';
import { ToolInvocationError, type LocalToolResult } from './tool-result';

const MAX_CLIPBOARD_TEXT_LENGTH = 500_000;

/**
 * clipboard.write — write text to the Windows clipboard from the Electron main
 * process. The result deliberately returns length only so clipboard contents are
 * never echoed into the bridge logs or tool result previews.
 */
export function clipboardWrite() {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const text = typeof args['text'] === 'string' ? args['text'] : null;
    if (text === null) {
      throw new ToolInvocationError('INVALID_ARGS', 'text is required');
    }
    if (text.length > MAX_CLIPBOARD_TEXT_LENGTH) {
      throw new ToolInvocationError(
        'INVALID_ARGS',
        `text exceeds ${MAX_CLIPBOARD_TEXT_LENGTH} chars`
      );
    }

    try {
      clipboard.writeText(text);
    } catch (err) {
      throw new ToolInvocationError(
        'EXECUTION_FAILED',
        (err as Error).message || 'Clipboard write failed.',
        {},
        true
      );
    }

    return {
      status: 'success',
      result: {
        written: true,
        length: text.length
      }
    };
  };
}
