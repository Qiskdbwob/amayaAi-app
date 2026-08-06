import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { logger } from '../shared/logger';
import { nowMs } from '../shared/time';

export interface TrustedDevice {
  deviceId: string;
  deviceName: string;
  firstPairedAt: number;
  lastSeenAt: number;
  trusted: boolean;
}

const DEFAULT_PATH = resolve(process.cwd(), 'config', 'trusted-devices.json');

export class TrustedDeviceStore {
  private devices: TrustedDevice[] = [];

  constructor(private readonly path: string = DEFAULT_PATH) {
    this.load();
  }

  getAll(): TrustedDevice[] {
    return [...this.devices];
  }

  find(deviceId: string): TrustedDevice | undefined {
    return this.devices.find((d) => d.deviceId === deviceId);
  }

  isTrusted(deviceId: string): boolean {
    const d = this.find(deviceId);
    return d?.trusted === true;
  }

  /** Add or update a device as trusted. */
  trust(deviceId: string, deviceName?: string): TrustedDevice {
    const existing = this.find(deviceId);
    if (existing) {
      existing.trusted = true;
      existing.lastSeenAt = nowMs();
      if (deviceName) existing.deviceName = deviceName;
      this.persist();
      return existing;
    }
    const device: TrustedDevice = {
      deviceId,
      deviceName: deviceName ?? deviceId,
      firstPairedAt: nowMs(),
      lastSeenAt: nowMs(),
      trusted: true
    };
    this.devices.push(device);
    this.persist();
    return device;
  }

  touch(deviceId: string): void {
    const d = this.find(deviceId);
    if (d) {
      d.lastSeenAt = nowMs();
      this.persist();
    }
  }

  revoke(deviceId: string): boolean {
    const idx = this.devices.findIndex((d) => d.deviceId === deviceId);
    if (idx < 0) return false;
    this.devices.splice(idx, 1);
    this.persist();
    logger.info('trusted-devices', `revoked device=${deviceId}`);
    return true;
  }

  private load(): void {
    try {
      if (!existsSync(this.path)) {
        this.devices = [];
        return;
      }
      const raw = readFileSync(this.path, 'utf-8');
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        this.devices = parsed.filter(
          (d) => d && typeof d.deviceId === 'string' && d.deviceId.length > 0
        );
      }
    } catch (err) {
      logger.warn('trusted-devices', 'failed to load', (err as Error).message);
      this.devices = [];
    }
  }

  private persist(): void {
    try {
      const dir = dirname(this.path);
      if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
      writeFileSync(this.path, JSON.stringify(this.devices, null, 2), 'utf-8');
    } catch (err) {
      logger.warn('trusted-devices', 'failed to persist', (err as Error).message);
    }
  }
}
