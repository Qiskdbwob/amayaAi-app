# Phase 3 — Integrate Windows Bridge as Android Agent Remote Tool Executor

## Context

Phase 1 sudah selesai:
- `domain/bridge/` berisi protocol model:
  - BridgeEnvelope
  - BridgeMessageType
  - BridgeToolCall
  - BridgeToolResult
  - BridgeToolError
  - BridgeApproval
  - BridgeRiskPolicy
  - BridgeSessionState
  - BridgeAuditEvent
  - BridgeToolNames

Phase 2 sudah selesai:
- `impl/bridge/windows/` berisi Android Windows Bridge client:
  - WindowsBridgeSessionClient
  - WindowsBridgeClientConfig
  - WindowsBridgeConnectionState
  - WindowsBridgeClientEvent
  - WindowsBridgeEventHandler
  - WindowsBridgeEnvelopeMapper
  - WindowsBridgeLogger

Build Phase 2 sudah compile bersih.

Sekarang lanjut ke **Phase 3**.

---

## Product Vision Reminder

Amaya adalah AI assistant Android.

Target sistem:

```txt
Android AI Agent
→ reasoning dan planning
→ memanggil bridge tool-call
→ Windows Bridge menerima tool.call
→ Windows Bridge execute di komputer Windows
→ Windows Bridge mengirim tool.result / tool.error / approval.request
→ Android memasukkan hasil ke agent loop
→ AI lanjut reasoning

Android tetap:

AI planner
chat UI
approval UI
memory/context owner
session controller

Windows Bridge tetap:

remote executor
permission gate
audit logger
screen/input/file/shell/browser host

Phase 3 belum membuat Electron dan belum membuat native helper.

Phase 3 hanya membuat Android agent bisa melihat Windows Bridge sebagai remote tool executor.

Phase 3 Goal

Integrasikan Windows Bridge client ke tool system Android.

Tujuan utama:

Membuat bridge tools muncul sebagai tool-call yang bisa dipanggil AI agent.
Ketika model memanggil tool seperti screen.capture atau mouse.click, Android mengubahnya menjadi BridgeToolCall.
Android mengirim BridgeToolCall lewat WindowsBridgeSessionClient.
Android menunggu BridgeToolResult atau BridgeToolError.
Result/error dikembalikan ke existing agent loop sebagai ToolResult.
Approval request dari bridge harus bisa dipetakan ke existing approval flow jika memungkinkan.
Jangan membuat Windows executor dulu.
Jangan membuat Electron dulu.
Jangan mengubah Antigravity flow.
Important Boundary

Jangan bikin Windows Bridge menjadi agent baru.

Yang benar:

AI planning = Android
Tool execution = Windows Bridge

Jangan pindahkan logic ini ke Windows:

memory
persona
skills
self-improvement
provider call
context manager
agent loop

Windows hanya menerima tool-call dan mengirim result.

Files / Package

Buat package baru jika belum ada:

app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/

File yang disarankan:

WindowsBridgeToolExecutor.kt
WindowsBridgeToolRegistry.kt
WindowsBridgeToolDefinitions.kt
WindowsBridgeToolMapper.kt
WindowsBridgeToolResultMapper.kt
WindowsBridgeToolAvailability.kt
WindowsBridgeApprovalMapper.kt
README.md

Sesuaikan dengan pola existing codebase jika ada struktur yang lebih cocok.

1. WindowsBridgeToolRegistry

Buat registry bridge tools.

Minimal tool Phase 3:

screen.capture
window.list
mouse.click
keyboard.type
keyboard.hotkey
clipboard.write

Jangan aktifkan tool berbahaya dulu kecuali sebagai definition disabled atau future placeholder.

Future tools boleh didefinisikan tapi jangan otomatis enabled:

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

Setiap tool definition harus punya metadata:

name
description
parameters
risk
requiresApproval
category
enabledByDefault

Risk awal:

screen.capture   = LOW
window.list      = LOW
mouse.click      = MEDIUM
keyboard.type    = MEDIUM
keyboard.hotkey  = MEDIUM
clipboard.write  = MEDIUM

file.write       = HIGH disabled
file.delete      = HIGH disabled
shell.run        = HIGH disabled
clipboard.read   = HIGH disabled
browser.type     = MEDIUM/HIGH disabled
2. WindowsBridgeToolExecutor

Buat executor yang menjembatani existing Android tool system ke Windows Bridge.

Executor harus:

- menerima nama tool dan args dari existing ToolExecutor / agent loop
- validasi bridge connected
- validasi tool ada di registry
- validasi apakah tool enabled
- buat BridgeToolCall
- kirim via WindowsBridgeSessionClient
- tunggu result/error dengan timeout
- convert BridgeToolResult/BridgeToolError ke ToolResult existing

Jika bridge belum connected:

return ToolResult error:
"Windows Bridge is not connected. Connect to a Windows Bridge session first."

Jika tool disabled:

return ToolResult error:
"Windows Bridge tool is disabled in this phase."

Jika timeout:

return ToolResult error:
"Windows Bridge tool timed out."

Jangan crash.

3. Pending Tool Call Handling

Karena WindowsBridgeSessionClient menerima event async, Phase 3 perlu pending map:

toolCallId → CompletableDeferred<Result>

Saat kirim tool.call:

create pending deferred
send BridgeEnvelope
wait result/error/timeout
remove pending entry

Saat receive event:

tool.result with toolCallId → complete pending success
tool.error with toolCallId → complete pending failure
approval.request → emit approval flow
session.closed/error → fail all pending calls

Pastikan thread/coroutine safe.

4. BridgeToolCall Mapping

Buat mapper:

WindowsBridgeToolMapper

Tugas:

existing tool name + args
→ BridgeToolCall
→ BridgeEnvelope

Pastikan field berikut terisi:

id
sessionId
tool
args
risk
requiresApproval
createdAt
timeoutMs
metadata

Metadata minimal:

source = "android_agent"
phase = "phase_3"
5. BridgeToolResult Mapping

Buat mapper:

WindowsBridgeToolResultMapper

Tugas:

BridgeToolResult / BridgeToolError
→ existing ToolResult

Result sukses harus bisa dikembalikan ke agent sebagai JSON/text yang bisa dipahami model.

Contoh success:

{
  "ok": true,
  "tool": "screen.capture",
  "result": {
    "imageBase64": "...",
    "width": 1920,
    "height": 1080
  }
}

Contoh error:

{
  "ok": false,
  "tool": "mouse.click",
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "Agent control is not enabled."
  }
}

Gunakan style ToolResult existing di codebase.

6. Approval Mapping

Jika Windows Bridge mengirim:

approval.request

Map ke existing approval system jika aman.

Cari existing approval flow:

ToolExecutor confirmation callback
LocalIntelligenceService inline approval
ToolCallCard approval metadata

Jika terlalu berisiko untuk dihubungkan penuh di Phase 3, jangan paksa.

Minimal Phase 3:

- menerima ApprovalRequest
- emit event ke bridge event stream
- log safe summary
- pending tool-call menunggu sampai approval accepted/rejected/result/error

Jika integrasi UI approval butuh perubahan besar, tandai TODO untuk Phase 6.

Jangan bikin dialog UI baru di Phase 3 kecuali sangat kecil dan aman.

7. Tool Definition Integration

Cari bagaimana existing local tools didaftarkan ke model.

Kemungkinan area:

tools/ToolExecutor.kt
tools/ToolDefinition
data/repository/AiRepository.kt
data/remote/api/AiToolDefinition
MCP tool merging

Integrasikan bridge tools dengan cara paling minim.

Opsi aman:

- Tambahkan provider/registry bridge tools terpisah.
- Existing ToolExecutor bisa mengambil tool definitions tambahan jika bridge connected.
- Jangan ubah struktur besar ToolExecutor.

Jika ToolExecutor saat ini sulit disentuh, buat adapter:

BridgeToolProvider

Lalu expose method:

getAvailableBridgeTools(): List<ToolDefinition>
executeBridgeTool(name, args): ToolResult

Jangan langsung merge semua advanced tools ke model kalau belum ada Windows bridge.

Untuk Phase 3, bridge tools boleh hanya tersedia saat:

WindowsBridgeConnectionState.CONNECTED
atau
WindowsBridgeConnectionState.PAUSED untuk resume/cancel only
8. Availability / Feature Flag

Tambahkan availability check.

Bridge tools harus hidden/disabled jika:

- WindowsBridgeSessionClient belum connected
- sessionId belum tersedia
- device belum paired
- tool disabled by phase

Boleh buat:

data class WindowsBridgeToolAvailability(
    val isConnected: Boolean,
    val sessionId: String?,
    val enabledTools: Set<String>,
    val reasonIfUnavailable: String?
)

Jangan memaksa UI setting dulu.

9. Minimal Integration Target

Phase 3 dianggap selesai jika secara internal Android bisa:

1. Membuat BridgeToolCall dari tool request.
2. Mengirim tool.call lewat WindowsBridgeSessionClient.
3. Menerima tool.result/tool.error.
4. Menyelesaikan pending call.
5. Mengubah result/error menjadi existing ToolResult.
6. Menyediakan bridge tool definitions ke agent/tool registry secara controlled.
7. Tidak mengganggu local tool, Antigravity, browser operator, memory/persona/skill.

Tidak perlu actual Windows Bridge server berjalan untuk build.

Jika ingin membuat fake/local test hook, boleh buat minimal mock handler, tapi jangan tambahkan runtime UI besar.

10. Constraints

Wajib:

- Jangan buat Electron.
- Jangan buat native helper.
- Jangan buat Windows executor.
- Jangan ubah Antigravity RemoteSessionClient.
- Jangan ubah Antigravity event protocol.
- Jangan ubah browser operator Android.
- Jangan ubah memory/persona/skills/self-improvement.
- Jangan refactor besar ToolExecutor jika tidak wajib.
- Jangan expose HIGH-risk tools secara default.
- Jangan log full payload sensitif.
- Jangan crash jika bridge offline.

Boleh:

- Tambah package impl/bridge/windows/tools.
- Tambah bridge tool registry.
- Tambah bridge tool executor adapter.
- Tambah mapper result/error.
- Tambah integration point kecil ke existing ToolExecutor jika diperlukan.
- Tambah README docs Phase 3.
- Tambah unit test ringan kalau infra memungkinkan.
11. Safety Defaults

Default Phase 3:

Enabled:
- screen.capture
- window.list

Optional enabled if Agent Control concept already exists:
- mouse.click
- keyboard.type
- keyboard.hotkey
- clipboard.write

Disabled:
- shell.run
- file.write
- file.delete
- clipboard.read
- browser.type
- browser.click

Jika belum ada Agent Control state, jangan aktifkan mouse/keyboard default.