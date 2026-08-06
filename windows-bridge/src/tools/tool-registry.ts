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
  mouseMoveStub,
  mouseScrollStub,
  mouseDragStub,
  keyboardTypeStub,
  keyboardHotkeyStub
} from './input-tools-stub';
import { clipboardWrite } from './clipboard-tools';
import {
  focusWindow,
  closeWindow,
  openApp,
  uiTree,
  uiFindText,
  uiClickElement,
  uiHitTest,
  keyboardHotkey,
  keyboardType,
  keyboardHold,
  listWindowsReal,
  mouseClick,
  mouseMove,
  mouseScroll,
  mouseDrag,
  mousePress,
  mouseRelease,
  mouseHover,
  inputWait,
  diagnostics as diagnosticsTool
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
  const legacyUiEnabled = options.policy?.features?.legacyUiToolsEnabled === true;
  const capture = captureScreenFactory(screenPolicy, helper);

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
        'Capture the screen in physical pixels. mode=display (default) captures a monitor; mode=window captures a specific windowId even when it is partially covered; mode=region crops a rectangle. Output includes accessibility metadata (displayBounds, windows[], recommendedWindowId, coordinateGuide, cursor) so the agent can translate between image and mouse coordinates. Supports format (png|jpeg), quality (1-100), maxWidth, displayIndex, includeWindows, includeCursor, windowId, region.',
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
    'window.close': {
      name: 'window.close',
      description: 'Request that a top-level window closes by handle id from window.list.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? closeWindow(helper!) : disabled
    },
    'app.open': {
      name: 'app.open',
      description: 'Open a Windows application by app name or safe executable alias, then verify with window.list/screen.capture.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? openApp(helper!) : disabled
    },
    'ui.tree': {
      name: 'ui.tree',
      description:
        'LEGACY: Dump the child HWND tree of a Win32 window. Works reliably only for classic Win32 apps (Notepad, File Explorer, installers, some WinForms/WPF). Modern apps (Chromium, Electron, UWP, WinUI, DirectX) expose almost nothing here — prefer screen.capture + mouse.click with focusWindowId + ui.hit_test instead. Disabled by default; set policy.legacyUiToolsEnabled to opt in.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper && legacyUiEnabled,
      execute: hasHelper ? uiTree(helper!) : disabled
    },
    'ui.find_text': {
      name: 'ui.find_text',
      description:
        'LEGACY: Find child HWNDs whose title/class contains the query. Same Win32-only caveats as ui.tree. Prefer screen.capture + visual matching + mouse.click. Disabled by default.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper && legacyUiEnabled,
      execute: hasHelper ? uiFindText(helper!) : disabled
    },
    'ui.click_element': {
      name: 'ui.click_element',
      description: 'LEGACY: Click a UI element by elementId returned from ui.tree or ui.find_text. Disabled by default.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper && legacyUiEnabled,
      execute: hasHelper ? uiClickElement(helper!) : disabled
    },
    'ui.hit_test': {
      name: 'ui.hit_test',
      description:
        'Return the top-level window at a screen coordinate (physical px). Use after computing a click target from screen.capture to verify the right window is under (x, y) before committing a click.',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? uiHitTest(helper!) : disabled
    },
    'mouse.click': {
      name: 'mouse.click',
      description: hasHelper
        ? 'Click at screen coordinate (x, y) in physical pixels. Options: button (left|right|middle), clicks (1-3 for single/double/triple), modifiers ("ctrl+shift" / "ctrl,alt"), focusWindowId (activate window first), relativeToWindowId + clientX + clientY (client-relative coords converted to screen by the helper). Returns cursor, foregroundWindow, hitWindow for post-action verification.'
        : 'Mouse click (stub — native helper not available).',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? mouseClick(helper!) : mouseClickStub
    },
    'mouse.move': {
      name: 'mouse.move',
      description: hasHelper
        ? 'Move the cursor to (x, y) without clicking. Supports durationMs (0-2000) and focusWindowId. Use to reveal hover menus or position before a drag.'
        : 'Mouse move (stub — native helper not available).',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? mouseMove(helper!) : mouseMoveStub
    },
    'mouse.scroll': {
      name: 'mouse.scroll',
      description: hasHelper
        ? 'Scroll at coordinate (x, y). direction: up|down|left|right. amount: 1-50 ticks (default 3). Optional focusWindowId. Uses SendInput plus a PostMessage(WM_MOUSEWHEEL) fallback so DirectManipulation / WebView2 surfaces also receive the event.'
        : 'Mouse scroll (stub — native helper not available).',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? mouseScroll(helper!) : mouseScrollStub
    },
    'mouse.drag': {
      name: 'mouse.drag',
      description: hasHelper
        ? 'Press-hold at (startX, startY), move to (endX, endY), release. Optional waypoints[], durationMs, focusWindowId.'
        : 'Mouse drag (stub — native helper not available).',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: true,
      execute: hasHelper ? mouseDrag(helper!) : mouseDragStub
    },
    'mouse.press': {
      name: 'mouse.press',
      description:
        'Press a mouse button at (x, y) without releasing. Pair with mouse.release for spreadsheet-style drag select, tight modifier-locked drags, or games that need a held button.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? mousePress(helper!) : disabled
    },
    'mouse.release': {
      name: 'mouse.release',
      description: 'Release the mouse button previously pressed via mouse.press.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? mouseRelease(helper!) : disabled
    },
    'mouse.hover': {
      name: 'mouse.hover',
      description:
        'Move the cursor to (x, y) and hold it there for holdMs (default 400, max 5000) so tooltips / hover menus appear before the next action.',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? mouseHover(helper!) : disabled
    },
    'input.wait': {
      name: 'input.wait',
      description: 'Pause execution for durationMs milliseconds (100–10000, default 1000). Use to wait for animations, loading spinners, or rate-limited UIs.',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: true,
      execute: inputWait()
    },
    'keyboard.type': {
      name: 'keyboard.type',
      description: hasHelper
        ? 'Type text reliably. Long/multiline text is pasted via clipboard internally; short text can use key events. Supports mode auto|paste|keys.'
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
    'keyboard.hold': {
      name: 'keyboard.hold',
      description:
        'Press a single key, wait durationMs (10-10000), release. Equivalent to Claude computer_use hold_key. Useful for games and selection-with-modifier.',
      risk: BridgeRiskLevel.MEDIUM,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? keyboardHold(helper!) : disabled
    },
    'diagnostics': {
      name: 'diagnostics',
      description:
        'One-shot snapshot of helper capabilities: DPI context, system DPI, elevation status, OS version, virtual screen bounds, Windows.Graphics.Capture and PrintWindow availability. Call once per session so the agent can refuse tasks that require elevation or a secure desktop.',
      risk: BridgeRiskLevel.LOW,
      requiresApproval: false,
      enabled: hasHelper,
      execute: hasHelper ? diagnosticsTool(helper!) : disabled
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
