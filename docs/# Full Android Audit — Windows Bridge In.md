# Full Android Audit — Windows Bridge Integration, Folder Structure, Flow, Logic Placement, and Redundancy

## Context

Saya sedang membangun aplikasi Android AI assistant bernama **Amaya**.

Beberapa fase terakhir kita baru saja menambahkan fitur **Windows Bridge**.

Tujuan besar Windows Bridge:

```txt
Android AI Agent
→ menjadi planner/controller
→ connect ke Windows Bridge di PC Windows
→ Windows Bridge menjadi remote executor
→ Android tetap menjadi chat UI, approval UI, memory/context owner, dan agent controller
````

Yang sudah dibuat sejauh ini di Android:

```txt
- Shared bridge protocol di domain/bridge
- WindowsBridgeSessionClient
- WindowsBridgeController
- WindowsBridgeToolProvider / Executor / Registry / Mapper
- Bridge tools masuk ke agent path
- WindowsBridgeActivity / WindowsBridgeScreen
- ChatScreen integration banner
- Agent Control toggle
- Emergency Stop
- Approval card
- Pairing payload parser
- Saved Windows Bridge profiles
```

Sekarang saya ingin **audit total Android-side saja**.

Jangan audit Electron/Windows Bridge folder dulu.

Fokus hanya Android codebase:

```txt
app/src/main/java/com/amaya/intelligence/
```

---

# Main Goal

Lakukan audit menyeluruh untuk memastikan integrasi Windows Bridge di Android:

```txt
1. Tidak salah penempatan folder/file.
2. Tidak membuat logic redundant.
3. Tidak mencampur local, remote IDE, MCP, dan Windows Bridge secara mentah.
4. Tidak membuat God Class.
5. Tidak duplicate ChatScreen / ChatViewModel / ToolExecutor.
6. Reuse shared/existing logic jika ada.
7. Menjaga boundary sesuai arsitektur awal project.
8. Mengidentifikasi bagian yang perlu dirapikan sebelum lanjut fase berikutnya.
```

---

# Very Important

Jangan langsung edit file.

Audit dulu.

Gunakan `git diff` untuk melihat perubahan Android terbaru yang muncul dari fitur Windows Bridge.

Wajib cek:

```bash
git status
git diff -- app/src/main/java/com/amaya/intelligence
git diff -- app/src/main/AndroidManifest.xml
git diff -- app/build.gradle.kts
```

Jika ada perubahan Android lain di luar path tersebut, cek juga bila relevan.

Jangan hanya baca file final. Bandingkan dengan diff agar tahu logic baru masuk ke mana.

---

# Audit Scope

## Android only

Audit area:

```txt
app/src/main/java/com/amaya/intelligence/
app/src/main/AndroidManifest.xml
app/build.gradle.kts
```

Jangan fokus ke:

```txt
windows-bridge/
amaya-remote-extension/
```

Boleh melihat `windows-bridge/` hanya jika perlu memahami protocol, tapi jangan audit detail Electron di task ini.

---

# Expected Architecture

Boundary yang saya inginkan:

```txt
domain/bridge/
  = protocol/model murni
  = tidak boleh import Android UI, Compose, Activity, Hilt, Room, Context

impl/bridge/windows/
  = Android runtime bridge implementation
  = client, controller, executor, provider, pairing/profile store

impl/bridge/windows/tools/
  = Windows Bridge tool definitions, registry, mapper, executor

impl/bridge/windows/pairing/
  = pairing payload parser, saved profile model/store, pairing manager if any

ui/screens/remote/
  = Windows Bridge setup/control screen

ui/components/remote/
  = reusable Compose components for bridge UI

ui/viewmodels/
  = ViewModel / UI state adapter

ui/screens/chat/
  = existing ChatScreen integration only
  = do not duplicate chat screen

tools/
  = existing local Android tools
  = do not mix Windows executor logic here unless adapter is intentionally routed

data/repository/
  = agent loop / context orchestration
  = should only append bridge tool definitions safely

data/remote/mcp/
  = MCP execution
  = should not become Windows Bridge logic host
