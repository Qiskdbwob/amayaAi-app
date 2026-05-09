Phase 6 Main Goal

Phase 6 fokus pada safety dan control, bukan menambah tool berbahaya.

Tujuan utama:

1. Sinkronisasi Agent Control Android ↔ Windows.
2. Implement approval flow end-to-end.
3. Tambahkan app allowlist.
4. Tambahkan folder allowlist placeholder.
5. Tambahkan command policy placeholder.
6. Hardening token handling.
7. Tambahkan opsi kompresi screen.capture.
8. Tambahkan audit viewer basic.
9. Patch Android agar window.focus resmi dikenal sebagai bridge tool.

Jangan implement:

shell.run
file.write
file.delete
browser automation
clipboard.read
credential access
installer packaging besar
Important Architecture Rule

Jangan campur status tool-call local dan Windows.

Local tools tetap punya lifecycle sendiri.

Windows Bridge tools tetap punya lifecycle sendiri.

Target/origin tool-call harus tetap jelas:

LOCAL_ANDROID
WINDOWS_BRIDGE
REMOTE_IDE
MCP

Jika perlu menambahkan metadata, tambahkan tanpa merusak model existing.

Windows Bridge tool-call lifecycle:

created locally
→ sent to bridge
→ waiting bridge result
→ waiting approval jika ada
→ running on Windows
→ completed / failed / timeout / session closed

Local tool-call lifecycle tidak boleh diubah.

Required Work
1. Agent Control Sync Android ↔ Windows

Saat ini ada dua sisi Agent Control:

Android WindowsBridgeController.setAgentControlEnabled(true/false)
Windows SessionManager.agentControlEnabled

Keduanya harus disinkronkan.

Goal

Saat Android mengaktifkan Agent Control:

Android setAgentControlEnabled(true)
→ kirim event/envelope ke Windows
→ Windows update session.agentControlEnabled = true
→ Windows kirim status balik
→ Android update availability/visible tools

Saat Android mematikan Agent Control:

Android setAgentControlEnabled(false)
→ kirim event/envelope ke Windows
→ Windows update session.agentControlEnabled = false
→ Windows menolak MEDIUM tools
→ Android menyembunyikan mouse/keyboard/window.focus
Message Type

Gunakan message yang sudah ada jika cocok:

agent.status
agent.paused
agent.resumed

Atau tambah payload convention pada agent.status:

{
  "status": "agent_control_changed",
  "agentControlEnabled": true
}

Jangan menambah message type baru kalau tidak wajib.

Android side

Update:

WindowsBridgeController
WindowsBridgeSessionClient
WindowsBridgeToolAvailability
WindowsBridgeToolProvider

Expected behavior:

setAgentControlEnabled(true)
  - update local StateFlow
  - send BridgeEnvelope agent.status ke Windows jika connected
  - refresh availability

setAgentControlEnabled(false)
  - update local StateFlow
  - send BridgeEnvelope agent.status ke Windows jika connected
  - hide MEDIUM tools
Windows side

Update:

SessionManager
websocket-server.ts
status/tray UI

Expected behavior:

agent.status with agentControlEnabled true/false
  - update session
  - update tray/status window
  - send status ack if useful
Safety rule

MEDIUM tools require both:

Android advertises tool only when Agent Control true
Windows risk engine allows execution only when Agent Control true

This defense-in-depth must remain.

2. Add Approval Flow End-to-End

Phase 5 rejected HIGH-risk tools because approval was not implemented.

Phase 6 should add real approval infrastructure, but do not enable dangerous tools yet unless safe.

Goal

Support flow:

Windows receives tool.call
→ risk engine says REQUIRE_APPROVAL
→ Windows sends approval.request to Android
→ Android exposes ApprovalRequest event
→ Android sends approval.accepted / approval.rejected
→ Windows continues or rejects
→ timeout defaults reject
Windows side

Update:

approval-policy.ts
websocket-server.ts
session-manager.ts if needed

Implement pending approvals:

approvalRequestId → pending approval promise/state

Approval request fields:

id
sessionId
toolCallId
tool
risk
reason
argsPreview
requestedAt
expiresAt
status

Timeout:

default 30s
on timeout → reject

If rejected:

return tool.error APPROVAL_REJECTED
audit approval_rejected

If expired:

return tool.error APPROVAL_REJECTED or TIMEOUT
audit approval_rejected/expired

If approved:

continue execution
audit approval_accepted
Android side

Phase 6 should minimally expose approval to existing flow.

Current existing pieces:

WindowsBridgeApprovalMapper
WindowsBridgeToolExecutor
WindowsBridgeSessionClient
local inline approval pattern exists in app

Implement the least invasive path.

Required:

