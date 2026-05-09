# Phase 2 — Android Windows Bridge Client  ## Context  Phase 1 sudah selesai dan build berhasil.  Sudah dibuat package baru:  ```txt app/src/main/java/com/amaya/intelligence/domain/bridge/

Isinya:

BridgeEnvelope.kt BridgeMessageType.kt BridgeToolCall.kt BridgeToolResult.kt BridgeError.kt BridgeApproval.kt BridgeRiskPolicy.kt BridgeSessionState.kt BridgeAuditEvent.kt BridgeToolNames.kt README.md

Semua model sudah compile dan tidak import Android UI/Compose/Hilt/Room.

Sekarang lanjut ke Phase 2.

Product Vision Reminder

Amaya adalah AI assistant Android.

Target Windows Bridge:

Android AI Agent → connect ke Windows Bridge → kirim BridgeEnvelope/tool.call → Windows Bridge execute → Windows kirim tool.result/tool.error/screen.frame/approval.request → Android update state → AI lanjut reasoning

Android tetap:

AI planner chat UI approval UI memory/context owner session controller

Windows Bridge tetap:

remote executor permission gate audit logger screen/input/file/shell/browser host

Phase 2 Goal

Buat Android-side client untuk koneksi ke Windows Bridge.

Jangan implement Electron. Jangan implement native helper. Jangan implement actual Windows executor. Jangan integrasi penuh ke AI agent dulu. Jangan ubah local chat flow. Jangan ubah Antigravity remote flow. Jangan ubah browser operator. Jangan ubah memory/persona/skill/self-improvement.

Phase 2 hanya membuat:

Android Windows Bridge Client + event handler dasar + connection state + send/receive BridgeEnvelope + parser aman + reconnect/close/cancel basic

Required New Package

Buat package baru:

app/src/main/java/com/amaya/intelligence/impl/bridge/windows/

File yang disarankan:

WindowsBridgeSessionClient.kt WindowsBridgeConnectionState.kt WindowsBridgeEventHandler.kt WindowsBridgeClientConfig.kt WindowsBridgeClientEvent.kt WindowsBridgeEnvelopeMapper.kt WindowsBridgeLogger.kt README.md

Kalau nama/struktur existing codebase punya pola lain, ikuti pola existing yang paling dekat, tapi jangan campur ke Antigravity package.

Important Rule

Jangan reuse langsung:

impl/ide/antigravity/client/RemoteSessionClient.kt

Boleh baca dan jadikan referensi untuk:

WebSocket lifecycle reconnect seq handling event parsing command queue

Tapi Windows Bridge harus punya client sendiri, karena protocol-nya beda dan tidak boleh Antigravity-specific.

1. WindowsBridgeSessionClient

Buat client Android yang bisa:

connect(host, port, token?) disconnect() sendEnvelope(envelope) sendToolCall(toolCall) sendApprovalDecision(decision) pauseSession() resumeSession() cancelSession() closeSession()

Client harus expose state minimal via Flow/StateFlow jika sesuai pola project:

DISCONNECTED CONNECTING CONNECTED RECONNECTING PAUSED CLOSING ERROR

Gunakan WebSocket library yang sudah ada di project jika memungkinkan. Jangan tambah dependency baru kecuali benar-benar wajib.

2. Incoming Message Handling

Client harus bisa menerima JSON dari Windows Bridge dalam bentuk BridgeEnvelope.

Handle message type:

session.created session.closed device.paired device.disconnected  screen.frame screen.capture_result  tool.result tool.error  agent.status agent.step agent.paused agent.resumed agent.cancelled  approval.request  audit.event error

Untuk Phase 2, cukup parse dan emit sebagai event. Jangan render UI dulu.

Buat sealed class:

sealed class WindowsBridgeClientEvent {     data class Connected(...)     data class Disconnected(...)     data class EnvelopeReceived(...)     data class ToolResultReceived(...)     data class ToolErrorReceived(...)     data class ApprovalRequestReceived(...)     data class ScreenFrameReceived(...)     data class AuditEventReceived(...)     data class Error(...) }

Sesuaikan dengan style codebase.

3. Outgoing Message Handling

Client harus bisa mengirim:

tool.call approval.accepted approval.rejected agent.paused agent.resumed agent.cancelled session.closed

Gunakan BridgeEnvelope.

Pastikan setiap outgoing envelope punya:

id type sessionId deviceId seq timestamp payload metadata

Tambahkan helper untuk generate:

message id seq number timestamp

4. JSON / Mapper Layer

Karena Phase 1 memakai Map<String, Any?> untuk payload, Phase 2 wajib membuat mapper yang aman.

Buat helper:

WindowsBridgeEnvelopeMapper

Tugas:

encode BridgeEnvelope -> JSON string decode JSON string -> BridgeEnvelope decode payload -> typed model jika message type dikenal fallback unknown payload -> raw map

Wajib handle error:

unknown message type missing required field invalid payload invalid seq invalid timestamp malformed JSON

Jangan crash app karena message bridge rusak.

5. Seq / Session Handling

Tambahkan basic seq handling:

outgoingSeq++ lastIncomingSeq ignore duplicate seq jika masuk berulang log gap jika seq lompat

Jangan over-engineering ack/replay dulu. Cukup basic.

6. Connection Config

Buat config model:

data class WindowsBridgeClientConfig(     val host: String,     val port: Int,     val token: String? = null,     val deviceId: String,     val sessionId: String? = null,     val reconnectEnabled: Boolean = true,     val reconnectMaxAttempts: Int = 5 )

Sesuaikan jika codebase punya config pattern lain.

7. Pairing Scope

Untuk Phase 2, pairing belum perlu full QR.

Cukup siapkan field/protocol:

token deviceId sessionId device.paired device.disconnected

Jangan implement UI QR dulu.

8. Logging

Tambahkan logger ringan.

Log minimal:

connect requested connected disconnected reconnect attempt incoming envelope type outgoing envelope type parse error protocol error session closed

Jangan log payload sensitif full. Untuk payload, log type/tool/id saja.

9. Tests / Safety Check

Jika test infra mudah, tambahkan test ringan untuk:

BridgeMessageType.fromWireName() encode/decode BridgeEnvelope unknown message type handling BridgeRiskPolicy helper

Kalau test infra belum jelas, minimal buat kode rapi dan compile.

Constraints

Wajib:

- Jangan ubah local agent flow. - Jangan ubah Antigravity remote flow. - Jangan ubah browser operator. - Jangan ubah memory/persona/skill/self-improvement. - Jangan ubah UI. - Jangan buat Electron. - Jangan buat native helper. - Jangan buat Windows executor. - Jangan tambah dependency besar. - Jangan pindahkan model existing. - Jangan delete/rename file existing.

Boleh:

- Tambah package impl/bridge/windows. - Tambah mapper/helper. - Tambah README docs untuk Phase 2. - Tambah test ringan. - Pakai WebSocket dependency existing.

Final Direction

Phase 2 bukan membuat bridge selesai.

Phase 2 hanya membuat Android siap berbicara dengan Windows Bridge memakai protocol Phase 1.

Setelah Phase 2 selesai, Phase 3 baru integrasi ke agent Android sebagai remote tool executor.