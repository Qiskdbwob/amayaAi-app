import { copyFileSync, existsSync, mkdirSync, renameSync } from 'node:fs';
import { basename, join } from 'node:path';
import { createHash } from 'node:crypto';
import { getUserDataDir } from '../main/app-paths';
import { nowMs } from '../shared/time';
import { newId } from '../shared/ids';

function dateFolder(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function shortHash(path: string): string {
  return createHash('sha256').update(path).digest('hex').slice(0, 8);
}

export function createBackup(originalPath: string): string | null {
  if (!existsSync(originalPath)) return null;
  const dir = join(getUserDataDir(), 'backups', dateFolder());
  mkdirSync(dir, { recursive: true });
  const name = `${shortHash(originalPath)}-${basename(originalPath)}.bak`;
  const dest = join(dir, name);
  copyFileSync(originalPath, dest);
  return dest;
}

export function moveToTrash(originalPath: string): string | null {
  if (!existsSync(originalPath)) return null;
  const dir = join(getUserDataDir(), 'trash', dateFolder());
  mkdirSync(dir, { recursive: true });
  const name = `${shortHash(originalPath)}-${basename(originalPath)}`;
  const dest = join(dir, name);
  renameSync(originalPath, dest);
  return dest;
}