WindowsBridgeController exposes pendingApproval StateFlow/SharedFlow
WindowsBridgeController can respondApproval(requestId, approved, reason?)
WindowsBridgeSessionClient can send approval.accepted / approval.rejected

Optional UI:

If small and safe, add minimal approval surface.
If UI integration is risky, expose state and document TODO.

But Phase 6 should at least support programmatic approval/decision.

Approval event mapping

Incoming:

approval.request

Android should decode to:

ApprovalRequest

Then emit:

WindowsBridgeClientEvent.ApprovalRequestReceived

Decision:

approval.accepted
approval.rejected

Payload:

{
  "requestId": "approval_123",
  "sessionId": "session_abc",
  "toolCallId": "call_001",
  "approved": true,
  "decidedAt": 1778320000,
  "reason": "User approved from Android"
}
Important

Do not fake approval success.

Do not auto-approve HIGH tools.

Default if no UI/user response:

reject / timeout
3. Add App Allowlist

Phase 6 should add app/window allowlist policy.

Goal

MEDIUM tools like:

mouse.click
keyboard.type
keyboard.hotkey
window.focus
clipboard.write

should optionally be restricted to allowed apps/windows.

Config model

Add config file or in-memory model:

windows-bridge/config/security-policy.json

Suggested content:

{
  "appAllowlistEnabled": false,
  "allowedProcessNames": [
    "notepad",
    "Code",
    "chrome",
    "msedge"
  ],
  "allowedWindowTitlePatterns": [],
  "blockedProcessNames": [],
  "blockedWindowTitlePatterns": [
    "password",
    "credential",
    "bank",
    "wallet"
  ]
}

Default for MVP:

appAllowlistEnabled = false

But blocked patterns can still be enforced if safe.

Policy behavior

Before executing input tools:

mouse.click
keyboard.type
keyboard.hotkey

Check active/focused window:

processName
windowTitle

If allowlist enabled:

processName must match allowlist OR title pattern match.

If blocked pattern match:

deny.

Return error:

APP_NOT_ALLOWED

Audit:

tool_failed
decision = denied_by_app_policy
Required helper support

If needed, add helper method:

window.active

Return:

{
  "window": {
    "id": "123456",
    "title": "Untitled - Notepad",
    "processId": 1234,
    "processName": "notepad",
    "bounds": {...},
    "visible": true,
    "focused": true
  }
}

Do not implement full UI Automation tree yet.

4. Folder Allowlist Placeholder

Do not implement file tools yet.

But create policy model for future file tools.

Add config section:

{
  "folderPolicy": {
    "allowedFolders": [],
    "blockedFolders": [
      "%USERPROFILE%\\.ssh",
      "%USERPROFILE%\\AppData",
      "%USERPROFILE%\\Documents\\Passwords"
    ],
    "sensitivePathPatterns": [
      "id_rsa",
      ".env",
      "credentials",
      "token",
      "password"
    ]
  }
}

Create helper/policy module:

folder-policy.ts

Expected methods:

isPathAllowed(path)
isSensitivePath(path)
explainPathDecision(path)

But keep file tools disabled:

file.list disabled
file.read disabled
file.write disabled
file.delete disabled
5. Command Policy Placeholder

Do not implement shell.run yet.

But create policy model for future shell tools.

Add config section:

{
  "commandPolicy": {
    "shellEnabled": false,
    "allowedCommands": [],
    "blockedCommands": [
      "rm",
      "del",
      "format",
      "shutdown",
      "reg",
      "net user",
      "powershell -enc",
      "curl",
      "wget"
    ],
    "requireApprovalForAll": true
  }
}

Create:

command-policy.ts

Expected methods:

isCommandAllowed(command)
isCommandBlocked(command)
explainCommandDecision(command)

But keep tools disabled:

shell.run disabled
shell.cancel disabled
6. Harden Token Handling

Phase 4/5 allowed token from:

Authorization: Bearer <token>
or ?token=...

Phase 6 should add config:

{
  "auth": {
    "requireToken": false,
    "allowQueryTokenFallback": true
  }
}

Behavior:

Authorization header is preferred.
Query token fallback only allowed if allowQueryTokenFallback = true.
Never log token.
Status UI should show token configured: yes/no, not token value.

If allowQueryTokenFallback = false:

reject query token.
require header token if token is configured.

For dev, default can remain:

allowQueryTokenFallback = true

But document production recommendation:

allowQueryTokenFallback = false
7. Improve screen.capture Options

Phase 4/5 screen.capture real but base64 can be large.

Add args support:

{
  "displayIndex": 0,
  "format": "jpeg",
  "quality": 75,
  "maxWidth": 1280
}

Supported:

