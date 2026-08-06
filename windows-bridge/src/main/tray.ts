import { app, Menu, shell, Tray, nativeImage } from 'electron';
import type { AppState } from './app-state';
import type { WindowsBridgeWebSocketServer } from '../transport/websocket-server';
import { getConfigDir, getLogsDir } from './app-paths';
import { createStatusWindow } from './window';

export interface TrayDeps {
  state: AppState;
  server: WindowsBridgeWebSocketServer;
}

let tray: Tray | null = null;

export function createTray(deps: TrayDeps): Tray {
  if (tray) return tray;
  // Phase 4: no packaged icon yet. Use an empty native image — Electron falls
  // back to a default tray placeholder which is fine for an MVP.
  const icon = nativeImage.createEmpty();
  tray = new Tray(icon);
  tray.setToolTip('Amaya Windows Bridge');
  refreshMenu(deps);
  deps.state.on('snapshot', () => refreshMenu(deps));
  deps.state.on('server', () => refreshMenu(deps));
  deps.state.on('helper', () => refreshMenu(deps));
  tray.on('click', () => createStatusWindow(deps));
  return tray;
}

function refreshMenu(deps: TrayDeps): void {
  if (!tray) return;
  const snap = deps.state.snapshot();
  const info = deps.state.info;
  const helper = deps.state.helperStatus;
  const items: Electron.MenuItemConstructorOptions[] = [
    {
      label: info
        ? `Listening ${info.host}:${info.port}`
        : 'Bridge not started',
      enabled: false
    },
    {
      label: snap.sessionId
        ? `Device: ${snap.deviceId ?? '-'} (${snap.status})`
        : 'No device connected',
      enabled: false
    },
    {
      label: helper.running
        ? `Helper: running (pid ${helper.pid ?? '?'})`
        : helper.lastError
          ? `Helper: stopped — ${helper.lastError}`
          : 'Helper: stopped',
      enabled: false
    },
    { type: 'separator' },
    {
      label: 'Open status window',
      click: () => createStatusWindow(deps)
    },
    {
      label: snap.agentControlEnabled
        ? 'Disable Agent Control'
        : 'Enable Agent Control',
      enabled: !!snap.sessionId,
      click: () => deps.server.broadcastAgentControl(!snap.agentControlEnabled)
    },
    {
      label: snap.emergencyStopped ? 'Resume session' : 'Emergency stop',
      enabled: !!snap.sessionId,
      click: () => {
        if (snap.emergencyStopped) deps.server.resume();
        else deps.server.emergencyStop();
      }
    },
    {
      label: 'Restart native helper',
      click: () => deps.state.restartHelper()
    },
    {
      label: 'Open logs folder',
      click: () => shell.openPath(getLogsDir())
    },
    {
      label: 'Open config folder',
      click: () => shell.openPath(getConfigDir())
    },
    { type: 'separator' },
    { label: 'Quit', click: () => app.quit() }
  ];
  tray.setContextMenu(Menu.buildFromTemplate(items));
}
