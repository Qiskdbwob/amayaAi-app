import { resolve, normalize, sep } from 'node:path';
import type { FolderPolicyConfig } from './security-policy';

export interface FilePolicyConfig extends FolderPolicyConfig {
  fileToolsEnabled: boolean;
  maxReadBytes: number;
  maxWriteBytes: number;
  allowDelete: boolean;
  deleteUsesTrash: boolean;
  requireApprovalForWrite: boolean;
  requireApprovalForDelete: boolean;
  requireApprovalForSensitiveRead: boolean;
}

export type FileDecision =
  | { decision: 'allow' }
  | { decision: 'require_approval'; reason: string }
  | { decision: 'deny'; reason: string };

/**
 * Real file policy engine. Validates paths against allowed/blocked folders,
 * detects sensitive patterns, and enforces approval requirements.
 */
export function evaluateFileAccess(
  config: FilePolicyConfig,
  path: string,
  operation: 'list' | 'read' | 'write' | 'edit' | 'delete'
): FileDecision {
  if (!config.fileToolsEnabled) {
    return { decision: 'deny', reason: 'File tools are disabled.' };
  }
  if (!path || !path.trim()) {
    return { decision: 'deny', reason: 'Path is empty.' };
  }

  const normalized = normalizePath(path);

  // Reject UNC paths
  if (normalized.startsWith('\\\\')) {
    return { decision: 'deny', reason: 'UNC paths are not allowed.' };
  }

  // Reject path traversal
  if (path.includes('..')) {
    return { decision: 'deny', reason: 'Path traversal (..) is not allowed.' };
  }

  // Check blocked folders
  for (const blocked of config.blockedFolders) {
    const norm = normalizePath(expand(blocked));
    if (norm && isInsideOrEqual(normalized, norm)) {
      return { decision: 'deny', reason: `Path is inside blocked folder '${blocked}'.` };
    }
  }

  // Check sensitive patterns
  const isSensitive = config.sensitivePathPatterns.some(
    (pat) => pat && normalized.toLowerCase().includes(pat.toLowerCase())
  );

  // Check allowed folders
  if (config.allowedFolders.length > 0) {
    const allowed = config.allowedFolders.some((folder) => {
      const norm = normalizePath(expand(folder));
      return norm && isInsideOrEqual(normalized, norm);
    });
    if (!allowed) {
      return { decision: 'deny', reason: 'Path is outside allowed folders.' };
    }
  }

  // Operation-specific rules
  switch (operation) {
    case 'list':
      return { decision: 'allow' };
    case 'read':
      if (isSensitive && config.requireApprovalForSensitiveRead) {
        return { decision: 'require_approval', reason: 'Reading a sensitive file requires approval.' };
      }
      return { decision: 'allow' };
    case 'write':
    case 'edit':
      if (config.requireApprovalForWrite) {
        return { decision: 'require_approval', reason: `File ${operation} requires approval.` };
      }
      return { decision: 'allow' };
    case 'delete':
      if (!config.allowDelete) {
        return { decision: 'deny', reason: 'File deletion is disabled by policy.' };
      }
      if (config.requireApprovalForDelete) {
        return { decision: 'require_approval', reason: 'File deletion requires approval.' };
      }
      return { decision: 'allow' };
  }
}

export function normalizePath(input: string): string {
  let out = expand(input).replace(/\//g, sep);
  try {
    out = resolve(out);
  } catch {
    // keep as-is
  }
  return normalize(out).toLowerCase();
}

function isInsideOrEqual(child: string, parent: string): boolean {
  const c = child.endsWith(sep) ? child : child + sep;
  const p = parent.endsWith(sep) ? parent : parent + sep;
  return c.startsWith(p) || child === parent;
}

function expand(input: string): string {
  let out = input ?? '';
  const replacements: Array<[RegExp, string]> = [
    [/%USERPROFILE%/gi, process.env.USERPROFILE ?? ''],
    [/%APPDATA%/gi, process.env.APPDATA ?? ''],
    [/%LOCALAPPDATA%/gi, process.env.LOCALAPPDATA ?? '']
  ];
  for (const [re, value] of replacements) {
    if (value) out = out.replace(re, value);
  }
  return out;
}

/** Build a FilePolicyConfig from the security policy's folderPolicy section. */
export function buildFilePolicyConfig(raw: Record<string, unknown>): FilePolicyConfig {
  return {
    fileToolsEnabled: raw['fileToolsEnabled'] === true,
    allowedFolders: asStringArray(raw['allowedFolders']),
    blockedFolders: asStringArray(raw['blockedFolders']),
    sensitivePathPatterns: asStringArray(raw['sensitivePathPatterns']),
    maxReadBytes: asNumber(raw['maxReadBytes'], 262144),
    maxWriteBytes: asNumber(raw['maxWriteBytes'], 262144),
    allowDelete: raw['allowDelete'] === true,
    deleteUsesTrash: raw['deleteUsesTrash'] !== false,
    requireApprovalForWrite: raw['requireApprovalForWrite'] !== false,
    requireApprovalForDelete: raw['requireApprovalForDelete'] !== false,
    requireApprovalForSensitiveRead: raw['requireApprovalForSensitiveRead'] !== false
  };
}

function asStringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x) => typeof x === 'string') as string[];
}

function asNumber(v: unknown, fallback: number): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  return fallback;
}
