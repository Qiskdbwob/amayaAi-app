import type { AppAllowlistConfig } from './security-policy';

export interface ActiveWindowContext {
  processName: string;
  title: string;
}

export type AppPolicyDecision =
  | { decision: 'allow' }
  | { decision: 'deny'; reason: string };

/**
 * Decide whether an input-style tool (mouse/keyboard/window.focus) may run against
 * the currently focused window.
 *
 * Order of checks:
 *  1. Blocked process name / title pattern match → deny.
 *  2. Allowlist disabled → allow.
 *  3. Allowlist enabled → must match process name or title pattern.
 */
export function evaluateAppPolicy(
  config: AppAllowlistConfig,
  ctx: ActiveWindowContext
): AppPolicyDecision {
  const processName = (ctx.processName || '').toLowerCase();
  const title = (ctx.title || '').toLowerCase();

  for (const blocked of config.blockedProcessNames) {
    if (blocked && processName.includes(blocked.toLowerCase())) {
      return { decision: 'deny', reason: `process '${ctx.processName}' is blocked by policy` };
    }
  }
  for (const blocked of config.blockedWindowTitlePatterns) {
    if (blocked && title.includes(blocked.toLowerCase())) {
      return {
        decision: 'deny',
        reason: `window title matches blocked pattern '${blocked}'`
      };
    }
  }

  if (!config.appAllowlistEnabled) {
    return { decision: 'allow' };
  }

  for (const allowed of config.allowedProcessNames) {
    if (allowed && processName.includes(allowed.toLowerCase())) {
      return { decision: 'allow' };
    }
  }
  for (const allowed of config.allowedWindowTitlePatterns) {
    if (allowed && title.includes(allowed.toLowerCase())) {
      return { decision: 'allow' };
    }
  }
  return {
    decision: 'deny',
    reason: `focused app '${ctx.processName}' is not on the allowlist`
  };
}
