# Amaya Windows Bridge — Phase 4 MVP + Phase 5 Native Helper + Phase 6 Safety & Control

Electron TypeScript app that runs on a Windows computer and acts as the remote tool
executor for the Amaya Android AI agent. The Android side is the planner / chat UI
/ approval UI; this bridge only executes tools and reports results back over a
local WebSocket using the shared protocol declared in
`app/src/main/java/com/amaya/intelligence/domain/bridge/` (Phase 1).

## What Phase 4 shipped

- WebSocket server on `0.0.0.0:17878` (configurable via env).
- Tolerant `BridgeEnvelope` decoder/encoder — never crashes on bad input.
- Single-session manager with seq tracking.
- Handshake envelopes: `device.paired` + `session.created`.
- Tool dispatch with a tiny risk engine and audit log (`logs/audit.log`, JSONL).
- Real `screen.capture` via Electron `desktopCapturer`.
- Tray menu with status, Agent Control toggle, and Emergency Stop.
- HIGH-risk tools (`shell.run`, `file.*`, `clipboard.read`) declared but disabled.

## What Phase 5 adds

- C# .NET 8 native helper (`native-helper/AmayaBridgeHelper.exe`) spawned over
  stdin/stdout JSON-RPC.
- Real `window.list` (EnumWindows + process name lookup).
- New `window.focus` tool (MEDIUM risk, Agent-Control gated).
- Real `mouse.click`, `keyboard.type`, and `keyboard.hotkey`.
- Helper status in the tray and status window (pid + last error + restart).
- Automatic helper restart with 1.5 s backoff when the process exits.
- Audit redaction for `keyboard.type` (length only, never the text).

## Repo layout

```
windows-bridge/
├─ package.json            # npm scripts, Electron + ws deps.
├─ tsconfig.json           # TS build config — emits to dist/.
├─ scripts/copy-assets.mjs # Copies src/renderer into dist/renderer post-build.
├─ native-helper/          # .NET 8 console app (Phase 5).
└─ src/
   ├─ main/                # Electron main process.
   ├─ protocol/            # TS mirror of the Kotlin bridge protocol.
   ├─ transport/           # WebSocket server, session manager, pairing.
   ├─ native/              # JSON-RPC client for the C# helper.
   ├─ tools/               # Tool registry + screen/window/input executors.
   ├─ permissions/         # Risk engine + approval policy placeholder.
   ├─ audit/               # JSONL audit logger.
   ├─ shared/              # ids, time, logger.
   └─ renderer/status.html # Status window shown from the tray.
```

## Getting started

Prerequisites:

- Node.js ≥ 18.17.
- **.NET 10 SDK** (only needed to build the native helper). `dotnet --list-sdks`
  must include a `10.x` entry.

```bash
cd windows-bridge
npm install
npm run typecheck
npm run build
npm run build:helper
npm start
```

`npm run build:helper` shells out to `dotnet publish` and drops the helper at
`native-helper/bin/Release/net10.0-windows/win-x64/publish/AmayaBridgeHelper.exe`.
Electron resolves the executable in this order:

1. `$env:AMAYA_BRIDGE_HELPER_PATH` (override)
2. `dist/native/AmayaBridgeHelper.exe` (copy it there for packaging)
3. the publish folder above

If the helper is missing, the bridge still starts — window/input tools fall back
to the Phase 4 stub behavior and the tray shows `Helper: stopped`.

Environment variables:

| Name | Default | Purpose |
| --- | --- | --- |
| `AMAYA_BRIDGE_HOST` | `0.0.0.0` | WebSocket bind host |
| `AMAYA_BRIDGE_PORT` | `17878` | WebSocket bind port |
| `AMAYA_BRIDGE_TOKEN` | *(unset)* | Optional pairing token |
| `AMAYA_BRIDGE_HELPER_PATH` | *(auto)* | Override path to the helper `.exe` |
| `AMAYA_BRIDGE_POLICY_PATH` | `config/security-policy.json` | Override security policy file path |

Audit events are appended to `windows-bridge/logs/audit.log` as JSON lines. Each
record is redacted: tokens/passwords/secrets never land on disk, base64 images
are replaced with their byte length, and `keyboard.type` only logs the length.

## Protocol compatibility

All wire names match the Kotlin enums in Phase 1:

- `session.created`, `session.closed`
- `device.paired`, `device.disconnected`
- `tool.call`, `tool.result`, `tool.error`
- `approval.request`, `approval.accepted`, `approval.rejected`
- `agent.status`, `agent.step`, `agent.paused`, `agent.resumed`,
  `agent.cancelled`
- `screen.frame`, `screen.capture_result`
- `audit.event`, `error`

Outgoing envelopes from the bridge use `deviceId = "windows_bridge"` and stamp the
target device id under `metadata.target`.

## Risk engine (MVP)

```
LOW     → allowed when session connected
MEDIUM  → allowed when Agent Control is enabled
HIGH    → APPROVAL_REQUIRED (rejected until Phase 6 approval UI)
BLOCKED → always rejected (COMMAND_BLOCKED)
```

Emergency stop forces every risk level to DENY until the user resumes.

## Tool catalog

Enabled:

- `screen.capture` (LOW) — Electron `desktopCapturer`
- `window.list` (LOW) — native helper `EnumWindows`
- `window.focus` (MEDIUM, Agent-Control gated) — native helper
- `mouse.click` (MEDIUM, Agent-Control gated) — native helper `SendInput`
- `keyboard.type` (MEDIUM, Agent-Control gated) — native helper `SendInput`
- `keyboard.hotkey` (MEDIUM, Agent-Control gated) — native helper `SendInput`; accepts `keys` arrays and combo strings such as `ctrl+shift+esc`
- `clipboard.write` (MEDIUM, Agent-Control gated) — Electron clipboard write
- `file.list` / `file.read` (MEDIUM, Agent-Control gated) — read-only filesystem tools
- `shell.cancel` (MEDIUM, Agent-Control gated) — cancels bridge-managed shell processes

Disabled by policy until explicitly enabled:
`clipboard.read`, `file.write`, `file.delete`, `shell.run`, `browser.*`, `ui.*`.

## Next phases

- **Phase 7** — Installer packaging + auto-start + signed releases.
