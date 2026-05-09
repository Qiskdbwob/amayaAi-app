# Phase 4 — Build Electron Windows Bridge MVP

## Context

Phase 1 selesai:
- Android punya shared bridge protocol di `domain/bridge/`.

Phase 2 selesai:
- Android punya `WindowsBridgeSessionClient`.
- Bisa connect, send/receive `BridgeEnvelope`, parse event, reconnect, queue outbound, dan handle seq minimal.

Phase 3A selesai:
- Android punya bridge tool adapter:
  - `WindowsBridgeToolExecutor`
  - `WindowsBridgeToolRegistry`
  - `WindowsBridgeToolProvider`
  - mapper tool call/result/error/approval.

Phase 3B selesai:
- Bridge tools sudah masuk ke agent path secara controlled.
- `AiRepository.buildToolDefinitions()` append bridge tools.
- `McpToolExecutor.execute()` route bridge tool ke `WindowsBridgeToolProvider`.
- `WindowsBridgeController` own client + executor.
- Bridge dormant by default.
- Agent Control flag default false.
- HIGH-risk tools masih disabled.

Sekarang lanjut ke Phase 4.

---

## Product Vision Reminder

Amaya Android adalah AI planner/controller.

Windows Bridge adalah remote executor yang berjalan di komputer Windows.

Target flow:

