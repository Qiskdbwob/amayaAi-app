import { BrowserWindow } from 'electron';
import type { LocalToolResult } from './tool-result';

/**
 * window.list — Phase 4 stub.
 *
 * The real implementation needs the native helper (Phase 5) to enumerate top-level
 * OS windows. Until then we can at least report the bridge's own visible windows
 * so the client has something concrete to render while integration is validated.
 */
export async function listWindows(): Promise<LocalToolResult> {
  const windows = BrowserWindow.getAllWindows().map((w, idx) => ({
    id: `bridge-${idx}`,
    title: w.getTitle(),
    owner: 'Amaya Windows Bridge',
    bounds: w.getBounds(),
    visible: w.isVisible(),
    focused: w.isFocused()
  }));

  return {
    status: 'success',
    result: {
      windows,
      note:
        'Phase 4 stub: only bridge-owned windows are listed. System-wide window ' +
        'enumeration requires the native helper (Phase 5).'
    }
  };
}
