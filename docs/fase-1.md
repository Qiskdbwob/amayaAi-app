# Phase 1 — Build Windows Bridge Shared Protocol for Amaya

## Product Vision

Saya sedang membangun **Amaya**, sebuah AI assistant / AI agent Android.

Visi jangka panjangnya:

Amaya Android akan menjadi **AI agent controller** yang bisa memahami perintah user, melakukan reasoning, menjalankan tool-call, dan mengontrol environment eksternal.

Salah satu environment penting yang ingin saya bangun adalah:

```txt
Android AI Agent
→ terkoneksi ke Windows Bridge
→ Windows Bridge berjalan di komputer Windows
→ Android bisa melihat, mengontrol, dan menjalankan task di komputer Windows
→ semua berjalan dengan permission, approval, audit log, dan emergency stop
````

Saya ingin sistem ini seperti kombinasi:

```txt
AI Agent Controller
+ Remote Computer Use
+ Secure Windows Executor
+ Tool-call protocol
+ Approval-based automation
```

Target akhirnya:

User bisa dari Android memberi instruksi seperti:

> “Buka laptop saya, cek project Amaya, jalankan dev server, lihat error, dan bantu perbaiki, tapi jangan delete/push/run command berisiko tanpa izin.”

Lalu Android AI Agent akan membuat rencana, memanggil tool-call, Windows Bridge mengeksekusi, mengirim hasil/screenshot/status balik ke Android, lalu AI lanjut reasoning.

---

## Current Codebase Context

Codebase Amaya saat ini adalah Android app utama.

Dari audit sebelumnya, ditemukan:

* Android app sudah punya agent loop lokal.
* Android app sudah punya tool executor.
* Android app sudah punya approval inline di tool card.
* Android app sudah punya browser automation berbasis Android WebView.
* Android app sudah punya remote WebSocket flow untuk Antigravity/VS Code extension.
* Android app sudah punya memory, persona, skill, self-improvement, session recall.
* Tapi codebase belum punya **shared protocol murni** untuk Windows Bridge.
* Model message, tool-call, approval, event, dan session masih tersebar di beberapa layer:

  * `domain/`
  * `data/remote/api/`
  * `tools/`
  * `impl/ide/antigravity/client/`
  * UI state model

Masalah yang harus dihindari:

* Jangan mencampur Windows Bridge ke Antigravity flow.
* Jangan reuse langsung `RemoteSessionClient` Antigravity sebagai bridge protocol.
* Jangan menambah logic Windows ke model UI Compose.
* Jangan memindahkan semua logic Android ke shared.
* Jangan refactor besar-besaran dulu.
* Jangan mengubah local agent flow yang sudah berjalan.
* Jangan mengubah memory/persona/skills/self-improvement.

---

## Architecture Direction

Arsitektur target:

```txt
Android App
  = AI planner / controller / chat UI / approval UI

Shared Bridge Protocol
  = kontrak pesan antara Android dan Windows Bridge

Windows Bridge
  = remote executor / screen capture / input executor / file-shell-browser tool executor

Electron
  = tray app / pairing / approval popup / session indicator / log viewer

Native Helper
  = Windows native action executor
```

Pemisahan tanggung jawab:

```txt
Android:
- reasoning
- AI provider call
- chat state
- approval UI
- agent planning
- memory/persona/skills/context

Shared Protocol:
- BridgeEnvelope
- BridgeMessageType
- BridgeToolCall
- BridgeToolResult
- ApprovalRequest
- ApprovalDecision
- BridgeSessionState
- RiskLevel
- PermissionDecision
- AuditEvent

