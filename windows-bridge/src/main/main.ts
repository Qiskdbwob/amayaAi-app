import { app } from 'electron';
import { AppState } from './app-state';
import { loadPairingConfig, detectLanIps } from './pairing';
import {
  getAuditLogPath,
  getNativeHelperPath,
  getSecurityPolicyPath,
  getTrustedDevicesPath
} from './app-paths';
import { createTray } from './tray';
import { createStatusWindow, registerWindowIpc } from './window';
import { AuditLog } from '../audit/audit-log';
import { NativeHelperClient } from '../native/native-helper-client';
import { buildRegistry, installRegistry } from '../tools/tool-registry';
import { loadSecurityPolicy } from '../permissions/security-policy';
import { TrustedDeviceStore } from '../permissions/trusted-device-store';
import { PairingTokenStore } from '../permissions/pairing-token-store';
import { WindowsBridgeWebSocketServer } from '../transport/websocket-server';
import { logger } from '../shared/logger';

// Acquire the single-instance lock BEFORE anything else. If a previous instance
// is already running, quit immediately and surface the existing one. Without
// this, double-clicking the tray icon, relaunching from Start Menu, or the
// classic "UAC prompt relaunch" path would spawn a second bridge (each with
// its own helper, port, and tray icon) — which is exactly what happened before:
// one medium-integrity bridge from autostart, one high-integrity bridge from
// the UAC relaunch, both fighting for the same port.
if (!app.requestSingleInstanceLock()) {
  logger.info('main', 'another Amaya Windows Bridge instance is already running; exiting');
  app.quit();
  process.exit(0);
}

app.on('second-instance', () => {
  // Surface the existing window when the user tries to launch a second copy.
  try {
    const wins = require('electron').BrowserWindow.getAllWindows() as Array<{
      isMinimized(): boolean;
      restore(): void;
      show(): void;
      focus(): void;
      isDestroyed(): boolean;
    }>;
    const active = wins.find((w) => !w.isDestroyed());
    if (active) {
      if (active.isMinimized()) active.restore();
      active.show();
      active.focus();
    }
  } catch (err) {
    logger.warn('main', 'second-instance handler failed', (err as Error).message);
  }
});

async function bootstrap(): Promise<void> {
  const config = loadPairingConfig();
  const policy = loadSecurityPolicy(getSecurityPolicyPath());
  const state = new AppState();
  const trustedDevices = new TrustedDeviceStore(getTrustedDevicesPath());
  const pairingTokens = new PairingTokenStore();
  state.attachPairing(trustedDevices, pairingTokens);

  const helper = new NativeHelperClient(getNativeHelperPath());
  helper.on('status', (snap) => state.setHelperStatus(snap));
  state.attachHelper(helper);
  helper.start();
  installRegistry(buildRegistry({ helper, policy }));

  const server = new WindowsBridgeWebSocketServer(
    {
      host: config.host,
      port: config.port,
      authToken: config.token
    },
    state.sessions,
    new AuditLog(getAuditLogPath()),
    policy,
    helper,
    trustedDevices,
    pairingTokens
  );
  server.start();
  state.attachServer(server, {
    host: config.host,
    port: config.port,
    tokenConfigured: !!config.token,
    requireToken: policy.auth.requireToken,
    queryTokenFallback: policy.auth.allowQueryTokenFallback,
    lanIps: detectLanIps()
  });

  createTray({ state, server });
  registerWindowIpc({ state, server });
  createStatusWindow({ state, server });

  logger.info(
    'main',
    `Amaya Windows Bridge ready on ${config.host}:${config.port} token=${config.token ? 'set' : 'none'} packaged=${app.isPackaged}`
  );

  app.on('before-quit', async () => {
    await helper.dispose();
    await server.stop();
  });
}

app.whenReady().then(bootstrap).catch((err) => {
  logger.error('main', 'bootstrap failed', (err as Error).stack ?? err);
  app.exit(1);
});

app.on('window-all-closed', () => {
  // Keep running in the tray — the bridge is a background service.
});

app.on('before-quit', () => {
  logger.info('main', 'shutting down bridge');
});
