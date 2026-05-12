import { existsSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { delimiter, join } from 'node:path';
import { logger } from '../../shared/logger';

const SCOPE = 'opencode.binary';

const WINDOWS_BIN_NAMES = ['opencode.cmd', 'opencode.exe', 'opencode.ps1', 'opencode'];
const UNIX_BIN_NAMES = ['opencode'];

export interface OpencodeBinary {
  path: string;
  version?: string | null;
}

/**
 * Resolve an opencode executable, trying (in order):
 *  1. Env var OPENCODE_BIN_PATH
 *  2. PATH search (opencode.cmd / opencode.exe / opencode)
 *  3. Well-known npm global install locations on Windows (%AppData%\npm, %APPDATA%\npm)
 *
 * Returns `null` if nothing usable is found. The caller is expected to surface a
 * friendly error to the user and instruct them to `npm i -g opencode-ai`.
 */
export function resolveOpencodeBinary(): OpencodeBinary | null {
  const candidates = collectCandidates();
  for (const candidate of candidates) {
    if (candidate && existsSync(candidate) && isFile(candidate)) {
      const version = probeVersion(candidate);
      logger.info(SCOPE, `resolved binary=${candidate}${version ? ` version=${version}` : ''}`);
      return { path: candidate, version };
    }
  }
  logger.warn(SCOPE, 'no opencode binary found on PATH or typical npm locations');
  return null;
}

function collectCandidates(): string[] {
  const envPath = process.env.OPENCODE_BIN_PATH?.trim();
  const results: string[] = [];
  if (envPath) results.push(envPath);

  const isWindows = process.platform === 'win32';
  const names = isWindows ? WINDOWS_BIN_NAMES : UNIX_BIN_NAMES;

  // PATH lookup
  const pathEntries = (process.env.PATH ?? '').split(delimiter);
  for (const entry of pathEntries) {
    if (!entry) continue;
    for (const name of names) {
      results.push(join(entry, name));
    }
  }

  // Well-known npm global locations on Windows
  if (isWindows) {
    const appdata = process.env.APPDATA;
    if (appdata) {
      for (const name of names) {
        results.push(join(appdata, 'npm', name));
      }
    }
    const programFiles = process.env['ProgramFiles'];
    if (programFiles) {
      for (const name of names) {
        results.push(join(programFiles, 'nodejs', name));
      }
    }
  } else {
    // Common Unix-style install roots
    for (const name of names) {
      results.push(join('/usr/local/bin', name));
      results.push(join('/opt/homebrew/bin', name));
    }
  }

  return dedupe(results);
}

function dedupe(values: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const value of values) {
    const normalized = value.trim();
    if (!normalized) continue;
    if (seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(normalized);
  }
  return out;
}

function isFile(path: string): boolean {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

function probeVersion(binary: string): string | null {
  try {
    const result = spawnSync(binary, ['--version'], {
      encoding: 'utf-8',
      timeout: 4000,
      windowsHide: true
    });
    if (result.status !== 0) return null;
    const trimmed = (result.stdout || '').trim();
    // opencode --version prints something like "opencode 1.14.29" or just "1.14.29".
    const match = trimmed.match(/(\d+\.\d+\.\d+[\w.\-+]*)/);
    return match ? match[1] : trimmed || null;
  } catch {
    return null;
  }
}