```txt
Android AI Agent
→ tool.call via BridgeEnvelope
→ Windows Bridge Electron app
→ validate session
→ check permission/risk
→ execute MVP tool
→ return tool.result/tool.error
→ Android agent continues

Windows Bridge bukan agent utama.

Windows Bridge hanya:

remote executor
permission gate
audit logger
session indicator
screen/input tool host
Phase 4 Goal

Buat folder/project baru untuk Windows Bridge MVP berbasis Electron.

Phase 4 belum perlu native helper penuh.

Tujuan Phase 4:

Membuat Electron app Windows Bridge.
App bisa berjalan sebagai tray/desktop app.
App membuka WebSocket server lokal.
Android bisa connect ke WebSocket server tersebut.
Bridge bisa menerima dan decode BridgeEnvelope.
Bridge bisa merespons message dasar:
device.paired
session.created
tool.result
tool.error
audit.event
Implement MVP tools yang aman:
screen.capture
window.list dummy/limited
Untuk tool input seperti mouse.click dan keyboard.type, boleh buat stub dulu jika native helper belum ada.
Tambahkan visible session indicator dan emergency stop sederhana.
Jangan implement native helper C# dulu kecuali benar-benar minimal stub.
Required New Folder

Buat folder baru di root:

windows-bridge/

Struktur yang disarankan:

windows-bridge/
├─ package.json
├─ tsconfig.json
├─ vite.config.ts
├─ README.md
├─ src/
│  ├─ main/
│  │  ├─ main.ts
│  │  ├─ tray.ts
│  │  ├─ window.ts
│  │  ├─ pairing.ts
│  │  └─ app-state.ts
│  │
│  ├─ protocol/
│  │  ├─ bridge-envelope.ts
│  │  ├─ bridge-message-type.ts
│  │  ├─ bridge-tool.ts
│  │  ├─ bridge-approval.ts
│  │  ├─ bridge-risk.ts
│  │  └─ bridge-audit.ts
│  │
│  ├─ transport/
│  │  ├─ websocket-server.ts
│  │  ├─ session-manager.ts
│  │  └─ device-pairing.ts
│  │
│  ├─ tools/
│  │  ├─ tool-registry.ts
│  │  ├─ screen-tools.ts
│  │  ├─ window-tools.ts
│  │  ├─ input-tools-stub.ts
│  │  └─ tool-result.ts
│  │
│  ├─ permissions/
│  │  ├─ risk-engine.ts
│  │  └─ approval-policy.ts
│  │
│  ├─ audit/
│  │  ├─ audit-log.ts
│  │  └─ audit-event.ts
│  │
│  └─ shared/
│     ├─ ids.ts
│     ├─ time.ts
│     └─ logger.ts

Boleh sesuaikan dengan style project, tapi jangan campur ke Android app/.

Stack

Gunakan:

Electron
TypeScript
Node.js
ws
electron-builder atau builder placeholder

Jika repo belum punya package manager preference, gunakan npm atau pnpm sesuai yang paling aman.

Jangan tambah framework UI besar dulu.

Untuk Phase 4, UI boleh minimal:

- tray menu
- small status window
- logs/status text
Protocol Compatibility

Mirror protocol Kotlin dari Phase 1 dalam TypeScript.

Pastikan wire names sama:

tool.call
tool.result
tool.error
session.created
session.closed
device.paired
device.disconnected
approval.request
approval.accepted
approval.rejected
audit.event
error
screen.capture_result

Jangan rename wire protocol.

Setiap incoming message harus:

- parse JSON safely
- validate id/type/sessionId/deviceId/seq/timestamp
- reject unknown type with error envelope
- never crash server
WebSocket Server

Buat WebSocket server lokal.

Default:

host: 0.0.0.0
port: 17878

Configurable lewat env atau config file sederhana.

Pada connect:

1. Validate optional Authorization bearer token if configured.
2. Read `X-Amaya-Device-Id` if available.
3. Create or attach session.
4. Send `device.paired`.
5. Send `session.created`.

Jika header custom sulit dari WebSocket client/browser tertentu, fallback baca deviceId/token dari first message.

Session State

Buat SessionManager.

State minimal:

DISCONNECTED
PAIRING
CONNECTED
AGENT_CONTROL
VIEW_ONLY
PAUSED
CLOSED
ERROR

Session data:

sessionId
deviceId
connectedAt
lastSeenAt
agentControlEnabled
viewOnly
seq

MVP behavior:

- Only one active Android device at a time.
- New connection can replace old only if old disconnected.
- Emergency stop pauses session and rejects new tool.call.
Tool Registry

Register Phase 4 tools:

Enabled:

screen.capture
window.list

Stubbed but known:

mouse.click
keyboard.type
keyboard.hotkey
clipboard.write

Disabled:

shell.run
file.write
file.delete
clipboard.read
browser.*
ui.*

Every tool spec:

{
  name: string
  description: string
  risk: "LOW" | "MEDIUM" | "HIGH" | "BLOCKED"
  requiresApproval: boolean
  enabled: boolean
}
MVP Tool Behavior
screen.capture

Implement actual screen capture if possible using Electron APIs.

Return payload:

{
  "ok": true,
  "tool": "screen.capture",
  "result": {
    "imageBase64": "...",
    "width": 1920,
    "height": 1080,
    "format": "png"
  }
}

If Electron screen capture is too complex, return a clear tool.error with EXECUTION_FAILED, but still keep the tool path.

window.list

Phase 4 can be stub/limited.

Return:

{
  "ok": true,
  "tool": "window.list",
  "result": {
    "windows": []
  }
}

Add TODO for native helper in Phase 5.

mouse.click / keyboard.type / keyboard.hotkey

For Phase 4, do not execute real input yet unless using a safe stub.

Return:

{
  "ok": false,
  "tool": "mouse.click",
  "error": {
    "code": "EXECUTION_FAILED",
    "message": "Native input helper is not implemented yet."
  }
}

Real input belongs to Phase 5 native helper.

Permission / Risk Engine MVP

Implement minimal risk engine:

LOW:
- allowed when session connected

MEDIUM:
- require agentControlEnabled

HIGH:
- reject in Phase 4 or require approval stub

BLOCKED:
- always reject

If tool blocked:

return tool.error PERMISSION_DENIED or COMMAND_BLOCKED

Do not implement destructive operations.

Approval MVP

Phase 4 approval can be minimal.

If a tool requires approval:

1. Send approval.request to Android.
2. Wait for approval.accepted / approval.rejected.
3. Timeout after 30s.

If waiting flow is too much for Phase 4, reject HIGH-risk tools and document full approval as Phase 6.

Do not fake approval success.

Audit Log

Create local audit logger.

For every incoming tool.call:

tool_requested
tool_started
tool_succeeded / tool_failed

Audit event should include:

id
sessionId
toolCallId
tool
risk
decision
argsPreview
resultPreview
timestamp
actor

Do not log full sensitive payload.

Write to:

windows-bridge/logs/audit.log

JSONL format is fine.

Also send audit.event back to Android when useful.

Tray / Status UI

Electron app should show:

- Bridge running
- listening port
- connected device id
- session status
- agent control on/off
- emergency stop
- quit

Emergency stop should:

- set session PAUSED
- reject pending tool calls
- send agent.paused or agent.cancelled to Android

No polished UI required.

Build Scripts

Add scripts:

{
  "scripts": {
    "dev": "...",
    "build": "...",
    "typecheck": "...",
    "start": "..."
  }
}

At minimum:

npm install
npm run typecheck
npm run build
npm run start

or equivalent.

Do not require installer packaging yet if too early.

Constraints

Wajib:

- Jangan ubah Android app behavior.
- Jangan ubah existing Android flow.
- Jangan sentuh Antigravity.
- Jangan implement native helper penuh.
- Jangan implement shell/file destructive tools.
- Jangan execute real mouse/keyboard input unless safe and explicitly isolated.
- Jangan log token/full payload/sensitive data.
- Jangan overbuild UI.

Boleh:

- Tambah folder windows-bridge.
- Tambah Electron TypeScript project.
- Tambah WebSocket server.
- Tambah protocol mirror TS.
- Tambah screen.capture MVP.
- Tambah stub tool results.
- Tambah audit JSONL.
- Tambah tray/status window minimal.
Final Direction

Phase 4 should prove this:

Android can connect to a real Windows Bridge app,
send a BridgeEnvelope tool.call,
Windows can parse it,
execute or stub safely,
return tool.result/tool.error,
and write audit log.

Do not try to finish computer use yet.