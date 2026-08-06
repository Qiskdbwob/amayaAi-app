# Windows Bridge Instructions

## Scope
- This file applies to `windows-bridge/` and all of its children.
- It covers the Electron app, TypeScript transport/runtime, permissions, audit logging, packaging scripts, and the native C# helper.

## Bridge Rules
- Keep Electron main-process logic, WebSocket transport, session handling, permissions, audit, and tool routing in `src/`.
- Keep generated outputs (`dist/`, `release/`, `native-helper/obj/`, logs) untouched unless you are intentionally rebuilding or packaging.
- Keep the native helper isolated in `native-helper/` and rebuild/copy it when helper-side behavior changes.
- Keep the Windows bridge protocol aligned with the shared Android bridge contract in `app/src/main/java/com/amaya/intelligence/domain/bridge/`.
- Do not move Android-specific logic into this workspace.

## Editing Guidance
- Prefer small changes in `src/main/`, `src/native/`, `src/tools/`, `src/transport/`, `src/permissions/`, `src/audit/`, `scripts/`, and `native-helper/`.
- When touching helper input/window/integrity behavior, check the nearest recent commits and verify the helper build path before finishing.
- Before wrapping up, crosscheck `git status`, the touched-area diff, and the recent bridge commits.
- If files, folders, or features change here, update this AGENTS file in the same change.

## Build
- `npm run verify`
- `npm run build:helper`
- `npm run copy:helper`
- `npm run build`
- `npm run package`

## File Tree
```text
windows-bridge/
├─ AGENTS.md
├─ package.json
├─ scripts/
├─ src/
├─ native-helper/ (source only; `obj/` is ignored)
├─ config/
├─ dist/
├─ release/
└─ logs/
```

## File Functions
- `AGENTS.md`: bridge-wide rules and scope routing.
- `package.json`: Electron scripts, dependencies, and packaging commands.
- `electron-builder.yml`: packaging configuration.
- `scripts/copy-assets.mjs`: copies renderer assets into `dist/`.
- `scripts/copy-helper.mjs`: stages the native helper binary for packaging.
- `scripts/smoke-helper.mjs`: helper/runtime smoke checks.
- `src/main/`: Electron entrypoint, app state, tray, and window wiring.
- `src/transport/`: WebSocket server, pairing, and session management.
- `src/native/`: JSON-RPC client and helper process integration.
- `src/tools/`: screen, window, input, clipboard, shell, and file tool execution.
- `src/agents/`: CLI coding-agent runtimes (opencode, claude-code, codex) behind a shared `AgentProvider` contract.
- `src/permissions/`: approval policy, security policy, and trusted device logic.
- `src/audit/`: JSONL audit event logging and readers.
- `native-helper/`: C# Win32 helper executable and related services.
- `native-helper/AGENTS.md`: helper-specific rules for Win32 interop and JSON-RPC.
- `config/`: security and trusted-device configuration.

## Key Source Code
- `src/main/main.ts`: Electron bootstrap and lifecycle.
- `src/main/tray.ts`: tray menu, status, and control toggles.
- `src/transport/websocket-server.ts`: bridge WebSocket server.
- `src/transport/session-manager.ts`: session lifecycle and message routing.
- `src/native/native-helper-client.ts`: stdin/stdout JSON-RPC client for the helper.
- `src/tools/tool-registry.ts`: bridge tool registration and dispatch.
- `src/tools/native-tools.ts`: helper-backed tool execution.
- `src/permissions/approval-policy.ts`: risk gating and approval policy.
- `src/audit/audit-log.ts`: audit persistence.
- `native-helper/Program.cs`: helper entrypoint.
- `native-helper/Services/InputService.cs`: input execution and validation.
- `native-helper/Services/IntegrityService.cs`: UIPI / integrity-level checks.
- `native-helper/Services/WindowService.cs`: window enumeration and focus helpers.
- `native-helper/Services/DiagnosticsService.cs`: helper diagnostics and health.
- `native-helper/Windows/WindowInfo.cs`: Win32 window metadata model.