```

---

# Part A — Workspace / Folder Tree Audit

Buat tree Android terbaru.

Output:

```txt
app/src/main/java/com/amaya/intelligence/
├─ domain/
├─ impl/
├─ tools/
├─ ui/
├─ data/
├─ di/
...
```

Untuk setiap folder yang terkait Windows Bridge, jelaskan:

```txt
Folder:
Purpose:
Layer:
Correct placement? yes/no/partial
Problem:
Recommendation:
```

Fokus khusus:

```txt
domain/bridge/
impl/bridge/windows/
impl/bridge/windows/tools/
impl/bridge/windows/pairing/
ui/screens/remote/
ui/components/remote/
ui/screens/chat/
ui/viewmodels/
data/repository/
data/remote/mcp/
tools/
di/
```

---

# Part B — Git Diff Audit

Wajib gunakan `git diff`.

Cari semua file Android yang berubah karena fitur Windows Bridge.

Buat tabel:

| File | Change Type | What Changed | Layer | Correct Placement? | Risk |
| ---- | ----------- | ------------ | ----- | ------------------ | ---- |

Change Type:

```txt
CREATE
MODIFY
DELETE
MOVE
```

Layer:

```txt
domain
impl
tool-executor
data/repository
ui/screen
ui/component
viewmodel
manifest
di
other
```

---

# Part C — Flow Audit

Audit flow Android sekarang.

## 1. Connect / Pairing Flow

Jelaskan flow:

```txt
WindowsBridgeActivity / WindowsBridgeScreen
→ WindowsBridgeViewModel
→ WindowsBridgeProfileStore / PairingPayload parser
→ WindowsBridgeController.connect()
→ WindowsBridgeSessionClient
→ device.paired / session.created
→ save profile
→ update UI
```

Audit:

```txt
- apakah token disimpan?
- apakah deviceId stabil?
- apakah reconnect saved profile masuk akal?
- apakah profile store ditempatkan benar?
- apakah parser pairing payload aman dan never throws?
```

## 2. Agent Control Flow

Jelaskan flow:

```txt
ChatScreen / WindowsBridgeScreen
→ toggle Agent Control
→ confirmation dialog
→ WindowsBridgeController.setAgentControlEnabled()
→ WindowsBridgeSessionClient.sendAgentControlStatus()
→ Windows state update
→ available tools update
```

Audit:

```txt
- apakah Agent Control default OFF?
- apakah ON butuh confirmation?
- apakah OFF langsung aman?
- apakah tools MEDIUM hidden saat OFF?
- apakah Windows dan Android state bisa desync?
```

## 3. Emergency Stop Flow

Jelaskan flow:

```txt
ChatScreen banner / WindowsBridgeScreen
→ emergencyStop()
→ WindowsBridgeController.emergencyStop()
→ agent.cancelled / paused envelope
→ Windows denies input tools
→ Android updates UI paused
```

Audit:

```txt
- apakah benar-benar kirim signal ke Windows?
- apakah pending approval dibersihkan?
- apakah Agent Control dimatikan?
- apakah UI state paused jelas?
```

## 4. Tool Definition Flow

Jelaskan flow:

```txt
AiRepository.buildToolDefinitions()
→ local tools
→ MCP tools
→ WindowsBridgeToolProvider.getAvailableBridgeTools()
→ model sees bridge tools only when available
```

Audit:

```txt
- apakah local tools tetap jalan?
- apakah bridge tools tidak muncul saat disconnected?
- apakah HIGH-risk tools tidak kebuka default?
- apakah file/shell tools kalau ada sudah approval-gated?
```

## 5. Tool Execution Flow

Jelaskan flow:

```txt
Model emits tool call
→ McpToolExecutor.execute()
→ if mcp__* = MCP
→ if bridge tool = WindowsBridgeToolProvider.executeBridgeTool()
→ else local ToolExecutor.execute()
```

Audit:

```txt
- apakah routing jelas?
- apakah bridge tool hallucination offline menghasilkan error aman?
- apakah ToolResult mapping konsisten?
- apakah Windows tool-call punya target/origin metadata?
```

## 6. ChatScreen Integration Flow

Jelaskan flow:

```txt
LocalChatScreen / ChatScreen existing
→ WindowsBridgeChatPanelViewModel
→ WindowsBridgeConnectionBanner
→ approval card
→ View Screen / Agent Control / Stop callbacks
```

Audit:

```txt
- apakah ChatScreen tidak diduplikasi?
- apakah ChatViewModel tidak diambil alih?
- apakah input composer tetap existing?
- apakah banner tidak merusak layout?
- apakah approval card pakai pattern existing?
```

---

# Part D — Redundancy Audit

Cari redundansi antara:

```txt
Local tools
MCP tools
Remote IDE tools
Windows Bridge tools
Local approval
Remote approval
Windows Bridge approval
Local chat state
Remote chat state
Windows Bridge UI state
```

Buat tabel:

| Redundancy | Location A | Location B | Problem | Recommendation |
| ---------- | ---------- | ---------- | ------- | -------------- |

Wajib cek:

```txt
- ToolExecutor vs WindowsBridgeToolExecutor
- McpToolExecutor vs WindowsBridgeToolProvider branch
- LocalToolMapper vs WindowsBridgeToolMapper
- ToolUiMapper mapping local/remote/windows
- ConfirmationRequest local vs ApprovalRequest bridge
- ChatViewModel confirmation path vs WindowsBridge approval path
- RemoteSessionClient Antigravity vs WindowsBridgeSessionClient
- RemoteSessionScreen provider cards vs WindowsBridgeActivity
- WindowsBridgeViewModel vs WindowsBridgeChatPanelViewModel
- WindowsBridgeUiState vs WindowsBridgeChatUiState
```

Tentukan:

```txt
Keep separate
Merge helper only
Refactor later
Deprecated
Bug
```

Jangan asal gabung kalau tanggung jawab beda.

---

# Part E — Shared Logic Reuse Audit

Cari existing shared/common logic yang bisa dipakai ulang.

Fokus:

```txt
impl/common/mappers/
domain/models/
domain/security/
tools/ToolResult.kt
tools/ToolDefinition
ToolUiMapper
ToolUiMetadata
existing permission/confirmation UI
existing ChatScreen components
existing remote components
existing storage/settings pattern
```

Buat tabel:

| Existing Logic | Current Use | Can Reuse for Bridge? | Should Reuse? | Why |
| -------------- | ----------- | --------------------: | ------------: | --- |

Contoh kemungkinan:

```txt
ToolUiMapper
→ yes, for bridge labels

