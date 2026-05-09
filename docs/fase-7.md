Phase 7A Goal

Phase 7A bertujuan membuat Windows Bridge bisa dipakai user secara nyata dari Android.

Fokus:

1. Android Windows Bridge list / connect UI.
2. Android remote screen viewer untuk screenshot/capture.
3. Android Agent Control toggle UI.
4. Android approval UI untuk approval.request.
5. Android emergency stop button.
6. Windows Bridge pairing UX.
7. Windows Bridge packaging/dev run polish.
8. No shell/file/browser dangerous tools yet.

Jangan implement:

shell.run
file.write
file.delete
clipboard.read
browser automation
full UI Automation tree
credential access
Part A — Android Remote Screen / Windows Bridge UI
1. Add Windows Bridge Entry in Android Remote Screen

Cari existing remote screen / remote session UI:

ui/screens/remote/
ui/activities/remote/
ui/activities/chat/remote/
ui/screens/chat/remote/

Tambahkan entry untuk Windows Bridge.

UI minimal:

Windows Bridge
Status: Disconnected / Connecting / Connected / Agent Control / Paused / Error
Host: <ip>
Port: 17878
Device: <deviceId>
Session: <sessionId>
Buttons:
- Connect
- Disconnect
- View Screen
- Agent Control toggle
- Emergency Stop

Jangan campur dengan Antigravity provider secara mentah.

Buat provider/card terpisah jika ada list provider:

Antigravity
Cursor
Windsurf
Windows Bridge
2. Manual Connect Form

Buat form minimal:

Host/IP
Port
Token optional
Device name/id
Connect button

Default:

port = 17878
deviceId = Android device id / generated stable id

Expected behavior:

Connect
→ WindowsBridgeController.connect(config)
→ observe connectionState
→ show connected/sessionId

Jangan implement QR dulu kalau terlalu besar. Manual connect cukup untuk Phase 7A.

QR pairing boleh jadi TODO Phase 7B.

3. Persist Last Connection Config

Simpan config terakhir secara ringan:

host
port
deviceId
token? maybe not token unless secure storage exists

Jika token disimpan, gunakan existing secure storage jika sudah ada.

Kalau belum ada secure storage yang jelas:

Do not persist token.
Persist only host/port/deviceId.

Jangan simpan token plaintext di DataStore biasa.

4. Remote Screen Viewer

Buat screen Android untuk melihat capture dari Windows.

Minimal flow:

Open View Screen
→ call bridge tool screen.capture
→ show image
→ refresh button

MVP tidak perlu video stream.

UI:

Windows Remote Screen
- status bar: connected/session/agent control
- image preview from screen.capture
- Refresh
- Agent Control toggle
- Emergency Stop
- optional displayIndex/quality selector

Use existing bridge tool path:

WindowsBridgeToolProvider.executeBridgeTool("screen.capture", args, sessionId)

or controller wrapper if available.

Args default:

{
  "format": "jpeg",
  "quality": 75,
  "maxWidth": 1280
}

Result contains:

imageBase64
width
height
format
displayIndex

Decode base64 safely.

If capture fails:

show error card.
do not crash.
5. Agent Control Toggle UI

Expose toggle:

Agent Control: OFF / ON

On toggle ON:

WindowsBridgeController.setAgentControlEnabled(true)

On toggle OFF:

WindowsBridgeController.setAgentControlEnabled(false)

Show warning before enabling Agent Control:

Agent Control allows Amaya to click, type, and focus windows on your PC. You can stop anytime.

Do not enable by default.

6. Emergency Stop UI

Add prominent button:

Emergency Stop

Action:

WindowsBridgeController.cancelSession()
or send agent.cancelled / emergency stop method if available

If existing method is not exact, add wrapper:

emergencyStop()

Expected:

Windows pauses/rejects input tools.
Android hides MEDIUM tools or marks paused.
user can resume manually.
7. Approval UI

Android Phase 6 exposed:

pendingApproval: StateFlow<ApprovalRequest?>
approvalEvents: SharedFlow<ApprovalRequest>
respondApproval(requestId, approved, reason?)
respondPending(approved, reason?)

Now add minimal UI for pending approval.

UI content:

Approval Required
Tool: <tool>
Risk: <LOW/MEDIUM/HIGH/BLOCKED>
Reason: <reason>
Args preview: safe preview only
Expires: countdown if available
Buttons:
- Approve
- Reject

Actions:

Approve → WindowsBridgeController.respondPending(true, "Approved by user")
Reject  → WindowsBridgeController.respondPending(false, "Rejected by user")

Do not show sensitive payload raw.

Args preview must redact:

text typed
token
imageBase64
password
secret
authorization
apiKey
8. Recent Bridge Activity UI

Optional but useful.

If Windows Bridge sends audit events or status:

show last few events in Android screen.

Minimal:

