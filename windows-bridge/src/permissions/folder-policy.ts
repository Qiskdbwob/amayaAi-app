import { resolve } from 'node:path';
import type { FolderPolicyConfig } from './security-policy';

export type FolderPolicyDecision =
  | { decision: 'allow' }
  | { decision: 'deny'; reason: string };

/**
 * Placeholder folder policy — file tools remain disabled in Phase 6. The policy
 * vocabulary is introduced early so later phases can bolt on file tools without
 * churning the shape.
 */
export function isPathAllowed(
  config: FolderPolicyConfig,
  path: string
): FolderPolicyDecision {
  if (!path) return { decision: 'deny', reason: 'empty path' };
  const normalized = expand(path).toLowerCase();

  for (const blocked of config.blockedFolders) {
    const norm = expand(blocked).toLowerCase();
    if (norm && normalized.startsWith(norm)) {
      return { decision: 'deny', reason: `path is inside blocked folder '${blocked}'` };
    }
  }
  for (const sensitive of config.sensitivePathPatterns) {
    if (sensitive && normalized.includes(sensitive.toLowerCase())) {
      return {
        decision: 'deny',
        reason: `path matches sensitive pattern '${sensitive}'`
      };
    }
  }
  if (config.allowedFolders.length === 0) {
    return { decision: 'allow' };
  }
  for (const allowed of config.allowedFolders) {
    const norm = expand(allowed).toLowerCase();
    if (norm && normalized.startsWith(norm)) {
      return { decision: 'allow' };
    }
  }
  return { decision: 'deny', reason: 'path is not under any allowed folder' };
}

export function isSensitivePath(
  config: FolderPolicyConfig,
  path: string
): boolean {
  if (!path) return false;
  const normalized = expand(path).toLowerCase();
  return config.sensitivePathPatterns.some(
    (pat) => pat && normalized.includes(pat.toLowerCase())
  );
}

export function explainPathDecision(
  config: FolderPolicyConfig,
  path: string
): string {
  const r = isPathAllowed(config, path);
  return r.decision === 'allow' ? 'allowed by folder policy' : r.reason;
}

function expand(input: string): string {
  let out = input ?? '';
  // Expand a handful of env-style placeholders the config file uses.
  const replacements: Array<[RegExp, string]> = [
    [/%USERPROFILE%/gi, process.env.USERPROFILE ?? ''],
    [/%APPDATA%/gi, process.env.APPDATA ?? ''],
    [/%LOCALAPPDATA%/gi, process.env.LOCALAPPDATA ?? '']
  ];
  for (const [re, value] of replacements) {
    if (value) out = out.replace(re, value);
  }
  try {
    return resolve(out);
  } catch {
    return out;
  }
}