ToolCallCard
→ yes, for bridge tool-call UI

ConfirmationRequest
→ maybe, but bridge ApprovalRequest has session/toolCall/risk/expiresAt

CommandValidator
→ maybe concept only, Android-specific implementation should not be reused for Windows paths

RemoteSessionClient
→ concept only, do not reuse code directly

ChatScreen
→ yes, integration only; do not duplicate

LocalChatActivity
→ maybe start existing chat after bridge connect
```

---

# Part F — OOP / Responsibility Audit

Audit setiap class baru Windows Bridge Android.

Buat tabel:

| Class | Responsibility | Too Much? | Should Move? | Notes |
| ----- | -------------- | --------: | -----------: | ----- |

Wajib cek:

```txt
WindowsBridgeController
WindowsBridgeSessionClient
WindowsBridgeEnvelopeMapper
WindowsBridgeToolProvider
WindowsBridgeToolExecutor
WindowsBridgeToolRegistry
WindowsBridgeToolMapper
WindowsBridgeToolResultMapper
WindowsBridgeApprovalMapper
WindowsBridgeViewModel
WindowsBridgeChatPanelViewModel
WindowsBridgeUiState
WindowsBridgeChatUiState
WindowsBridgeActivity
WindowsBridgeScreen
WindowsBridgeConnectionBanner
WindowsBridgeApprovalCard
WindowsBridgeAgentControlDialog
WindowsBridgeProfileStore
WindowsBridgePairingPayload
WindowsBridgeProfile
```

Rule:

```txt
Controller:
- runtime bridge facade only
- no Compose
- no screen form state

ViewModel:
- UI state adapter only
- no raw WebSocket parsing

Composable:
- stateless as much as possible
- callbacks only

ToolProvider:
- expose/execute bridge tools only
- no UI navigation

ProfileStore:
- persistence only
- no connect logic