Windows Bridge:
- receive tool.call
- validate session
- check permission/risk
- request approval if needed
- execute tool
- write audit log
- return tool.result/tool.error/screen.frame
```

---

## Phase 1 Goal

Phase 1 hanya membuat **fondasi shared protocol kecil** di Android codebase.

Jangan implement Electron.
Jangan implement native helper.
Jangan implement Windows executor.
Jangan implement WebSocket client baru dulu kecuali benar-benar minimal untuk type reference.
Jangan ubah behavior runtime existing.

Tujuan Phase 1:

1. Membuat package/domain model baru untuk Windows Bridge protocol.
2. Menjadi kontrak awal antara Android dan Windows Bridge.
3. Model harus platform-neutral.
4. Tidak boleh import Android UI, Compose, Hilt, Room, Context, Activity, ViewModel, atau data persistence.
5. Tidak boleh mengganggu local chat, Antigravity remote session, browser operator, memory, skill, atau self-improvement.
6. Tambahkan dokumentasi singkat agar future phase jelas.

---

## Required New Package

Buat package baru:

```txt
app/src/main/java/com/amaya/intelligence/domain/bridge/
```

Package ini harus berisi model-model kecil dan bersih.

File yang disarankan:

```txt
BridgeEnvelope.kt
BridgeMessageType.kt
BridgeToolCall.kt
BridgeToolResult.kt
BridgeApproval.kt
BridgeSessionState.kt
BridgeRiskPolicy.kt
BridgeAuditEvent.kt
BridgeError.kt
```

Gunakan Kotlin data class / sealed class / enum class yang sederhana.

---

## Required Models

### 1. BridgeEnvelope

Model wrapper untuk semua pesan Android ↔ Windows Bridge.

Harus mendukung:

```txt
id
type
sessionId
deviceId
seq
timestamp
payload
```

Contoh konsep JSON:

```json
{
  "id": "msg_001",
  "type": "tool.call",
  "sessionId": "session_abc",
  "deviceId": "android_123",
  "seq": 42,
  "timestamp": 1778320000,
  "payload": {}
}
```

Payload boleh dibuat typed dengan sealed class, atau dibuat generic map jika lebih cocok dengan existing serialization. Pilih pendekatan yang paling aman dengan codebase saat ini.

---

### 2. BridgeMessageType

Buat enum atau sealed type untuk message type berikut:

```txt
session.created
session.closed
device.paired
device.disconnected

screen.frame
screen.capture_result

tool.call
tool.result
tool.error

agent.status
agent.step
agent.paused
agent.resumed
agent.cancelled

approval.request
approval.accepted
approval.rejected

audit.event
error
```

Gunakan naming Kotlin yang rapi, tapi pastikan serial value tetap bisa menjadi string seperti di atas.

Contoh:

```kotlin
enum class BridgeMessageType(val wireName: String) {
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result")
}
```

---

### 3. BridgeToolCall

Model untuk request tool-call dari Android ke Windows Bridge.

Field minimal:

```txt
id
sessionId
tool
args
risk
requiresApproval
createdAt
timeoutMs
metadata
```

Contoh payload:

```json
{
  "id": "call_001",
  "sessionId": "session_abc",
  "tool": "mouse.click",
  "args": {
    "x": 720,
    "y": 420,
    "button": "left"
  },
  "risk": "MEDIUM",
  "requiresApproval": false,
  "createdAt": 1778320000,
  "timeoutMs": 30000
}
```

---

### 4. BridgeToolResult

Model untuk response sukses dari Windows Bridge.

Field minimal:

```txt
id
toolCallId
sessionId
tool
status
result
startedAt
finishedAt
durationMs
metadata
```

Status minimal:

```txt
success
cancelled
timeout
```

---

### 5. BridgeToolError

Model untuk response gagal.

Field minimal:

```txt
id
toolCallId
sessionId
tool
code
message
details
recoverable
timestamp
```

Code minimal:

```txt
INVALID_ARGS
PERMISSION_DENIED
APP_NOT_ALLOWED
PATH_NOT_ALLOWED
COMMAND_BLOCKED
APPROVAL_REQUIRED
APPROVAL_REJECTED
EXECUTION_FAILED
TIMEOUT
SESSION_CLOSED
UNKNOWN
```

---

### 6. BridgeApproval

Buat model:

```txt
ApprovalRequest
ApprovalDecision
ApprovalStatus
```

ApprovalRequest field:

```txt
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
```

ApprovalDecision field:

```txt
requestId
sessionId
toolCallId
approved
decidedAt
reason
```

Status:

```txt
pending
approved
rejected
expired
cancelled
```

---

### 7. BridgeRiskPolicy

Buat enum:

```txt
LOW
MEDIUM
HIGH
BLOCKED
```

Buat juga permission decision:

```txt
ALLOW
REQUIRE_APPROVAL
DENY
BLOCK
```

Tambahkan helper sederhana:

```txt
shouldRequireApproval()
isBlocked()
```

Aturan default:

```txt
LOW:
- allowed if session active

