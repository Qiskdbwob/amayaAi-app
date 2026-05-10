import { EventEmitter } from 'node:events';
import { SessionManager, type SessionSnapshot } from '../transport/session-manager';
import type { WindowsBridgeWebSocketServer } from '../transport/websocket-server';
import type { TrustedDeviceStore } from '../permissions/trusted-device-store';
import type { PairingTokenStore } from '../permissions/pairing-token-store';
import type {
  HelperStatus,
  NativeHelperClient
} from '../native/native-helper-client';

export interface ServerInfo {
  host: string;
  port: number;
  tokenConfigured: boolean;
  requireToken: boolean;
  queryTokenFallback: boolean;
  lanIps: string[];
}

const HELPER_BLANK: HelperStatus = {
  running: false,
  pid: null,
  elevated: null,
  integrity: 'unknown',
  lastError: null,
  startedAt: null
};

export class AppState extends EventEmitter {
  readonly sessions = new SessionManager();
  private _server: WindowsBridgeWebSocketServer | null = null;
  private _info: ServerInfo | null = null;
  private _helper: NativeHelperClient | null = null;
  private _helperStatus: HelperStatus = HELPER_BLANK;
  private _trustedDevices: TrustedDeviceStore | null = null;
  private _pairingTokens: PairingTokenStore | null = null;

  get server(): WindowsBridgeWebSocketServer | null {
    return this._server;
  }

  get info(): ServerInfo | null {
    return this._info;
  }

  get helper(): NativeHelperClient | null {
    return this._helper;
  }

  get helperStatus(): HelperStatus {
    return { ...this._helperStatus };
  }

  get trustedDevices(): TrustedDeviceStore | null {
    return this._trustedDevices;
  }

  get pairingTokens(): PairingTokenStore | null {
    return this._pairingTokens;
  }

  attachServer(server: WindowsBridgeWebSocketServer, info: ServerInfo): void {
    this._server = server;
    this._info = info;
    server.on('snapshot', (snap: SessionSnapshot) => this.emit('snapshot', snap));
    this.emit('server', info);
  }

  attachHelper(helper: NativeHelperClient): void {
    this._helper = helper;
    this._helperStatus = helper.snapshot;
    this.emit('helper', this._helperStatus);
  }

  attachPairing(trustedDevices: TrustedDeviceStore, pairingTokens: PairingTokenStore): void {
    this._trustedDevices = trustedDevices;
    this._pairingTokens = pairingTokens;
  }

  setHelperStatus(status: HelperStatus): void {
    this._helperStatus = { ...status };
    this.emit('helper', this._helperStatus);
    this.emit('snapshot', this.snapshot());
  }

  restartHelper(): void {
    this._helper?.start();
  }

  snapshot(): SessionSnapshot {
    return this.sessions.snapshot;
  }

  /** Generate a fresh pairing payload for display in the status window. */
  generatePairingPayload(): Record<string, unknown> | null {
    const info = this._info;
    const tokens = this._pairingTokens;
    if (!info || !tokens) return null;
    const pt = tokens.generate();
    const host = info.lanIps.length > 0 ? info.lanIps[0] : info.host;
    return {
      type: 'amaya.windows_bridge.pairing',
      version: 1,
      host,
      port: info.port,
      token: pt.token,
      bridgeId: 'windows_bridge',
      computerName: require('node:os').hostname(),
      expiresAt: pt.expiresAt
    };
  }
}
