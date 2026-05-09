import { readdirSync, readFileSync, writeFileSync, statSync, existsSync, mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { ToolInvocationError, type LocalToolResult } from './tool-result';
import { createBackup, moveToTrash } from './file-backup';
import type { FilePolicyConfig } from '../permissions/file-policy';
import { evaluateFileAccess } from '../permissions/file-policy';

// ── file.list ────────────────────────────────────────────────────────────────

export function fileList(policy: FilePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const path = asString(args['path']);
    if (!path) throw invalid('path is required');
    const maxDepth = clamp(asInt(args['maxDepth']) ?? 1, 0, 5);
    const limit = clamp(asInt(args['limit']) ?? 200, 1, 1000);
    const pattern = asString(args['pattern']);
    const includeHidden = args['includeHidden'] === true;

    const decision = evaluateFileAccess(policy, path, 'list');
    if (decision.decision === 'deny') throw denied(decision.reason);
    if (decision.decision === 'require_approval') throw approval(decision.reason);

    const resolved = resolve(path);
    if (!existsSync(resolved)) throw notFound(`Directory not found: ${path}`);
    const stat = statSync(resolved);
    if (!stat.isDirectory()) throw invalid('Path is not a directory');

    const entries: Array<Record<string, unknown>> = [];
    walk(resolved, 0, maxDepth, limit, pattern, includeHidden, entries);

    return {
      status: 'success',
      result: {
        path: resolved,
        entries,
        truncated: entries.length >= limit
      }
    };
  };
}

function walk(
  dir: string,
  depth: number,
  maxDepth: number,
  limit: number,
  pattern: string | null,
  includeHidden: boolean,
  out: Array<Record<string, unknown>>
): void {
  if (out.length >= limit) return;
  let items: string[];
  try {
    items = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of items) {
    if (out.length >= limit) return;
    if (!includeHidden && name.startsWith('.')) continue;
    if (pattern && !matchGlob(name, pattern)) {
      // Still recurse into dirs even if name doesn't match pattern
      const full = join(dir, name);
      try {
        if (depth < maxDepth && statSync(full).isDirectory()) {
          walk(full, depth + 1, maxDepth, limit, pattern, includeHidden, out);
        }
      } catch { /* skip */ }
      continue;
    }
    const full = join(dir, name);
    try {
      const st = statSync(full);
      out.push({
        name,
        path: full,
        type: st.isDirectory() ? 'directory' : 'file',
        size: st.size,
        modifiedAt: st.mtimeMs
      });
      if (depth < maxDepth && st.isDirectory()) {
        walk(full, depth + 1, maxDepth, limit, pattern, includeHidden, out);
      }
    } catch { /* skip inaccessible */ }
  }
}

// ── file.read ────────────────────────────────────────────────────────────────

export function fileRead(policy: FilePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const path = asString(args['path']);
    if (!path) throw invalid('path is required');
    const maxBytes = clamp(asInt(args['maxBytes']) ?? policy.maxReadBytes, 1, policy.maxReadBytes);
    const startLine = asInt(args['startLine']) ?? 1;
    const maxLines = clamp(asInt(args['maxLines']) ?? 500, 1, 5000);

    const decision = evaluateFileAccess(policy, path, 'read');
    if (decision.decision === 'deny') throw denied(decision.reason);
    if (decision.decision === 'require_approval') throw approval(decision.reason);

    const resolved = resolve(path);
    if (!existsSync(resolved)) throw notFound(`File not found: ${path}`);
    const stat = statSync(resolved);
    if (!stat.isFile()) throw invalid('Path is not a file');

    const buffer = readFileSync(resolved);
    if (buffer.length > maxBytes) {
      // Return truncated
      const text = buffer.subarray(0, maxBytes).toString('utf-8');
      const lines = text.split(/\r?\n/);
      return {
        status: 'success',
        result: {
          path: resolved,
          content: lines.slice(startLine - 1, startLine - 1 + maxLines).join('\n'),
          startLine,
          endLine: Math.min(startLine - 1 + maxLines, lines.length),
          truncated: true,
          sizeBytes: stat.size,
          encoding: 'utf-8'
        }
      };
    }

    const text = buffer.toString('utf-8');
    const lines = text.split(/\r?\n/);
    const sliced = lines.slice(startLine - 1, startLine - 1 + maxLines);
    return {
      status: 'success',
      result: {
        path: resolved,
        content: sliced.join('\n'),
        startLine,
        endLine: Math.min(startLine - 1 + maxLines, lines.length),
        truncated: sliced.length < lines.length,
        sizeBytes: stat.size,
        encoding: 'utf-8'
      }
    };
  };
}

// ── file.write ───────────────────────────────────────────────────────────────

