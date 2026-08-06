#!/usr/bin/env node
// Minimal asset copy for the renderer status window. Keeps the build step
// dependency-free (no Vite/webpack required for Phase 4).

import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const from = join(root, 'src', 'renderer');
const to = join(root, 'dist', 'renderer');

if (!existsSync(from)) {
  console.log('[copy-assets] no renderer assets to copy, skipping');
  process.exit(0);
}

mkdirSync(to, { recursive: true });
cpSync(from, to, { recursive: true });
console.log(`[copy-assets] copied ${from} -> ${to}`);