MEDIUM:
- allowed if Agent Control mode active

HIGH:
- require approval

BLOCKED:
- never execute
```

Jangan implement policy engine penuh dulu. Cukup model dan helper kecil.

---

### 8. BridgeSessionState

Buat model untuk session lifecycle.

Status minimal:

```txt
created
pairing
connected
agent_control
view_only
paused
cancelled
closed
error
```

Field:

```txt
sessionId
deviceId
computerName
status
capabilities
createdAt
updatedAt
lastSeenAt
```

Capabilities contoh:

```txt
screenCapture
screenStream
mouseControl
keyboardControl
windowControl
fileAccess
shellAccess
browserAccess
uiAutomation
clipboardAccess
```

---

### 9. BridgeAuditEvent

Model untuk audit log event.

Field minimal:

```txt
id
sessionId
toolCallId
eventType
tool
risk
decision
argsPreview
resultPreview
timestamp
actor
```

Event type minimal:

```txt
tool_requested
approval_requested
approval_accepted
approval_rejected
tool_started
tool_succeeded
tool_failed
tool_cancelled
session_paused
session_resumed
session_closed
```

Actor:

```txt
android_user
android_agent
windows_bridge
native_helper
system
```

---

## Windows Tool Names for Future Compatibility

Jangan implement executornya sekarang, tapi pastikan model bisa membawa tool name seperti:

```txt
screen.capture
screen.stream.start
screen.stream.stop

window.list
window.focus
window.close

mouse.move
mouse.click
mouse.double_click
mouse.drag
mouse.scroll

keyboard.type
keyboard.press
keyboard.hotkey

clipboard.read
clipboard.write

ui.tree
ui.find_text
ui.click_element

file.list
file.read
file.write
file.move
file.delete

shell.run
shell.cancel

browser.open
browser.goto
browser.dom
browser.click
browser.type
browser.screenshot
```

Boleh tambahkan object/list constant:

```kotlin
object BridgeToolNames {
    const val SCREEN_CAPTURE = "screen.capture"
    const val MOUSE_CLICK = "mouse.click"
}
```

Jangan buat executor.

---



## Constraints

Wajib:

* Jangan ubah flow local agent.
* Jangan ubah Antigravity remote flow.
* Jangan ubah browser operator Android.
* Jangan ubah memory/persona/skill/self-improvement.
* Jangan ubah UI kecuali benar-benar perlu untuk compile.
* Jangan tambah dependency besar.
* Jangan buat Electron.
* Jangan buat native helper.
* Jangan buat WebSocket runtime Windows Bridge penuh.
* Jangan menghapus file existing.
* Jangan rename package existing.
* Jangan refactor besar.


---

## Quality Requirements

Pastikan:

* Semua model compile.
* Tidak ada import Android UI/Compose di package `domain/bridge`.
* Naming konsisten.
* Serial/wire names jelas.
* Model cukup fleksibel untuk JSON payload.
* Tidak over-engineering.
* Tidak mengubah behavior existing app.
* Tidak membuat abstraction terlalu besar.
* Tidak membuat duplicate runtime logic.

---


---

## Important Product Direction

Ingat:

Windows Bridge bukan agent utama.

Windows Bridge adalah:

```txt
remote executor
+ permission gate
+ audit logger
+ screen/input/file/shell/browser tool host
```

Android tetap:

```txt
AI planner
+ chat UI
+ approval UI
+ memory/context owner
+ session controller
```

Jadi Phase 1 harus hanya membuat bahasa/kontrak yang akan dipakai Android dan Windows untuk saling bicara.

```
```