export function fileWrite(policy: FilePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const path = asString(args['path']);
    if (!path) throw invalid('path is required');
    const content = asString(args['content']);
    if (content === null) throw invalid('content is required');
    const mode = asString(args['mode']) ?? 'overwrite';
    const createParents = args['createParents'] === true;

    if (Buffer.byteLength(content, 'utf-8') > policy.maxWriteBytes) {
      throw invalid(`Content exceeds max write size (${policy.maxWriteBytes} bytes).`);
    }

    const decision = evaluateFileAccess(policy, path, 'write');
    if (decision.decision === 'deny') throw denied(decision.reason);
    if (decision.decision === 'require_approval') throw approval(decision.reason);

    const resolved = resolve(path);
    if (createParents) {
      const dir = require('node:path').dirname(resolved);
      mkdirSync(dir, { recursive: true });
    }

    let backupPath: string | null = null;
    if (existsSync(resolved) && mode !== 'create_new') {
      backupPath = createBackup(resolved);
    }
    if (mode === 'create_new' && existsSync(resolved)) {
      throw invalid('File already exists and mode is create_new.');
    }

    if (mode === 'append') {
      const existing = existsSync(resolved) ? readFileSync(resolved, 'utf-8') : '';
      writeFileSync(resolved, existing + content, 'utf-8');
    } else {
      writeFileSync(resolved, content, 'utf-8');
    }

    return {
      status: 'success',
      result: {
        path: resolved,
        written: true,
        bytesWritten: Buffer.byteLength(content, 'utf-8'),
        backupPath,
        mode
      }
    };
  };
}

// ── file.edit ────────────────────────────────────────────────────────────────

export function fileEdit(policy: FilePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const path = asString(args['path']);
    if (!path) throw invalid('path is required');
    const oldText = asString(args['oldText']);
    const newText = asString(args['newText']);
    if (!oldText) throw invalid('oldText is required');
    if (newText === null) throw invalid('newText is required');
    const replaceAll = args['replaceAll'] === true;

    const decision = evaluateFileAccess(policy, path, 'edit');
    if (decision.decision === 'deny') throw denied(decision.reason);
    if (decision.decision === 'require_approval') throw approval(decision.reason);

    const resolved = resolve(path);
    if (!existsSync(resolved)) throw notFound(`File not found: ${path}`);
    const content = readFileSync(resolved, 'utf-8');

    const occurrences = countOccurrences(content, oldText);
    if (occurrences === 0) {
      throw new ToolInvocationError('EXECUTION_FAILED', 'oldText not found in file.', {}, true);
    }
    if (occurrences > 1 && !replaceAll) {
      throw new ToolInvocationError(
        'EXECUTION_FAILED',
        `Ambiguous edit: oldText found ${occurrences} times. Use replaceAll=true or make oldText more specific.`,
        { occurrences },
        true
      );
    }

    const backupPath = createBackup(resolved);
    const updated = replaceAll
      ? content.split(oldText).join(newText)
      : content.replace(oldText, newText);
    writeFileSync(resolved, updated, 'utf-8');

    const replacements = replaceAll ? occurrences : 1;
    return {
      status: 'success',
      result: {
        path: resolved,
        edited: true,
        replacements,
        backupPath,
        diffPreview: `Replaced ${replacements} occurrence(s) of "${oldText.slice(0, 40)}${oldText.length > 40 ? '...' : ''}" with "${newText.slice(0, 40)}${newText.length > 40 ? '...' : ''}"`
      }
    };
  };
}

// ── file.delete ──────────────────────────────────────────────────────────────

export function fileDelete(policy: FilePolicyConfig) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const path = asString(args['path']);
    if (!path) throw invalid('path is required');
    const permanent = args['permanent'] === true;

    if (permanent) {
      throw denied('Permanent deletion is not allowed. Files are moved to trash.');
    }

    const decision = evaluateFileAccess(policy, path, 'delete');
    if (decision.decision === 'deny') throw denied(decision.reason);
    if (decision.decision === 'require_approval') throw approval(decision.reason);

    const resolved = resolve(path);
    if (!existsSync(resolved)) throw notFound(`File not found: ${path}`);

    const trashPath = moveToTrash(resolved);
    return {
      status: 'success',
      result: {
        path: resolved,
        deleted: true,
        trashPath,
        permanent: false
      }
    };
  };
}

// ── helpers ──────────────────────────────────────────────────────────────────

function countOccurrences(text: string, search: string): number {
  let count = 0;
  let pos = 0;
  while (true) {
    const idx = text.indexOf(search, pos);
    if (idx < 0) break;
    count++;
    pos = idx + search.length;
  }
  return count;
}

function matchGlob(name: string, pattern: string): boolean {
  const re = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*')
    .replace(/\?/g, '.');
  return new RegExp(`^${re}$`, 'i').test(name);
}

function asString(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}
function asInt(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string') { const n = Number(v); if (Number.isFinite(n)) return Math.trunc(n); }
  return null;
}
function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}
function invalid(msg: string): ToolInvocationError {
  return new ToolInvocationError('INVALID_ARGS', msg);
}
function denied(msg: string): ToolInvocationError {
  return new ToolInvocationError('PATH_NOT_ALLOWED', msg);
}
function notFound(msg: string): ToolInvocationError {
  return new ToolInvocationError('EXECUTION_FAILED', msg, {}, true);
}
function approval(msg: string): ToolInvocationError {
  return new ToolInvocationError('APPROVAL_REQUIRED', msg);
}
