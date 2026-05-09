import { BridgeRiskLevel } from '../protocol/bridge-risk';
import type { NativeHelperClient } from '../native/native-helper-client';
import type { SecurityPolicy } from '../permissions/security-policy';
import { buildFilePolicyConfig, type FilePolicyConfig } from '../permissions/file-policy';
import { buildShellPolicyConfig, type ShellPolicyConfig } from '../tools/shell-tools';
import { ProcessManager } from './process-manager';
import { captureScreenFactory } from './screen-tools';
import { listWindows as listWindowsStub } from './window-tools';
import {
  mouseClickStub,
  keyboardTypeStub,
  keyboardHotkeyStub
} from './input-tools-stub';
import { clipboardWrite } from './clipboard-tools';
import {
  focusWindow,
  keyboardHotkey,
  keyboardType,
  listWindowsReal,
  mouseClick
} from './native-tools';
import { fileList, fileRead, fileWrite, fileEdit, fileDelete } from './file-tools';
import { shellRun, shellCancel } from './shell-tools';
import type { LocalToolResult } from './tool-result';

export interface ToolSpec {
  name: string;
  description: string;
  risk: BridgeRiskLevel;
  requiresApproval: boolean;
  enabled: boolean;
  execute: (args: Record<string, unknown>) => Promise<LocalToolResult>;
}

const disabled = async (): Promise<LocalToolResult> => ({
  status: 'success',
  result: {}
});

export interface BuildRegistryOptions {
  /**
   * Optional native helper. When present, window/input tools delegate to it.
   * When absent, the bridge falls back to the Phase 4 behavior (limited window
   * list + structured stub errors for input tools).
   */
  helper?: NativeHelperClient | null;
  /** Security policy used to seed per-tool defaults. */
  policy?: SecurityPolicy;
}

export function buildRegistry(options: BuildRegistryOptions = {}): Record<string, ToolSpec> {
  const helper = options.helper ?? null;
  const hasHelper = helper !== null;
  const screenPolicy = options.policy?.screenCapture ?? {
    defaultFormat: 'png' as const,
    defaultQuality: 85,
    defaultMaxWidth: null
  };
  const capture = captureScreenFactory(screenPolicy);

  // File/shell policy
  const rawFolder = (options.policy?.folderPolicy ?? {}) as Record<string, unknown>;
  const filePolicy = buildFilePolicyConfig(rawFolder);
  const rawCommand = (options.policy?.commandPolicy ?? {}) as Record<string, unknown>;
  const shellPolicy = buildShellPolicyConfig(rawCommand);
  const processManager = new ProcessManager();
  const readOnlyFilePolicy = { ...filePolicy, fileToolsEnabled: true };

  return {
    'screen.capture': {
      name: 'screen.capture',
      description:
        'Capture a screenshot. Supports format (png|jpeg), quality (1-100), maxWidth, displayIndex.',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: true,
      execute: capture
    },
    'window.list': {
      name: 'window.list',
      description: hasHelper
        ? 'Enumerate visible top-level windows via the native helper.'
        : 'List windows (stub: bridge-owned windows only).',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? listWindowsReal(helper!) : listWindowsStub
    },
    'window.focus': {
      name: 'window.focus',
      description: 'Bring a window to the foreground by handle id.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? focusWindow(helper!) : disabled
    },
    'mouse.click': {
      name: 'mouse.click',
      description: hasHelper
        ? 'Click at the given screen coordinate via the native helper.'
        : 'Mouse click (stub — native helper not available).',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? mouseClick(helper!) : mouseClickStub
    },
    'keyboard.type': {
      name: 'keyboard.type',
      description: hasHelper
        ? 'Type text via the native helper.'
        : 'Keyboard type (stub — native helper not available).',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? keyboardType(helper!) : keyboardTypeStub
    },
    'keyboard.hotkey': {
      name: 'keyboard.hotkey',
      description: hasHelper
        ? 'Send a hotkey combination via the native helper.'
        : 'Keyboard hotkey (stub — native helper not available).',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? keyboardHotkey(helper!) : keyboardHotkeyStub
    },
    'clipboard.write': {
      name: 'clipboard.write',
      description: 'Write text to the clipboard.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: clipboardWrite()
    },
    'clipboard.read': {
      name: 'clipboard.read',
      description: 'Read the clipboard. Disabled in Phase 8.',
      risk: BridgeRiskLevel.HIGH,
      requiresApproval: true,
      enabled: false,
      execute: disabled
    },
    // ── File tools ─────────────────────────────────────────────────────────
    'file.list': {
      name: 'file.list',
      description: 'List files in an allowed Windows folder.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: fileList(readOnlyFilePolicy)
    },
    'file.read': {
      name: 'file.read',
      description: 'Read a text file from an allowed Windows folder.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: fileRead(readOnlyFilePolicy)
    },
    'file.write': {
      name: 'file.write',
      description: 'Write a file in an allowed Windows folder. Requires approval and creates backup.',
      risk: BridgeRiskLevel.HIGH,
      requiresApproval: true,
      enabled: filePolicy.fileToolsEnabled,
      execute: fileWrite(filePolicy)
    },
    'file.edit': {
      name: 'file.edit',
      description: 'Safely edit a file by exact text replacement. Requires approval and creates backup.',
      risk: BridgeRiskLevel.HIGH,
      requiresApproval: true,
      enabled: filePolicy.fileToolsEnabled,
      execute: fileEdit(filePolicy)
    },
    'file.delete': {
      name: 'file.delete',
      description: 'Move a file to Amaya Bridge trash. Requires approval.',
      risk: BridgeRiskLevel.HIGH,
      requiresApproval: true,
      enabled: filePolicy.fileToolsEnabled && filePolicy.allowDelete,
      execute: fileDelete(filePolicy)
    },
    // ── Shell tools ────────────────────────────────────────────────────────
    'shell.run': {
      name: 'shell.run',
      description: 'Run an approved shell command in an allowed working directory. Requires approval.',
      risk: BridgeRiskLevel.HIGH,
      requiresApproval: true,
      enabled: shellPolicy.shellEnabled,
      execute: shellRun(shellPolicy, processManager)
    },
    'shell.cancel': {
      name: 'shell.cancel',
      description: 'Cancel a shell process started by Amaya Windows Bridge.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: shellCancel(processManager)
    }
  };
}

let activeRegistry: Record<string, ToolSpec> = buildRegistry();

/** Swap the active registry used by the server. Call once at startup. */
export function installRegistry(registry: Record<string, ToolSpec>): void {
  activeRegistry = registry;
}

export function findTool(name: string): ToolSpec | undefined {
  return activeRegistry[name];
}

export function enabledTools(): ToolSpec[] {
  return Object.values(activeRegistry).filter((spec) => spec.enabled);
}

/** Legacy re-export for tests/scripts that grab the map directly. */
export function registrySnapshot(): Record<string, ToolSpec> {
  return activeRegistry;
}