Recent Activity
- screen.capture succeeded
- mouse.click denied
- keyboard.type approved

No sensitive payload.

Part B — Windows Bridge Pairing UX
1. Status Window Improvements

Windows status window already has:

helper status
token source
recent activity
Agent Control
emergency stop

Improve pairing section:

Windows Bridge is running
Address: ws://<local-ip>:17878
Token: configured yes/no
Device: <connected device id>
Session: <sessionId>

Show copy button:

Copy connection info

Connection info format:

Host: 192.168.x.x
Port: 17878
Token: <hidden or "configured">

Do not display token value by default.

2. Local IP Detection

Add utility to show likely LAN IPs:

192.168.x.x
10.x.x.x
172.16-31.x.x

Do not rely on only localhost, because Android needs LAN IP.

3. Pairing Token UX

If token configured:

show “Token required”.
do not show token by default.
optionally show “Reveal token” with warning if token exists in env/config and safe.

If no token:

show “No token configured — dev mode only”.
4. QR Placeholder

Do not implement full QR if too much.

Add placeholder section:

QR Pairing: coming in Phase 7B

or implement only if easy without large dependency.

Do not add large QR dependency unless justified.

Part C — Packaging / Run Polish
1. Scripts

Ensure scripts exist:

{
  "scripts": {
    "typecheck": "...",
    "build:helper": "...",
    "build": "...",
    "start": "...",
    "dev": "...",
    "clean": "..."
  }
}

Add if useful:

{
  "scripts": {
    "verify": "npm run typecheck && npm run build:helper && npm run build"
  }
}
2. Dev Config

Provide clear env/config docs:

AMAYA_BRIDGE_HOST
AMAYA_BRIDGE_PORT
AMAYA_BRIDGE_TOKEN
AMAYA_BRIDGE_POLICY_PATH
AMAYA_BRIDGE_HELPER_PATH
3. Packaging Placeholder

Do not need final installer yet.

But prepare notes for future:

electron-builder
NSIS/MSIX
bundle Native Helper
.NET self-contained option
auto-start optional

If simple electron-builder config is already there, keep it minimal. Do not overbuild installer.

Part D — Tool Availability Correctness

Ensure Android UI and agent agree:

When disconnected

Visible tools:

none

Remote UI:

Disconnected
When connected but Agent Control OFF

Visible tools:

screen.capture
window.list

Remote UI:

View Only / Observe mode
When connected and Agent Control ON

Visible tools:

screen.capture
window.list
window.focus
mouse.click
keyboard.type
keyboard.hotkey
clipboard.write

Remote UI:

Agent Control ON
Emergency stop

Visible/allowed:

screen.capture maybe allowed
input tools denied

Remote UI:

Paused / Emergency stopped
Part E — Constraints

Wajib:

- Jangan implement shell.run.
- Jangan implement file.write.
- Jangan implement file.delete.
- Jangan implement clipboard.read.
- Jangan implement browser automation.
- Jangan implement credential extraction.
- Jangan auto-enable Agent Control.
- Jangan simpan token plaintext jika secure storage belum jelas.
- Jangan log token.
- Jangan tampilkan typed text di approval/audit.
- Jangan ubah Antigravity flow.
- Jangan ubah local ToolExecutor kecuali sangat perlu.
- Jangan gabungkan status local tool dan Windows tool secara mentah.

Boleh:

- Tambah Android Windows Bridge UI.
- Tambah manual connect form.
- Tambah remote screen capture viewer.
- Tambah approval UI.
- Tambah Agent Control toggle.
- Tambah Emergency Stop.
- Tambah Windows status window pairing improvements.
- Tambah local IP display.
- Tambah verify script.
- Tambah docs.
Expected Files / Areas
Android likely areas
app/src/main/java/com/amaya/intelligence/ui/screens/remote/
app/src/main/java/com/amaya/intelligence/ui/activities/remote/
app/src/main/java/com/amaya/intelligence/ui/components/remote/
app/src/main/java/com/amaya/intelligence/ui/viewmodels/
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeController.kt
app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolProvider.kt

Add new files if cleaner:

WindowsBridgeScreen.kt
WindowsBridgeRemoteScreen.kt
WindowsBridgeConnectSheet.kt
WindowsBridgeApprovalSheet.kt
WindowsBridgeViewModel.kt
WindowsBridgeUiState.kt

Follow existing Compose style.

Windows likely areas
windows-bridge/src/main/status.html
windows-bridge/src/main/preload.ts
windows-bridge/src/main/window.ts
windows-bridge/src/main/app-state.ts
windows-bridge/src/main/pairing.ts
windows-bridge/src/shared/logger.ts
windows-bridge/README.md
windows-bridge/package.json
Build / Test Requirements

Run:

npm run typecheck
npm run build:helper
npm run build
./gradlew assembleDebug

If on Windows:

.\gradlew.bat assembleDebug