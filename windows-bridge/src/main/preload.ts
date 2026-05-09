import { contextBridge, ipcRenderer } from 'electron';

type Snapshot = unknown;
type AuditEntry = unknown;

const api = {
  snapshot: (): Promise<Snapshot> => ipcRenderer.invoke('amaya-bridge/snapshot'),
  onSnapshot: (cb: (snap: Snapshot) => void): void => {
    ipcRenderer.on('amaya-bridge/snapshot', (_evt, snap) => cb(snap));
  },
  audit: (limit?: number): Promise<AuditEntry[]> =>
    ipcRenderer.invoke('amaya-bridge/audit', limit),
  clearAuditLog: (): Promise<{ ok: boolean; error?: string }> =>
    ipcRenderer.invoke('amaya-bridge/clear-audit-log'),
  rawAuditLog: (): Promise<string> =>
    ipcRenderer.invoke('amaya-bridge/raw-audit-log'),
  trustedDevices: (): Promise<unknown[]> =>
    ipcRenderer.invoke('amaya-bridge/trusted-devices'),
  generatePairingPayload: (): Promise<unknown> =>
    ipcRenderer.invoke('amaya-bridge/generate-pairing'),
  revokeDevice: (deviceId: string): void => {
    ipcRenderer.send('amaya-bridge/revoke-device', deviceId);
  },
  toggleAgent: (enabled: boolean): void => {
    ipcRenderer.send('amaya-bridge/agent-control', enabled);
  },
  emergencyStop: (): void => {
    ipcRenderer.send('amaya-bridge/emergency-stop');
  },
  resume: (): void => {
    ipcRenderer.send('amaya-bridge/resume');
  },
  restartHelper: (): void => {
    ipcRenderer.send('amaya-bridge/restart-helper');
  }
};

contextBridge.exposeInMainWorld('bridge', api);
