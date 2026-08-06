import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { logger } from '../shared/logger';

export interface AppAllowlistConfig {
  appAllowlistEnabled: boolean;
  allowedProcessNames: string[];
  allowedWindowTitlePatterns: string[];
  blockedProcessNames: string[];
  blockedWindowTitlePatterns: string[];
}

export interface FolderPolicyConfig {
  allowedFolders: string[];
  blockedFolders: string[];
  sensitivePathPatterns: string[];
}

export interface CommandPolicyConfig {
  shellEnabled: boolean;
  allowedCommands: string[];
  blockedCommands: string[];
  requireApprovalForAll: boolean;
}

export interface AuthPolicyConfig {
  requireToken: boolean;
  allowQueryTokenFallback: boolean;
}

export interface ScreenCapturePolicyConfig {
  defaultFormat: 'png' | 'jpeg';
  defaultQuality: number;
  defaultMaxWidth: number | null;
}

export interface ApprovalPolicyConfig {
  enabled: boolean;
  timeoutMs: number;
}

export interface FeatureFlagsConfig {
  /**
   * When true, register the legacy ui.tree / ui.find_text / ui.click_element
   * tools. They work only for classic Win32 apps (Notepad, File Explorer,
   * installers) because the current implementation enumerates HWND children.
   * Modern apps (Chromium/Electron/UWP/WinUI/DirectX) expose nothing useful
   * through them — prefer screen.capture + mouse.click + ui.hit_test instead.
   */
  legacyUiToolsEnabled: boolean;
}

export interface SecurityPolicy {
  appPolicy: AppAllowlistConfig;
  folderPolicy: FolderPolicyConfig;
  commandPolicy: CommandPolicyConfig;
  auth: AuthPolicyConfig;
  screenCapture: ScreenCapturePolicyConfig;
  approval: ApprovalPolicyConfig;
  features: FeatureFlagsConfig;
}

const DEFAULTS: SecurityPolicy = {
  appPolicy: {
    appAllowlistEnabled: false,
    allowedProcessNames: ['notepad', 'Code', 'chrome', 'msedge'],
    allowedWindowTitlePatterns: [],
    blockedProcessNames: [],
    blockedWindowTitlePatterns: ['password', 'credential', 'bank', 'wallet']
  },
  folderPolicy: {
    allowedFolders: [],
    blockedFolders: [
      '%USERPROFILE%\\.ssh',
      '%USERPROFILE%\\AppData',
      '%USERPROFILE%\\Documents\\Passwords'
    ],
    sensitivePathPatterns: ['id_rsa', '.env', 'credentials', 'token', 'password']
  },
  commandPolicy: {
    shellEnabled: false,
    allowedCommands: [],
    blockedCommands: [
      'rm',
      'del',
      'format',
      'shutdown',
      'reg',
      'net user',
      'powershell -enc',
      'curl',
      'wget'
    ],
    requireApprovalForAll: true
  },
  auth: {
    requireToken: false,
    allowQueryTokenFallback: true
  },
  screenCapture: {
    defaultFormat: 'jpeg',
    defaultQuality: 72,
    defaultMaxWidth: 1280
  },
  approval: {
    enabled: true,
    timeoutMs: 30_000
  },
  features: {
    legacyUiToolsEnabled: false
  }
};

/** Deep-merge helper that only accepts known keys from [partial]. */
function mergePolicy(partial: unknown): SecurityPolicy {
  if (!partial || typeof partial !== 'object') return { ...DEFAULTS };
  const p = partial as Record<string, unknown>;
  const out: SecurityPolicy = {
    appPolicy: { ...DEFAULTS.appPolicy },
    folderPolicy: { ...DEFAULTS.folderPolicy },
    commandPolicy: { ...DEFAULTS.commandPolicy },
    auth: { ...DEFAULTS.auth },
    screenCapture: { ...DEFAULTS.screenCapture },
    approval: { ...DEFAULTS.approval },
    features: { ...DEFAULTS.features }
  };
  applyObject(out.appPolicy, p['appPolicy']);
  applyObject(out.folderPolicy, p['folderPolicy']);
  applyObject(out.commandPolicy, p['commandPolicy']);
  applyObject(out.auth, p['auth']);
  applyObject(out.screenCapture, p['screenCapture']);
  applyObject(out.approval, p['approval']);
  applyObject(out.features, p['features']);
  // Accept legacy flat shape where app allowlist keys live at the top level.
  applyObject(out.appPolicy, p);
  return out;
}

function applyObject<T extends object>(target: T, source: unknown): void {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return;
  const src = source as Record<string, unknown>;
  const t = target as unknown as Record<string, unknown>;
  for (const [key, value] of Object.entries(src)) {
    if (!(key in t)) continue;
    const existing = t[key];
    if (Array.isArray(existing) && Array.isArray(value)) {
      t[key] = value.filter((v) => typeof v === 'string');
    } else if (typeof existing === typeof value && value !== null) {
      t[key] = value;
    }
  }
}

export function loadSecurityPolicy(
  path: string = resolveDefaultPath()
): SecurityPolicy {
  try {
    if (!existsSync(path)) {
      logger.info('policy', `no policy file at ${path}; using defaults`);
      return { ...DEFAULTS };
    }
    const raw = readFileSync(path, 'utf-8');
    const parsed = JSON.parse(raw) as unknown;
    const merged = mergePolicy(parsed);
    logger.info('policy', `loaded ${path}`);
    return merged;
  } catch (err) {
    logger.warn('policy', 'failed to load policy file, using defaults', (err as Error).message);
    return { ...DEFAULTS };
  }
}

export function defaultPolicy(): SecurityPolicy {
  return {
    appPolicy: { ...DEFAULTS.appPolicy },
    folderPolicy: { ...DEFAULTS.folderPolicy },
    commandPolicy: { ...DEFAULTS.commandPolicy },
    auth: { ...DEFAULTS.auth },
    screenCapture: { ...DEFAULTS.screenCapture },
    approval: { ...DEFAULTS.approval },
    features: { ...DEFAULTS.features }
  };
}

function resolveDefaultPath(): string {
  const override = process.env.AMAYA_BRIDGE_POLICY_PATH?.trim();
  if (override && existsSync(override)) return override;
  return resolve(process.cwd(), 'config', 'security-policy.json');
}
