#!/usr/bin/env node
// Copies the published native helper into dist/native/ so electron-builder can
// bundle it as an extraResource and dev mode can find it at a stable path.

import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const publishDir = resolve(
  root,
  'native-helper',
  'bin',
  'Release',
  'net10.0-windows',
  'win-x64',
  'publish'
);
const destDir = resolve(root, 'dist', 'native');

if (!existsSync(publishDir)) {
  console.error(`[copy-helper] publish dir not found: ${publishDir}`);
  console.error('[copy-helper] run "npm run build:helper" first');
  process.exit(1);
}

mkdirSync(destDir, { recursive: true });
cpSync(publishDir, destDir, { recursive: true });
console.log(`[copy-helper] copied ${publishDir} -> ${destDir}`);