PairingPayload:
- parse/validate only
- no network call
```

---

# Part G — Dependency / DI Audit

Audit Hilt/module wiring.

Cari:

```txt
@Inject
@Singleton
@HiltViewModel
@Module
@Provides
```

Cek:

```txt
- apakah WindowsBridgeController singleton?
- apakah WindowsBridgeSessionClient lifecycle aman?
- apakah WindowsBridgeToolProvider injected dengan benar?
- apakah ViewModel tidak membuat dependency manual yang harusnya injected?
- apakah Activity registered di Manifest?
- apakah ada memory leak dari StateFlow/SharedFlow subscription?
- apakah ada coroutine scope yang tidak dibatalkan?
```

Output:

| DI Area | Status | Issue | Recommendation |
| ------- | ------ | ----- | -------------- |

---

# Part H — UI Placement Audit

Cek apakah UI diletakkan di tempat yang benar.

Wajib audit:

```txt
WindowsBridgeActivity
WindowsBridgeScreen
WindowsBridgeConnectionBanner
WindowsBridgeApprovalCard
WindowsBridgeAgentControlDialog
RemoteSessionScreen entry card
LocalChatScreen wrapper / banner insertion
ToolCallCard changes
```

Pertanyaan:

```txt
- Apakah WindowsBridgeActivity hanya setup/control panel?
- Apakah ChatScreen tetap pusat percakapan?
- Apakah banner kecil dan tidak intrusive?
- Apakah approval UI tidak duplicate terlalu banyak?
- Apakah View Screen membuka WindowsBridgeActivity tanpa membuat loop?
- Apakah Start Chat kembali ke ChatScreen dengan benar?
```

---

# Part I — Safety Audit

Audit safety Android-side.

Cek:

```txt
- Agent Control default OFF
- confirmation dialog sebelum ON
- emergency stop real signal
- pending approval clear saat disconnect
- token tidak disimpan plaintext
- pairing payload expired ditolak
- bridge tools hidden saat disconnected
- MEDIUM tools hidden saat Agent Control OFF
- HIGH-risk tools approval required
- user-facing error tidak expose raw payload
- imageBase64 tidak masuk log UI
- typed text tidak tampil di approval/audit
```

Output:

| Safety Check | Pass/Fail/Unknown | Evidence | Recommendation |
| ------------ | ----------------- | -------- | -------------- |

---

# Part J — Build / Compile / Regression

Jalankan:

```bash
./gradlew assembleDebug
```

Jika terlalu berat, minimal:

```bash
./gradlew compileDebugKotlin
```

Tapi preferred:

```bash
./gradlew assembleDebug
```

Laporkan:

```txt
Build result:
Command:
Output summary:
Errors/warnings:
```

---

# Part K — Output Format

Berikan hasil audit dalam format berikut.

## 1. Executive Summary

Jawab singkat:

```txt
- Apakah integrasi Android Windows Bridge sehat?
- Apa masalah terbesar?
- Apa yang harus dipatch sebelum lanjut?
```

## 2. Android Folder / File Tree Review

Tree + komentar.

## 3. Git Diff Summary

Tabel file yang berubah.

## 4. Flow Review

Subsection:

```txt
- Pairing/connect flow
- Agent Control flow
- Emergency Stop flow
- Tool definition flow
- Tool execution flow
- ChatScreen integration flow
```

## 5. Redundancy Findings

Tabel.

## 6. Shared Logic Reuse Opportunities

Tabel.

## 7. OOP Responsibility Review

Tabel.

## 8. DI / Lifecycle Review

Tabel.

## 9. UI Placement Review

Tabel.

## 10. Safety Review

Tabel.

## 11. Recommended Refactor / Patch Plan

Pisahkan:

```txt
Must fix now
Should fix soon
Can defer
Do not change
```

## 12. Files Recommended to Modify

```txt
CREATE:
- path — reason

MODIFY:
- path — reason

DEPRECATE:
- path — reason
```

## 13. Build Result

Command + result.

---

# Rules

Wajib:

```txt
- Jangan langsung edit file.
- Jangan refactor dulu.
- Jangan hapus logic lama.
- Jangan gabungkan local/remote/windows kalau tanggung jawabnya beda.
- Jangan membuat abstraction baru sebelum menjelaskan masalahnya.
- Jangan audit Electron detail.
- Fokus Android.
- Gunakan git diff untuk perubahan Android.
- Jika tidak yakin, tulis UNKNOWN.
```

Boleh:

```txt
- Memberi rekomendasi patch.
- Memberi rekomendasi struktur folder.
- Menandai code smell.
- Menandai redundancy.
- Menandai potential bug.
- Menandai file yang terlalu besar / class terlalu banyak tanggung jawab.
```

---

# Final Purpose

Audit ini harus menjawab:

```txt
Apakah Android-side Windows Bridge sudah ditempatkan dengan benar?
Apakah local/remote/MCP/Windows Bridge flow sudah terpisah sehat?
Apakah ada logic redundant yang harus digabung sebagai helper?
Apakah ada shared logic existing yang seharusnya dipakai?
Apakah ChatScreen integration sudah benar?
Apakah kita aman lanjut ke file/shell tools atau perlu patch dulu?
```

```
```
