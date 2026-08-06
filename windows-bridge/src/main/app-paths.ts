import { app } from 'electron';
import { existsSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * Centralized path resolver for config, logs, and data files.
 *
 * - **Packaged mode**: uses `app.getPath('userData')` → `%APPDATA%/Amaya Windows Bridge/`
 * - **Dev mode**: uses the project working directory (`process.cwd()`)
 *
 * All directories are created on first access if they don't exist.
 */

function isPackaged(): boolean {
  return app.isPackaged;
}

export function getUserDataDir(): string {
  if (isPackaged()) {
    return app.getPath('userData');
  }
  return process.cwd();
}

export function getConfigDir(): string {
  const dir = join(getUserDataDir(), 'config');
  ensureDir(dir);
  return dir;
}

export function getLogsDir(): string {
  const dir = join(getUserDataDir(), 'logs');
  ensureDir(dir);
  return dir;
}

export function getAuditLogPath(): string {
  return join(getLogsDir(), 'audit.log');
}

export function getAppLogPath(): string {
  return join(getLogsDir(), 'app.log');
}

export function getSecurityPolicyPath(): string {
  return join(getConfigDir(), 'security-policy.json');
}

export function getTrustedDevicesPath(): string {
  return join(getConfigDir(), 'trusted-devices.json');
}

export function getNativeHelperPath(): string {
  const override = process.env.AMAYA_BRIDGE_HELPER_PATH?.trim();
  if (override && existsSync(override)) return override;

  if (isPackaged()) {
    // In packaged mode, extraResources places native/ under process.resourcesPath
    const packaged = join(process.resourcesPath, 'native', 'AmayaBridgeHelper.exe');
    if (existsSync(packaged)) return packaged;
  }

  // Dev mode candidates
  const candidates = [
    resolve(process.cwd(), 'dist', 'native', 'AmayaBridgeHelper.exe'),
    resolve(
      process.cwd(),
      'native-helper',
      'bin',
      'Release',
      'net10.0-windows',
      'win-x64',
      'publish',
      'AmayaBridgeHelper.exe'
    )
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  return candidates[0]!;
}

function ensureDir(dir: string): void {
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}
