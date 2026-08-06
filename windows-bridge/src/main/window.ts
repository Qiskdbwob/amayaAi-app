import { BrowserWindow, ipcMain } from 'electron';
import { join } from 'node:path';
import type { AppState } from './app-state';
import type { WindowsBridgeWebSocketServer } from '../transport/websocket-server';
import { readRecentAudit } from '../audit/audit-reader';

export interface StatusWindowDeps {
  state: AppState;
  server: WindowsBridgeWebSocketServer;
}

let win: BrowserWindow | null = null;

export function createStatusWindow(deps: StatusWindowDeps): BrowserWindow {
  if (win && !win.isDestroyed()) {
    win.show();
    win.focus();
    return win;
  }
  win = new BrowserWindow({
    width: 460,
    height: 620,
    show: true,
    resizable: true,
    title: 'Amaya Windows Bridge',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: join(__dirname, 'preload.js')
    }
  });
  win.removeMenu();
  win.loadFile(join(__dirname, '..', 'renderer', 'status.html'));
  win.on('closed', () => {
    win = null;
  });

  const push = () => {
    if (!win || win.isDestroyed()) return;
    win.webContents.send('amaya-bridge/snapshot', {
      server: deps.state.info,
      session: deps.state.snapshot(),
      helper: deps.state.helperStatus
    });
  };
  deps.state.on('snapshot', push);
  deps.state.on('helper', push);
  deps.state.on('server', push);
  win.webContents.on('did-finish-load', () => {
    // Small delay so the renderer's script has time to register the listener
    // before we push the first snapshot.
    setTimeout(push, 150);
  });

  return win;
}

export function registerWindowIpc(deps: StatusWindowDeps): void {
  ipcMain.handle('amaya-bridge/snapshot', () => ({
    server: deps.state.info,
    session: deps.state.snapshot(),
    helper: deps.state.helperStatus
  }));
  ipcMain.handle('amaya-bridge/audit', async (_evt, limit?: number) => {
    const entries = await readRecentAudit(Math.max(1, Math.min(100, limit ?? 20)));
    return entries;
  });
  ipcMain.handle('amaya-bridge/raw-audit-log', async () => {
    const { getAuditLogPath } = await import('./app-paths');
    const { readFileSync, existsSync } = await import('node:fs');
    const path = getAuditLogPath();
    if (!existsSync(path)) return '(audit log is empty or not yet created)';
    try {
      return readFileSync(path, 'utf-8');
    } catch (err) {
      return `(failed to read audit log: ${(err as Error).message})`;
    }
  });
  ipcMain.handle('amaya-bridge/clear-audit-log', async () => {
    const { getAuditLogPath } = await import('./app-paths');
    const { writeFileSync, existsSync } = await import('node:fs');
    const path = getAuditLogPath();
    try {
      writeFileSync(path, '', 'utf-8');
      return { ok: true };
    } catch (err) {
      return { ok: false, error: (err as Error).message };
    }
  });
  ipcMain.handle('amaya-bridge/trusted-devices', () => {
    return deps.state.trustedDevices?.getAll() ?? [];
  });
  ipcMain.handle('amaya-bridge/generate-pairing', () => {
    return deps.state.generatePairingPayload();
  });
  ipcMain.on('amaya-bridge/revoke-device', (_evt, deviceId: string) => {
    deps.state.trustedDevices?.revoke(deviceId);
  });
  ipcMain.on('amaya-bridge/agent-control', (_evt, enabled: boolean) => {
    deps.server.broadcastAgentControl(!!enabled, 'windows');
  });
  ipcMain.on('amaya-bridge/emergency-stop', () => {
    deps.server.emergencyStop();
  });
  ipcMain.on('amaya-bridge/resume', () => {
    deps.server.resume();
  });
  ipcMain.on('amaya-bridge/restart-helper', () => {
    deps.state.restartHelper();
  });
}
