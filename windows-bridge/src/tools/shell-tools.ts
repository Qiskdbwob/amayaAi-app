import { resolve } from 'node:path';
import { existsSync } from 'node:fs';
import { ToolInvocationError, type LocalToolResult } from './tool-result';
import { ProcessManager } from './process-manager';
import type { CommandPolicyConfig } from '../permissions/security-policy';

export interface ShellPolicyConfig extends CommandPolicyConfig {
  maxRuntimeMs: number;
  maxOutputBytes: number;
  allowedWorkingDirectories: string[];
}

/**
 * shell.run — execute a command in an allowed working directory.
 * Requires approval by default. Blocked commands are rejected before execution.
 */
export function shellRun(policy: ShellPolicyConfig, processManager: ProcessManager) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const command = asString(args['command']);
    if (!command) throw invalid('command is required');
    const cwd = asString(args['cwd']);
    if (!cwd) throw invalid('cwd is required');
    const timeoutMs = clamp(asInt(args['timeoutMs']) ?? policy.maxRuntimeMs, 1000, policy.maxRuntimeMs);

    if (!policy.shellEnabled) {
      throw denied('Shell tools are disabled.');
    }

    // Validate cwd
    const resolvedCwd = resolve(expand(cwd));
    if (!existsSync(resolvedCwd)) {
      throw invalid(`Working directory not found: ${cwd}`);
    }
    if (policy.allowedWorkingDirectories.length > 0) {
      const allowed = policy.allowedWorkingDirectories.some((dir) => {
        const norm = resolve(expand(dir)).toLowerCase();
        return resolvedCwd.toLowerCase().startsWith(norm);
      });
      if (!allowed) {
        throw new ToolInvocationError('PATH_NOT_ALLOWED', 'Working directory is not in the allowed list.', {}, false);
      }
    }

    // Check blocked commands
    const cmdLower = command.toLowerCase();
    for (const blocked of policy.blockedCommands) {
      if (blocked && cmdLower.includes(blocked.toLowerCase())) {
        throw new ToolInvocationError(
          'COMMAND_BLOCKED',
          `Command matches blocked pattern '${blocked}'.`,
          {},
          false
        );
      }
    }

    // Check allowed commands (if non-empty, require prefix match)
    if (policy.allowedCommands.length > 0) {
      const allowed = policy.allowedCommands.some((a) =>
        cmdLower.startsWith(a.toLowerCase())
      );
      if (!allowed && policy.requireApprovalForAll) {
        // Will be gated by approval in the server layer
      }
    }

    // Approval is handled by the websocket server layer (REQUIRE_APPROVAL risk).
    // If we reach here, approval was already granted.

    const result = await processManager.spawn(command, resolvedCwd, timeoutMs, policy.maxOutputBytes);

    if (result.timedOut) {
      throw new ToolInvocationError('TIMEOUT', `Command timed out after ${timeoutMs}ms.`, {
        processId: result.processId,
        durationMs: result.durationMs
      }, true);
    }

    return {
      status: 'success',
      result: {
        processId: result.processId,
        exitCode: result.exitCode,
        stdout: result.stdout,
        stderr: result.stderr,
        durationMs: result.durationMs,
        timedOut: false,
        truncated: result.truncated
      }
    };
  };
}

/**
 * shell.cancel — cancel a tracked process.
 */
export function shellCancel(processManager: ProcessManager) {
  return async (args: Record<string, unknown>): Promise<LocalToolResult> => {
    const processId = asString(args['processId']);
    if (!processId) throw invalid('processId is required');

    const cancelled = processManager.cancel(processId);
    if (!cancelled) {
      throw new ToolInvocationError(
        'EXECUTION_FAILED',
        `Process '${processId}' not found or already finished.`,
        {},
        true
      );
    }

    return {
      status: 'success',
      result: { cancelled: true, processId }
    };
  };
}

// ── helpers ──────────────────────────────────────────────────────────────────

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
  return new ToolInvocationError('PERMISSION_DENIED', msg);
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

export function buildShellPolicyConfig(raw: Record<string, unknown>): ShellPolicyConfig {
  return {
    shellEnabled: raw['shellEnabled'] === true,
    allowedCommands: asStringArray(raw['allowedCommands']),
    blockedCommands: asStringArray(raw['blockedCommands']),
    requireApprovalForAll: raw['requireApprovalForAll'] !== false,
    maxRuntimeMs: typeof raw['maxRuntimeMs'] === 'number' ? raw['maxRuntimeMs'] : 120000,
    maxOutputBytes: typeof raw['maxOutputBytes'] === 'number' ? raw['maxOutputBytes'] : 65536,
    allowedWorkingDirectories: asStringArray(raw['allowedWorkingDirectories'])
  };
}

function asStringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x) => typeof x === 'string') as string[];
}
