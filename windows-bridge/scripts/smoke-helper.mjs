#!/usr/bin/env node
// Quick smoke test for the native helper. Sends a handful of requests and
// prints the replies. Useful after `npm run build:helper`.
//
// Usage: node scripts/smoke-helper.mjs

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const exe = resolve(
  here,
  '..',
  'native-helper',
  'bin',
  'Release',
  'net10.0-windows',
  'win-x64',
  'publish',
  'AmayaBridgeHelper.exe'
);

if (!existsSync(exe)) {
  console.error(`[smoke] helper not found at ${exe}`);
  process.exit(2);
}

const child = spawn(exe, [], { stdio: ['pipe', 'pipe', 'pipe'] });
const pending = new Map();
let buffer = '';

child.stdout.setEncoding('utf-8');
child.stdout.on('data', (chunk) => {
  buffer += chunk;
  while (true) {
    const nl = buffer.indexOf('\n');
    if (nl < 0) break;
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line.length) continue;
    try {
      const msg = JSON.parse(line);
      const entry = pending.get(msg.id);
      if (entry) {
        pending.delete(msg.id);
        entry.resolve(msg);
      } else {
        console.log('[smoke][orphan]', line);
      }
    } catch {
      console.log('[smoke][bad-line]', line);
    }
  }
});
child.stderr.setEncoding('utf-8');
child.stderr.on('data', (chunk) => process.stderr.write(`[helper-stderr] ${chunk}`));
child.on('exit', (code) => process.exit(code ?? 0));

function call(method, params = {}) {
  const id = `smoke-${method}`;
  const promise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`timeout: ${method}`));
    }, 5000);
    pending.set(id, {
      resolve: (msg) => {
        clearTimeout(timer);
        resolve(msg);
      }
    });
  });
  child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
  return promise;
}

(async () => {
  try {
    console.log('[smoke] health.ping', JSON.stringify(await call('health.ping'), null, 2));
    const active = await call('window.active');
    if (active.ok && active.result?.window) {
      const w = active.result.window;
      console.log(`[smoke] window.active pid=${w.processId} proc=${w.processName} title=${JSON.stringify(w.title)}`);
    } else {
      console.log('[smoke] window.active', JSON.stringify(active, null, 2));
    }
    const list = await call('window.list');
    const count = Array.isArray(list.result?.windows) ? list.result.windows.length : -1;
    console.log(`[smoke] window.list count=${count}`);
  } catch (err) {
    console.error('[smoke] error:', err.message);
    process.exitCode = 1;
  } finally {
    child.kill();
  }
})();
