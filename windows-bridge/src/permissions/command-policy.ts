import type { CommandPolicyConfig } from './security-policy';

export type CommandPolicyDecision =
  | { decision: 'allow' }
  | { decision: 'require_approval'; reason: string }
  | { decision: 'deny'; reason: string };

/**
 * Placeholder command policy — shell tools remain disabled in Phase 6. The
 * vocabulary is introduced early so later phases can bolt on shell execution.
 */
export function isCommandAllowed(
  config: CommandPolicyConfig,
  command: string
): CommandPolicyDecision {
  if (!config.shellEnabled) {
    return { decision: 'deny', reason: 'shell tools are disabled in this phase' };
  }
  const normalized = (command ?? '').trim();
  if (!normalized) {
    return { decision: 'deny', reason: 'empty command' };
  }
  const lower = normalized.toLowerCase();

  for (const blocked of config.blockedCommands) {
    const pat = blocked.toLowerCase();
    if (pat && lower.includes(pat)) {
      return { decision: 'deny', reason: `command matches blocked pattern '${blocked}'` };
    }
  }

  if (config.allowedCommands.length > 0) {
    const allowed = config.allowedCommands.some((a) => lower.startsWith(a.toLowerCase()));
    if (!allowed) {
      return {
        decision: config.requireApprovalForAll ? 'require_approval' : 'deny',
        reason: 'command is not on the allowlist'
      };
    }
  }

  return config.requireApprovalForAll
    ? { decision: 'require_approval', reason: 'command requires user approval' }
    : { decision: 'allow' };
}

export function isCommandBlocked(
  config: CommandPolicyConfig,
  command: string
): boolean {
  const result = isCommandAllowed(config, command);
  return result.decision === 'deny';
}

export function explainCommandDecision(
  config: CommandPolicyConfig,
  command: string
): string {
  const r = isCommandAllowed(config, command);
  return r.decision === 'allow'
    ? 'allowed by command policy'
    : r.reason;
}