format: png | jpeg
quality: 1-100 for jpeg
displayIndex: number
maxWidth: optional number

Behavior:

- If maxWidth set, resize thumbnail before base64 if possible.
- If format jpeg, use quality.
- If invalid displayIndex, return INVALID_ARGS.
- If invalid format, return INVALID_ARGS.
- Always redact imageBase64 in logs/audit.

Result:

{
  "imageBase64": "...",
  "width": 1280,
  "height": 720,
  "format": "jpeg",
  "displayIndex": 0,
  "originalWidth": 1920,
  "originalHeight": 1080
}
8. Add Basic Audit Viewer

Audit log already exists as JSONL.

Phase 6 should add basic viewer to status window.

Goal

Status window can show recent audit summary.

Do not show full sensitive payload.

Add module:

audit-reader.ts

Capabilities:

read last N audit events
redact again before display
return summary only

Status UI:

Recent Activity:
- 20:01 screen.capture succeeded
- 20:02 mouse.click denied: Agent Control disabled
- 20:03 keyboard.type succeeded length=12

Do not display:

typed text
imageBase64
token
secret values
full payload
9. Android Patch: Add window.focus Tool

Phase 5 added window.focus in Windows helper/Electron, but Android may not officially know the tool yet.

Patch Android:

BridgeToolNames.kt
WindowsBridgeToolDefinitions.kt

Add:

window.focus

Risk:

MEDIUM

Requires:

Agent Control enabled
Bridge connected

Tool definition:

name: window.focus
description: Focus a top-level Windows window by id.
parameters:
  windowId: string required

Make sure:

window.focus is hidden when bridge offline
window.focus is hidden when Agent Control disabled
window.focus returns clear error if hallucinated offline
Tool Risk Policy After Phase 6

Enabled LOW:

screen.capture
window.list

Enabled MEDIUM, gated by Agent Control + policy:

window.focus
mouse.click
keyboard.type
keyboard.hotkey
clipboard.write

Approval infrastructure exists but dangerous tools still disabled:

clipboard.read
file.list
file.read
file.write
file.delete
shell.run
shell.cancel
browser.open
browser.goto
browser.dom
browser.click
browser.type
browser.screenshot
ui.tree
ui.find_text
ui.click_element

HIGH-risk tools remain disabled unless explicitly scoped later.

Expected File Areas
Windows Bridge

Likely modify/add:

windows-bridge/src/transport/websocket-server.ts
windows-bridge/src/transport/session-manager.ts
windows-bridge/src/transport/device-pairing.ts

windows-bridge/src/permissions/risk-engine.ts
windows-bridge/src/permissions/approval-policy.ts
windows-bridge/src/permissions/app-allowlist.ts
windows-bridge/src/permissions/folder-policy.ts
windows-bridge/src/permissions/command-policy.ts
windows-bridge/src/permissions/security-policy.ts

windows-bridge/src/tools/screen-tools.ts
windows-bridge/src/tools/window-tools.ts
windows-bridge/src/tools/tool-registry.ts

windows-bridge/src/audit/audit-log.ts
windows-bridge/src/audit/audit-reader.ts

windows-bridge/src/main/app-state.ts
windows-bridge/src/main/tray.ts
windows-bridge/src/main/window.ts
windows-bridge/src/main/preload.ts
windows-bridge/src/main/main.ts
windows-bridge/src/main/status.html

windows-bridge/src/native/native-tools.ts
windows-bridge/src/native/native-helper-client.ts

windows-bridge/README.md

Native helper may need:

windows-bridge/native-helper/Services/WindowService.cs
windows-bridge/native-helper/Program.cs

Only if adding window.active.

Android

Likely modify:

app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeToolNames.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeSessionClient.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeClientEvent.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeEnvelopeMapper.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeController.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolDefinitions.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolProvider.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeApprovalMapper.kt

Do not touch:

Antigravity RemoteSessionClient
Android browser operator
memory/persona/skills/self-improvement
local ToolExecutor unless absolutely necessary
Build / Test Requirements

Run if available:

npm run typecheck
npm run build:helper
npm run build
.\gradlew.bat compileDebugKotlin

If there is a full project build target:

.\gradlew.bat assembleDebug

Run it if reasonable.

Safety Requirements

Wajib:

- Do not log token.
- Do not log typed text.
- Do not log imageBase64.
- Do not log password/secret/apiKey/authorization.
- Do not auto-approve HIGH-risk tools.
- Do not enable shell.run.
- Do not enable file.write.
- Do not enable file.delete.
- Do not enable clipboard.read.
- Do not implement credential extraction.
- Do not require admin.
- Do not fake success.
- Do not crash if helper stopped.
- Do not crash on malformed WebSocket message.