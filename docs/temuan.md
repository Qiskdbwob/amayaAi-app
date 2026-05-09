# Temuan Audit Codebase Amaya untuk Rencana Windows Bridge

Tanggal audit: 2026-05-09  
Workspace: `C:/Users/BiuBiu/Documents/my app/amaya`  
Status: **audit dan rencana saja**. Tidak ada perubahan kode aplikasi, tidak ada refactor, tidak ada overwrite logic lama.

---

## 1. Workspace Overview

### Struktur project nyata

```txt
root/
├─ AGENTS.md
├─ README.md
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/
├─ docs/
│  ├─ android-browser-use-toolcall.md
│  ├─ browser-toolcall-schema.ts
│  ├─ codex-auth.md
│  ├─ codex-auth-implementation.md
│  ├─ models.md
│  ├─ chat-screen-ios-mica-guidelines.md
│  └─ settings-screen-ios-grouped-guidelines.md
├─ app/
│  ├─ AGENTS.md
│  ├─ build.gradle.kts
│  ├─ schemas/
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/
│     ├─ java/com/amaya/intelligence/
│     │  ├─ AmayaApplication.kt
│     │  ├─ data/
│     │  │  ├─ local/
│     │  │  │  ├─ dao/
│     │  │  │  ├─ db/
│     │  │  │  ├─ entity/
│     │  │  │  └─ files/
│     │  │  ├─ remote/
│     │  │  │  ├─ api/
│     │  │  │  └─ mcp/
│     │  │  └─ repository/
│     │  ├─ di/
│     │  ├─ domain/
│     │  │  ├─ ai/
│     │  │  ├─ memory/
│     │  │  ├─ models/
│     │  │  ├─ security/
│     │  │  └─ skills/
│     │  ├─ impl/
│     │  │  ├─ common/
│     │  │  ├─ ide/
│     │  │  │  ├─ antigravity/
│     │  │  │  ├─ cursor/
│     │  │  │  └─ windsurf/
│     │  │  └─ local/
│     │  │     ├─ browser/
│     │  │     └─ tools/
│     │  ├─ service/
│     │  ├─ tools/
│     │  ├─ ui/
│     │  │  ├─ activities/
│     │  │  ├─ components/
│     │  │  │  ├─ local/
│     │  │  │  ├─ remote/
│     │  │  │  └─ shared/
│     │  │  ├─ screens/
│     │  │  │  ├─ agent/
│     │  │  │  ├─ amaya/
│     │  │  │  ├─ browser/
│     │  │  │  ├─ chat/
│     │  │  │  ├─ cronjob/
│     │  │  │  ├─ mcp/
│     │  │  │  ├─ persona/
│     │  │  │  ├─ project/
│     │  │  │  ├─ remote/
│     │  │  │  ├─ selfimprovement/
│     │  │  │  └─ settings/
│     │  │  ├─ theme/
│     │  │  └─ viewmodels/
│     │  ├─ util/
│     │  └─ utils/
│     ├─ res/
│     ├─ res-agent/
│     └─ res-remote/
└─ amaya-remote-extension/
   ├─ AGENTS.md
   ├─ package.json
   ├─ tsconfig.json
   ├─ src/
   │  ├─ extension.ts
   │  ├─ connectivity/
   │  ├─ controllers/
   │  │  └─ support/
   │  ├─ ide/
   │  │  ├─ antigravity/
   │  │  └─ stub/
   │  ├─ interfaces/
   │  ├─ types/
   │  └─ utils/
   └─ test/
```

### Tech stack terdeteksi

| Area | Stack | Catatan |
|---|---|---|
| Android app | Kotlin, Android SDK, Jetpack Compose, Hilt, Room, WorkManager, DataStore, Moshi, OkHttp/Retrofit/SSE, Java-WebSocket | Module Gradle hanya `:app`; belum KMP. |
| Local AI provider | Kotlin providers untuk OpenAI-compatible, Anthropic, Gemini | Model/provider abstraction ada di `data/remote/api`. |
| Local persistence | Room + file-backed JSONL repositories | Room untuk conversation/catalog/project; file-backed untuk session recall/skills. |
| Remote IDE bridge | TypeScript VS Code extension, `ws`, VS Code API | Bridge ke Antigravity IDE; bukan Electron/native helper. |
| Browser automation Android | WebView + custom controller/session manager | Parent tool `browser` dengan nested sub-tools. |
| Protocol docs | TypeScript schema doc untuk browser tool | `docs/browser-toolcall-schema.ts` adalah referensi, bukan generated shared package. |

### Module utama dan fungsi

| Folder/module | Fungsi | Jenis | Dependency utama | Bisa dipakai Windows Bridge? | Risiko coupling |
|---|---|---|---|---|---|
| `app/` | Android app utama: UI, AI runtime lokal, remote session client, persistence, tools. | Android-only dengan sebagian domain reusable secara konsep | Android SDK, Compose, Hilt, Room | Sebagian konsep/model bisa dipakai; kode langsung tidak cross-platform | Tinggi jika bridge Windows langsung memakai package Android. |
| `app/src/main/java/com/amaya/intelligence/domain/` | Kontrak service, state chat, memory/skills/security domain. | Pseudo-shared, tapi masih ada coupling Android/data layer | Kotlin, sebagian Compose/data remote/tools | Sebagian besar konsep cocok untuk shared; perlu refactor dependency | `ChatModels.kt` import `data.remote.api.MessageRole`, `TodoItem`, `IntelligenceSessionManager`; `IdeConfig.kt` import Compose `ImageVector`. |
| `app/src/main/java/com/amaya/intelligence/data/remote/api/` | AI provider abstraction, DTO chat/function call, provider registry/settings. | Backend-like Android data layer | OkHttp, Moshi, Android DataStore/EncryptedSharedPreferences | DTO chat/tool-call sebagian reusable; manager/settings Android-only | `ChatMessage`, `ToolCallMessage`, `AiToolDefinition` berada di data layer, bukan shared protocol. |
| `app/src/main/java/com/amaya/intelligence/data/repository/` | Orkestrasi agent loop, context, memory, skill, session, model catalog. | Android/backend-like | Repos, tools, DB/file stores | Flow agent dan context engineering bisa jadi referensi; implementasi Android-only | `AgentEvent` berada dalam `AiRepository.kt`, bukan domain/protocol. |
| `app/src/main/java/com/amaya/intelligence/tools/` | Tool registry dan executor lokal: file, shell, browser, memory, skills, todo, reminders, subagents. | Android local runtime | Android filesystem/process APIs, repos | Schema/kontrak tool bisa dipakai; executor Android tidak | Registry campur schema, executor, confirmation, provider mapping. |
| `app/src/main/java/com/amaya/intelligence/domain/security/` | Command/path validator, risk level, validation result. | Pseudo-shared Android | Android Context | Konsep risk/policy sangat reusable; kode perlu dipisah dari Android path rules | Saat ini risk level belum punya `BLOCKED` enum eksplisit, dan validator Android-specific. |
| `app/src/main/java/com/amaya/intelligence/impl/local/browser/` | Browser operator Android WebView: controller, DOM inspector, session/pause/resume/safety. | Android-only runtime | WebView, Android input | Schema/session/safety konsep reusable; executor tidak | Browser state dan response JSON dibuat manual; TS schema docs terpisah. |
| `app/src/main/java/com/amaya/intelligence/impl/ide/antigravity/` | Android client untuk remote VS Code/Antigravity bridge. | Remote implementation | Java-WebSocket, JSON | Transport/event handling sangat relevan untuk Windows Bridge | Protocol hardcoded event names Antigravity/extension. |
| `app/src/main/java/com/amaya/intelligence/ui/` | Compose activities/screens/components/viewmodels. | Android UI-only | Compose, Activity APIs | Approval UI dan remote screen viewer calon lokasi Android-side | Jangan masuk shared. |
| `amaya-remote-extension/` | VS Code extension WebSocket server yang menghubungkan Android ke Antigravity. | Desktop IDE bridge, TypeScript | VS Code API, `ws` | Sangat relevan sebagai referensi transport/event buffer/session; bukan Windows OS bridge | Terikat VS Code/Antigravity, belum permission/audit OS-level. |
| `docs/` | Dokumentasi browser toolcall dan product notes. | Documentation/schema reference | N/A | Bisa jadi dasar schema bridge | Schema belum menjadi single source of truth. |

### Entry point aplikasi

| Entry point | Lokasi | Fungsi |
|---|---|---|
| Android Application | `app/src/main/java/com/amaya/intelligence/AmayaApplication.kt` | Hilt app, WorkManager config, apply theme. |
| Android Launcher | `app/src/main/java/com/amaya/intelligence/ui/MainActivity.kt` | Main Compose app, permission onboarding/navigation. |
| Local chat entry | `ui/activities/chat/local/LocalChatActivity.kt`, `ui/screens/chat/local/LocalChatScreen.kt` | Chat mode lokal. |
| Remote chat/session entry | `ui/activities/remote/RemoteSessionActivity.kt`, `ui/activities/chat/remote/RemoteChatActivity.kt`, `ui/screens/remote/RemoteSessionScreen.kt` | Connect/remote chat/workspace flow. |
| Browser operator | `ui/activities/browser/BrowserOperatorActivity.kt`, `ui/screens/browser/BrowserOperatorScreen.kt` | Fullscreen WebView operator. |
| VS Code extension activation | `amaya-remote-extension/src/extension.ts` | Start/stop WebSocket server, bootstrap IDE client. |

---

## 2. Android Logic Map

| Area | File/Folder | Fungsi | Bisa Dipakai Bridge? | Catatan integrasi |
|---|---|---|---|---|
| Unified AI service contract | `domain/ai/IntelligenceService.kt` | UI berbicara ke satu kontrak untuk local/remote. | Sebagian | Cocok sebagai pola untuk `BridgeIntelligenceService`, tapi contract sekarang chat-centric dan Android `StateFlow`. |
| Session mode | `domain/ai/IntelligenceSessionManager.kt` | Mode `LOCAL`, `ANTIGRAVITY`, `CURSOR`, `WINDSURF`. | Sebagian | Tambah mode `WINDOWS_BRIDGE` nanti; displayName extension saat ini memanggil impl factory dari domain. |
| Chat/message state | `domain/models/ChatModels.kt` | `ChatUiState`, `UiMessage`, `MessageStep`, `ToolExecution`, `ToolStatus`, attachments, project files. | Sebagian besar konsep | Perlu dipindah/duplikasi ke shared murni; saat ini import `MessageRole` dari data layer dan `TodoItem` dari tools. |
| Model/provider abstraction | `data/remote/api/AiProvider.kt`, `OpenAiProvider.kt`, `AnthropicProvider.kt`, `GeminiProvider.kt` | `ChatRequest`, `ChatResponse`, provider streaming/function call. | Sebagian | `ChatMessage`, `ToolCallMessage`, `ToolResultMessage`, `AiToolDefinition` perlu jadi shared/domain, provider tetap Android data. |
| Agent loop lokal | `data/repository/AiRepository.kt` | Full agentic loop: build context, call provider, stream text, parse fallback toolcall, execute tools, feed tool results, self-improvement. | Sebagian | Planner/loop bisa tetap Android agent controller; Windows Bridge sebaiknya menjadi remote tool executor, bukan menggandakan agent loop dulu. |
| Agent events | `data/repository/AiRepository.kt` (`sealed class AgentEvent`) | TextDelta, ToolCallStart, ToolCallResult, Usage, Error, Done, SubagentUpdate. | Ya konsepnya | Lokasi salah untuk shared; pindahkan ke domain/shared protocol jika dipakai bridge. |
| Local service orchestration | `impl/local/LocalIntelligenceService.kt` | Wrap `AiRepository`, maintain UI state, persist conversation, inline approval via tool metadata. | Sebagian | Bagian event-to-UI mapping reusable secara pola; persistence Android/Room tidak. |
| Remote service orchestration | `impl/ide/antigravity/services/AntigravityIntelligenceService.kt` | Wrap WebSocket client, handle remote events, expose `IntelligenceService`. | Ya sebagai pola | Ini kandidat pattern untuk `WindowsBridgeIntelligenceService`, dengan client/protocol baru. |
| Remote transport Android | `impl/ide/antigravity/client/RemoteSessionClient.kt` | Java-WebSocket client, reconnect, seq dedupe, command queue, parse events. | Ya, sangat relevan | Generalize menjadi `BridgeSessionClient` + protocol typed model; jangan reuse nama Antigravity. |
| Remote foreground indicator | `impl/ide/antigravity/client/RemoteSessionForegroundService.kt` | Notification ongoing remote session. | Ya Android-side | Berguna untuk visible session indicator Android; Windows tetap butuh tray indicator sendiri. |
| Remote event handling | `impl/ide/antigravity/services/event/*` | Map `RemoteEvent` ke `ChatUiState`, streaming, tool cards, workspace. | Sebagian | Buat parallel handler untuk bridge events; hindari menambah kondisi Windows ke Antigravity handler. |
| Tool-call UI metadata | `domain/models/ToolUiMetadata.kt`, `impl/common/mappers/ToolUiMapper.kt` | Normalisasi kategori/icon/label tool. | Sebagian | Tool UI mapping bisa mendukung bridge tool names; masih Android UI-oriented icons. |
| Local tool registry/executor | `tools/ToolExecutor.kt`, `ToolResult.kt` | Registry semua tools lokal, validation, confirmation callback. | Sebagian | Kontrak `Tool`, `ToolDefinition`, `ToolResult`, `ConfirmationRequest` bisa jadi shared; executor lokal tetap Android. |
| File tools | `tools/ListFilesTool.kt`, `ReadFileTool.kt`, `WriteFileTool.kt`, `EditFileTool.kt`, `DeleteFileTool.kt`, `FindFilesTool.kt`, `UndoChangeTool.kt` | File operations Android local. | Schema saja | Windows butuh executor native/helper sendiri karena path, permission, symlink, trash berbeda. |
| Shell tool | `tools/RunShellTool.kt` | ProcessBuilder `sh -c`, timeout, output limit, validation. | Schema dan safety pattern | Windows butuh PowerShell/cmd executor + command allow/blocklist Windows. |
| Command/path security | `domain/security/CommandValidator.kt`, `ValidationResult.kt` | Whitelist/blocklist commands, protected Android paths, risk confirmation. | Sebagian besar konsep | Pisahkan policy engine platform-neutral dari platform-specific validators. |
| Approval flow lokal | `ToolExecutor.kt`, `LocalIntelligenceService.awaitInlineToolConfirmation`, `ToolCallCard.kt` | Tool pending status + metadata `approvalRequired`, accept/decline in card. | Ya | Sudah cocok sebagai Android Approval UI foundation untuk HIGH bridge tools; perlu typed `ApprovalRequest`. |
| Approval flow remote IDE | `RemoteSessionClient.RemoteEvent.ConfirmationRequired`, `AntigravityIntelligenceService.respondToToolInteraction`, extension `tool_interaction` | Terminal command approval dari IDE. | Sebagian | Event ada tapi tidak sepenuhnya masuk typed shared model; perlu unify dengan bridge approval. |
| Browser parent tool Android | `tools/BrowserUseToolset.kt`, `impl/local/browser/*`, docs schema | WebView automation with parent `browser` and nested subtools, safety pause. | Konsep/schemas sangat relevan | Windows Bridge browser automation bisa gunakan nama mirip (`browser.*`) tapi executor berbeda (Playwright/CDP/native). |
| Browser safety | `impl/local/browser/SafetyGuard.kt`, `BrowserResponseFormatter.kt` | Sensitive fields, pause, allowed actions. | Ya konsep | Perlu shared safety taxonomy (`password`, `otp`, `payment`) dan site/user approvals. |
| Memory/persona/skill context | `domain/memory/*`, `domain/skills/*`, `data/repository/ContextManager.kt`, `MemoryRepository.kt`, `SkillRepository.kt`, `SelfImprovementPipeline.kt` | Context engineering, durable memory, skills, pending proposals. | Sebagian | Keep Android agent memory centralized; Bridge should receive scoped context only, not own global memory initially. |
| Session recall | `data/repository/SessionMemoryRepository.kt`, `data/local/files/FileSessionStore.kt` | JSONL session messages/toolcalls/summaries/search. | Sebagian | Bridge audit log should be separate; session event model reusable. |
| MCP tools | `data/remote/mcp/McpClientManager.kt`, `McpToolExecutor.kt`, `McpModels.kt` | External MCP tools merged into model tools. | Sebagian | Bridge could later expose Windows tools as MCP, but direct bridge protocol needs stricter permissions/audit. |
| Conversation persistence | `data/local/entity/ConversationEntity.kt`, `ConversationDao.kt`, `LocalIntelligenceService` JSON serialization | Persist local chat messages. | Tidak langsung | Android-only Room. Bridge session logs should be separate tables/entities later. |
| App permissions | `AndroidManifest.xml`, `MainActivity.kt`, `AppViewModel.kt`, `PermissionRequirementSheet.kt` | Android storage/camera/notification/exact alarm permissions. | Android side only | Bridge permissions are product/session permissions, not Android OS runtime permissions. |
| Background reminders | `service/CronJobReceiver.kt`, `ReminderWorker.kt`, `CronJobRepository.kt` | Scheduled reminders and background AI reply. | Tidak | Separate from Windows Bridge. |
| Provider/IDE abstraction | `domain/ai/IdeProvider.kt`, `impl/ide/IdeProviderFactory.kt`, `AntigravityProvider.kt`, stubs Cursor/Windsurf | IDE metadata/plugin pattern. | Sebagian | Good idea, but `IdeInfo` uses Compose `ImageVector` so not shared. |

---

## 3. Shared Component Audit

### Ringkasan penting

Tidak ada module root bernama `shared` atau Kotlin Multiplatform. Yang ada adalah:

1. `app/src/main/java/.../domain/` sebagai pseudo-shared Android internal.
2. `app/src/main/java/.../ui/.../shared/` sebagai shared UI component, bukan shared logic/protocol.
3. `app/src/main/java/.../impl/common/` sebagai mapper bersama local/remote Android.
4. `amaya-remote-extension/src/interfaces` dan `src/controllers/support` sebagai shared TypeScript internal extension.
5. `docs/browser-toolcall-schema.ts` sebagai schema dokumentasi, bukan package runtime.

### Audit tabel

| Shared Component | Fungsi | Dipakai Android? | Bisa Dipakai Windows Bridge? | Masalah | Rekomendasi |
|---|---|---:|---:|---|---|
| `domain/models/ChatModels.kt` | Chat UI/domain models, tool execution state, attachments, project/workspace entries. | Ya | Sebagian | Coupled ke `data.remote.api.MessageRole`, `tools.TodoItem`, session manager. | Ekstrak model message/tool/session minimal ke `shared/protocol` atau `domain/protocol`; UI-specific tetap Android. |
| `domain/models/ToolUiMetadata.kt` | Category/icon metadata untuk rendering tool. | Ya | Sebagian | Ikon/kategori UI bukan protocol murni. | Pertahankan Android UI mapper; shared cukup `toolName`, `risk`, `status`, `displayHint`. |
| `domain/models/ConnectionState.kt` | DISCONNECTED/CONNECTING/CONNECTED. | Ya | Ya | Terlalu minimal, belum ada pairing/auth/reconnecting/paused. | Perlu `BridgeConnectionState` typed yang lebih lengkap. |
| `domain/models/IdeConfig.kt` | IDE capabilities/info. | Ya | Sebagian | Import `ImageVector`, Android Compose. | Pisahkan `RemoteProviderInfo` pure dari UI icon. |
| `domain/ai/IntelligenceService.kt` | Unified UI service contract. | Ya | Sebagian | Chat-centric; `StateFlow`; remote-specific methods no-op. | Tambah service implementasi bridge, bukan jadikan protocol. |
| `domain/security/ValidationResult.kt` | Allowed/RequiresConfirmation/Denied + RiskLevel. | Ya | Ya konsep | Belum ada `BLOCKED` policy level; `ROOT` lebih Android/Linux. | Jadikan `RiskLevel LOW/MEDIUM/HIGH/BLOCKED` + `PermissionDecision`. |
| `domain/security/CommandValidator.kt` | Command/path guardrail. | Ya | Sebagian | Android Context + Android protected paths + shell command assumptions. | Split: `RiskPolicy` shared + `AndroidCommandValidator`/`WindowsCommandValidator`. |
| `domain/memory/*` | Memory proposals/types/safety/classifier/dedupe. | Ya | Sebagian | Good domain, but storage/app policy Android side. | Keep Android agent memory centralized; share only context references/event IDs if bridge needs. |
| `domain/skills/*` | Skill metadata and patch applier. | Ya | Sebagian | Reusable concept, not bridge protocol. | Jangan campur dengan bridge tools; skills tetap agent capability. |
| `data/remote/api/AiProvider.kt` models | `ChatRequest`, `ChatMessage`, `ToolCallMessage`, `ToolResultMessage`, `ChatResponse`, `AiToolDefinition`. | Ya | Sebagian besar | Berada di `data/remote/api`; `MessageRole` dipakai domain. | Pindah/duplikasi minimal ke shared AI protocol; provider implementation tetap data remote. |
| `data/repository/AiRepository.AgentEvent` | Stream event internal agent loop. | Ya | Ya konsep | Nested di repository, bukan shared. | Buat `AgentEvent` shared yang mencakup status/step/thinking/tool. |
| `tools/ToolResult.kt` | `Tool`, `ToolResult`, `ToolVisibility`, `ErrorType`, `FileInfo`. | Ya | Sebagian | Tool interface Android suspend; FileInfo local; visibility hanya `MODEL`. | Ekstrak `ToolResult`, `ToolError`, `ToolDescriptor` shared; executor interface platform-specific. |
| `tools/ToolExecutor.kt` | Registry + execution + confirmation + provider schema conversion. | Ya | Sebagian | Menggabungkan registry schema, security, execution, conversion to AI provider. | Bridge butuh registry terpisah; reusable hanya schema/confirmation pattern. |
| `tools/ConfirmationRequest` | Request confirmation for tool. | Ya | Ya | Berada di tools Android; belum ada timeout/session/audit/requester. | Buat `ApprovalRequest` shared dengan `sessionId`, `toolCallId`, `risk`, `expiresAt`, `reason`, `argsPreview`. |
| `impl/common/mappers/ToolUiMapper.kt` | Tool-to-UI metadata shared local/remote Android. | Ya | Sebagian | Android/UI-focused; Windows tool names belum ada. | Tambah bridge tool mapping di Android UI layer, bukan protocol. |
| `impl/local/tools/LocalToolMapper.kt` + `impl/ide/antigravity/tools/AntigravityToolMapper.kt` | Normalize tool names/args per backend. | Ya | Sebagian | Banyak mapping duplicate. | Buat common normalizer helper pure, tetap backend adapters terpisah. |
| `impl/ide/antigravity/client/RemoteSessionClient.RemoteEvent` | Remote event model parsed from WebSocket. | Ya | Ya, sangat relevan | Model berada dalam client file; event names Antigravity-specific. | Ekstrak bridge-neutral `BridgeEnvelope`/`BridgeEvent` tanpa mengubah Antigravity dulu. |
| `impl/ide/antigravity/client/RemoteSessionClient` | WebSocket transport: reconnect, command queue, seq dedupe. | Ya | Ya konsep | Hardcoded commands/events Antigravity. | Fork/generalize menjadi `BridgeSessionClient` untuk Windows Bridge. |
| `docs/browser-toolcall-schema.ts` | TS schema browser response. | Docs only | Ya | Tidak generated; Kotlin model bisa drift. | Jadikan source-of-truth schema package atau keep docs but add tests. |
| `ui/components/shared/*` | Compose shared UI components: chat input, tool cards, permission sheets, markdown, etc. | Ya | Android-only | Tidak langsung | Reuse for Android Approval UI/Tool Cards only. |
| `amaya-remote-extension/src/interfaces/*` | Provider-neutral IDE interfaces and stream callbacks. | Extension only | Sebagian | IDE-centric, not Windows OS bridge. | Bisa jadi referensi TypeScript interface untuk `windows-bridge`, jangan depend langsung. |
| `amaya-remote-extension/src/controllers/support/EventBuffer.ts` | Per-session/global event buffer with seqId/serverSessionId. | Extension only | Ya | Buffer size 100, no auth/audit/persistence. | Reuse design for bridge transport, add durable audit and auth. |
| `amaya-remote-extension/src/controllers/support/HostWorkspaceService.ts` | Workspace file list/diff/content via VS Code/fs/git. | Extension only | Sebagian | No security allowlist beyond workspace; VS Code-specific. | Windows Bridge file executor should implement allowlist and audit. |

---

## 4. Current Agent Flow

### Local Android agent flow nyata

```txt
User input dari Compose ChatScreen
→ ChatViewModel.sendMessage()
→ active IntelligenceService dari IntelligenceModule
→ LocalIntelligenceService.sendMessage()
→ optimistic UiMessage(USER) masuk ChatUiState
→ persist/create conversation via ConversationDao
→ AiRepository.chat()
   → resolve AgentConfig/provider/model dari AiSettingsManager
   → ContextManager.buildContext()
      → persona, operating rules, memory snapshot, skill index, session recall, tools section
   → build tool definitions dari ToolExecutor + MCP cache
   → provider.chat(ChatRequest) streaming
      → ChatResponse.TextDelta → AgentEvent.TextDelta
      → ChatResponse.ToolCall → AgentEvent.ToolCallStart
      → ChatResponse.Done/Error
   → fallback parser jika model menulis <tool_call> / JSON tool call dalam text
   → execute tool via McpToolExecutor
      → local ToolExecutor atau MCP
      → CommandValidator validate tool/path/command
      → jika RequiresConfirmation: LocalIntelligenceService membuat tool pending metadata
      → user Accept/Decline di ToolCallCard → respondToToolInteraction(toolCallId, bool)
      → tool returns ToolResult
   → AgentEvent.ToolCallResult
   → tool result ditambahkan ke conversation message untuk iterasi berikutnya
   → loop sampai no tool calls / maxIterations
   → save session memory/toolcall
   → AgentEvent.Done
   → SelfImprovementPipeline async analyze interaction
→ LocalIntelligenceService maps AgentEvent ke UiMessage steps/toolExecutions
→ ChatScreen/ToolCallCard render streaming, tools, approval buttons
```

### Remote Antigravity/VS Code flow nyata

```txt
Android RemoteSessionScreen/QR/manual connect
→ ChatViewModel.connect(ip, port)
→ AntigravityIntelligenceService.connect()
→ RemoteSessionClient.connect(ws://ip:port)
→ VS Code extension WebSocket server receives client
→ Android sends { action: 'send_message', data: { content, conversationId, mode, attachments } }
→ MessageHandler/MessageCommandRouter
→ MessageFlowController.handleSendMessage()
   → validates attachments/model quota
   → chooses/creates Antigravity session
   → broadcasts active_conversation/user_message/new_assistant_message
   → api.sendMessage(...)
   → api.streamForResponse(... callbacks ...)
→ StreamOrchestrator callbacks broadcast events:
   state_sync, text_delta, ai_thinking, tool_call_start, tool_call_result,
   tool_activity, stream_done, error, models_list, conversations_list, project_files
→ EventBuffer adds seqId/serverSessionId and buffers for catch-up
→ Android RemoteSessionClient parses JSONObject into RemoteEvent
→ AntigravityEventHandler maps RemoteEvent to ChatUiState/ToolExecution
→ Compose ChatScreen renders remote messages/tools/approval
```

### Current protocol style

Android → extension commands use untyped JSON like:

```json
{ "action": "send_message", "data": { "content": "...", "conversationId": "..." } }
```

Extension → Android events use:

```json
{ "event": "tool_call_start", "data": { "toolCallId": "...", "name": "...", "arguments": {} }, "seqId": 12, "serverSessionId": "..." }
```

This is already close to a bridge protocol but is Antigravity/IDE-oriented, not Windows OS command-oriented.

---

## 5. Proposed Bridge Flow

### Flow baru yang disarankan

```txt
Android user prompt
→ Android agent planner / AiRepository local loop
→ Model decides bridge tool call, e.g. windows tool `mouse.click` or `screen.capture`
→ Android wraps it as shared BridgeMessage/ToolCall
→ Bridge transport WebSocket sends envelope to Windows Bridge
→ Windows Bridge validates session/pairing/auth
→ Windows Bridge PermissionPolicy + RiskPolicy:
   LOW: active session only
   MEDIUM: active Agent Control mode
   HIGH: approval request to Android
   BLOCKED: reject
→ If approval required:
   Windows sends approval.request
   Android shows approval UI
   Android returns approval.accepted/rejected
→ Windows executor runs tool via Electron/native helper
→ Windows writes audit.event
→ Windows returns tool.result/tool.error/screen.frame/status
→ Android converts result to ToolExecution/AgentEvent
→ AI agent continues with observation/result
```

### Bridge protocol design minimal

Saat ini belum ada protocol/message model Windows Bridge. Buat model baru yang tidak mengganti Antigravity protocol dulu.

#### Envelope

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

#### Tool call payload

```json
{
  "type": "tool.call",
  "id": "call_001",
  "sessionId": "session_abc",
  "tool": "mouse.click",
  "args": {
    "x": 720,
    "y": 420,
    "button": "left"
  },
  "requiresApproval": false,
  "risk": "medium",
  "timestamp": 1778320000
}
```

#### Message types yang harus didukung

| Type | Direction | Purpose |
|---|---|---|
| `session.created` | bridge → android | Sesi bridge dibuat/aktif. |
| `session.closed` | both | Sesi selesai/diputus. |
| `device.paired` | bridge → android | Pairing berhasil. |
| `device.disconnected` | bridge → android | Device bridge hilang. |
| `screen.frame` | bridge → android | Frame streaming remote screen. |
| `screen.capture_result` | bridge → android | Screenshot still capture result. |
| `tool.call` | android → bridge | Request eksekusi tool. |
| `tool.result` | bridge → android | Tool sukses. |
| `tool.error` | bridge → android | Tool error/blocked/timeout. |
| `agent.status` | both | Status general agent/session. |
| `agent.step` | both | Step timeline. |
| `agent.thinking` | android → bridge / bridge → android | Optional thinking/status display. |
| `agent.paused` | both | Session paused. |
| `agent.resumed` | both | Session resumed. |
| `agent.cancelled` | both | Emergency stop/cancel. |
| `approval.request` | bridge → android | Approval HIGH/MEDIUM policy. |
| `approval.accepted` | android → bridge | User approve. |
| `approval.rejected` | android → bridge | User reject. |
| `audit.event` | bridge → android/log | Immutable action audit. |
| `error` | both | Protocol/session error. |

### Windows Bridge Tool Registry design

| Tool name | Purpose | Input args | Output result | Risk | Requires approval | Executor location | Reusable shared model |
|---|---|---|---|---|---:|---|---|
| `screen.capture` | Capture current screen/monitor. | `monitorId?`, `format?`, `quality?`, `includeCursor?` | `{ imageBase64, width, height, monitorId }` | LOW | No if session active | Electron/native helper | `ToolCall`, `ToolResult`, `ScreenCaptureResult` |
| `screen.stream.start` | Start remote screen stream. | `monitorId?`, `fps`, `quality`, `maxWidth?` | `{ streamId, fps }` + `screen.frame` events | LOW/MEDIUM | No/Yes depending mode | Electron capture loop | `BridgeSession`, `ScreenFrame` |
| `screen.stream.stop` | Stop remote screen stream. | `streamId` | `{ stopped: true }` | LOW | No | Electron | `ToolResult` |
| `window.list` | List visible windows. | `includeMinimized?`, `includeTitles?` | `{ windows: [{id,title,app,bounds,focused}] }` | LOW | No | Native helper | `WindowInfo` |
| `window.focus` | Focus a window. | `windowId` | `{ focused: true }` | MEDIUM | Agent Control active | Native helper | `WindowInfo` |
| `window.close` | Close a window. | `windowId`, `force?` | `{ closed: true }` | HIGH | Yes | Native helper | `ToolResult` |
| `mouse.move` | Move cursor. | `x`, `y`, `durationMs?` | `{ x, y }` | LOW/MEDIUM | No if Agent Control | Native helper | `PointerAction` |
| `mouse.click` | Click screen coordinate. | `x`, `y`, `button`, `clicks?` | `{ clicked: true }` | MEDIUM | Agent Control active | Native helper | `PointerAction` |
| `mouse.double_click` | Double click. | `x`, `y`, `button?` | `{ clicked: 2 }` | MEDIUM | Agent Control active | Native helper | `PointerAction` |
| `mouse.drag` | Drag from A to B. | `from`, `to`, `durationMs?`, `button?` | `{ dragged: true }` | MEDIUM | Agent Control active | Native helper | `PointerAction` |
| `mouse.scroll` | Scroll. | `x?`, `y?`, `deltaX?`, `deltaY?` | `{ scrolled: true }` | MEDIUM | Agent Control active | Native helper | `PointerAction` |
| `keyboard.type` | Type text. | `text`, `intervalMs?`, `targetWindowId?` | `{ typedChars }` | MEDIUM/HIGH if sensitive/form | Agent Control; approval for sensitive submit | Native helper | `KeyboardAction` |
| `keyboard.press` | Press key. | `key` | `{ pressed: true }` | MEDIUM | Agent Control active | Native helper | `KeyboardAction` |
| `keyboard.hotkey` | Key combination. | `keys: string[]` | `{ pressed: true }` | MEDIUM/HIGH for destructive combos | Maybe | Native helper | `KeyboardAction` |
| `clipboard.read` | Read clipboard text/image metadata. | `format?` | `{ text?, imageBase64? }` | HIGH if contains secrets | Yes or explicit permission | Electron clipboard | `ClipboardResult` |
| `clipboard.write` | Write clipboard. | `text` or `imageBase64` | `{ written: true }` | MEDIUM | Agent Control active | Electron clipboard | `ToolResult` |
| `ui.tree` | Read accessibility/UI automation tree. | `windowId?`, `mode?`, `maxNodes?` | `{ nodes, truncated }` | LOW/MEDIUM | No if session active | UI Automation helper | `UiTreeNode` |
| `ui.find_text` | Find UI text. | `query`, `windowId?` | `{ matches }` | LOW | No | UI Automation helper | `UiElementRef` |
| `ui.click_element` | Click UI element by ref. | `elementId` or `selector` | `{ clicked: true }` | MEDIUM/HIGH | Agent Control; high if submit/destructive | UI Automation helper | `UiElementRef` |
| `file.list` | List allowed folder. | `path`, `maxDepth?`, `pattern?` | `{ files }` | LOW/MEDIUM | No if path allowlisted | Native helper/Node fs | `FileEntry` |
| `file.read` | Read allowed file. | `path`, `startLine?`, `maxBytes?` | `{ content, metadata }` | MEDIUM/HIGH if sensitive | Approval for outside allowlist/sensitive | Native helper/Node fs | `FileReadResult` |
| `file.write` | Write file. | `path`, `content`, `mode`, `backup?` | `{ written, backupPath? }` | HIGH | Yes | Native helper/Node fs | `FileWriteResult` |
| `file.move` | Move/rename file. | `source`, `destination`, `overwrite?` | `{ moved: true }` | HIGH | Yes | Native helper/Node fs | `ToolResult` |
| `file.delete` | Trash/delete file. | `path`, `permanent?` | `{ deleted, trashPath? }` | HIGH | Yes | Native helper/Node fs | `ToolResult` |
| `shell.run` | Run command. | `command`, `cwd?`, `timeoutMs?`, `env?` | `{ exitCode, stdout, stderr, timedOut }` | HIGH | Yes except allowlisted read-only | Native helper | `ShellCommandResult` |
| `shell.cancel` | Cancel running command. | `processId` | `{ cancelled: true }` | MEDIUM | No if same session | Native helper | `ToolResult` |
| `browser.open` | Open browser/app tab. | `browser?`, `url?`, `profile?` | `{ pageId, url }` | MEDIUM | Agent Control active | Electron/Playwright/CDP | `BrowserSessionRef` |
| `browser.goto` | Navigate page. | `pageId`, `url` | `{ url, title }` | MEDIUM | Agent Control active | Playwright/CDP | `BrowserPageState` |
| `browser.dom` | Read DOM/accessibility summary. | `pageId`, `mode?` | `{ interactiveElements, forms, textPreview }` | LOW/MEDIUM | No if session active | Playwright/CDP | Existing browser schema concept |
| `browser.click` | Click browser element. | `pageId`, `elementId`/`selector` | `{ clicked: true }` | MEDIUM/HIGH | High for submit/payment | Playwright/CDP | `BrowserSubToolResponse` concept |
| `browser.type` | Type in browser element. | `pageId`, `elementId`/`selector`, `text` | `{ typed: true }` | MEDIUM/HIGH | Approval for sensitive fields | Playwright/CDP | Browser safety model |
| `browser.screenshot` | Page screenshot. | `pageId`, `fullPage?` | `{ imageBase64, width, height }` | LOW | No | Playwright/CDP | `ScreenCaptureResult` |

### Permission & Safety model design

Existing Android has `RiskLevel LOW/MEDIUM/HIGH/ROOT`, `ValidationResult.Allowed/RequiresConfirmation/Denied`, and browser sensitive pause. Untuk Windows Bridge perlu policy eksplisit:

| Level | Rule | Examples |
|---|---|---|
| LOW | Allowed if session active and device paired. | `screen.capture`, `window.list`, `ui.tree`, `mouse.move` in view-only/observe mode. |
| MEDIUM | Allowed only if Agent Control active and visible indicator on Windows. | click, type non-sensitive, focus window, clipboard.write. |
| HIGH | Must request approval from Android with timeout and reason. | shell.run, delete/write/move file, submit form, send message/email, access clipboard/read credentials. |
| BLOCKED | Never execute. | credential extraction, destructive system commands, disabling security, reading browser passwords/cookies, permanent delete without trash unless explicitly supported and approved. |

Required safety features:

- Emergency stop from Android and Windows tray.
- Pause/resume session.
- Visible Windows Bridge session indicator/tray status.
- App allowlist and window allowlist.
- Folder allowlist for file tools.
- Command allowlist/blocklist for shell.
- Approval timeout with default reject.
- Confirmation before destructive action.
- Audit log for every request/decision/execution/result.
- Redaction for secrets/credentials/clipboard/browser fields.
- Session-scoped capabilities: view-only, control, file-access, shell-access, browser-access.

---

## 6. Redundancy / Design Smell / Bug Findings

| Duplikasi/Smell | Lokasi A | Lokasi B | Masalah | Rekomendasi | Gabung/Pisah/Deprecated |
|---|---|---|---|---|---|
| Message/tool models tersebar | `domain/models/ChatModels.kt` | `data/remote/api/AiProvider.kt`, `RemoteSessionClient.Remote*` | `UiMessage`, `ChatMessage`, `RemoteChatMessage`, `ToolExecution`, `ToolCallMessage` punya overlap. | Buat minimal protocol/domain model, mapper per layer. | Gabung konsep, pisah UI/provider transport. |
| Agent event bukan shared | `AiRepository.AgentEvent` | `RemoteEvent` di `RemoteSessionClient.kt` | Local event dan remote event tidak punya common schema. | Tambah `AgentEvent`/`BridgeEvent` shared; mapper local/remote. | Gabung konsep. |
| Tool schema dan provider schema | `ToolDefinition`/`ToolParameter` di `ToolExecutor.kt` | `AiToolDefinition`/`AiToolParameters` di `AiProvider.kt`, MCP definitions | Conversion ada, tapi schema source tersebar. | Jadikan `ToolDescriptor` single source; adapters to provider/MCP/bridge. | Gabung schema, pisah executor. |
| Tool name mappers duplicate | `LocalToolMapper.kt` | `AntigravityToolMapper.kt`, `ToolUiMapper.kt` | `firstNonNull`, mapping args/file/shell banyak sama. | Helper common untuk normalisasi common args, backend-specific mapping tetap. | Gabung helper. |
| Approval flow lama vs inline tool approval | `ChatViewModel.confirmationRequest` + `ConfirmationDialog` | `LocalIntelligenceService.awaitInlineToolConfirmation` + `ToolCallCard` metadata | `confirmationRequest` tidak terlihat pernah diset; `respondToConfirmation()` mengirim id kosong. | Deprecated dialog state atau hubungkan ke typed `ApprovalRequest`. | Deprecated path lama jika tidak digunakan. |
| Remote approval vs local approval | Extension `confirmation_required`/`tool_interaction` | Local `ConfirmationRequest`/metadata | Dua bentuk approval tanpa shared model. | Buat `ApprovalRequest/ApprovalDecision` shared. | Gabung model, pisah executor. |
| Browser schema TS vs Kotlin JSON builder | `docs/browser-toolcall-schema.ts` | `BrowserResponseFormatter.kt`, `BrowserSessionManager.kt` | Schema bisa drift karena TS hanya docs. | Tambah tests/golden JSON atau shared schema generator. | Pisah runtime, unify schema. |
| Session models overlap | `ConversationEntity` Room | `SessionMemoryRepository` JSONL, remote `RemoteConversationMeta` | Conversation, session memory, remote sessions berbeda tapi penamaan mirip. | Definisikan `ChatConversation`, `AgentRunSession`, `BridgeControlSession`, `AuditSession`. | Pisah tanggung jawab. |
| Memory vs self-improvement vs skills | `MemoryRepository`, `SelfImprovementPipeline`, `SkillRepository` | `UpdateMemoryTool`, `SkillManageTool` | Sudah ada policy yang menahan auto-skill; tetap rentan dianggap sama. | Pertahankan pemisahan: memory context vs reusable skills vs bridge audit. | Pisah. |
| Domain layer import implementation/UI | `IntelligenceSessionManager.displayName()` | `impl.ide.IdeProviderFactory` | Domain memanggil impl factory. | Pindahkan display mapping ke UI/mapper. | Pisah. |
| Domain model import Compose | `domain/models/IdeConfig.kt` | Compose `ImageVector` | Tidak shared/KMP-friendly. | Ganti icon id string di domain, map ke ImageVector di UI. | Refactor. |
| Domain ChatModels import data/tools | `ChatModels.kt` | `MessageRole` from data remote, `TodoItem` from tools | Domain tergantung data layer/tools. | Pindahkan `MessageRole` ke domain; todo UI state keluar dari core message. | Refactor. |
| Manifest references missing service | `AndroidManifest.xml` | `service/AiOperationService` not found | Potential build/runtime manifest issue. | Verifikasi build; hapus/implement jika memang missing. | Fix terpisah, bukan bridge. |
| Remote activities duplicated by intent | `ui.activities.chat.remote.RemoteChatActivity` | `ui.activities.remote.RemoteChatActivity` | Dua remote chat activity berbeda bisa membingungkan routing. | Audit penggunaan/navigation sebelum bridge UI ditambah. | Mungkin deprecate salah satu. |
| Transport protocols multiple | Android Antigravity `RemoteSessionClient` | Extension `MessageHandler/EventBuffer` | Protocol untyped; adding Windows directly bisa makin bercabang. | Buat `bridge/protocol` versioned and separate from Antigravity. | Pisah, jangan campur. |

---

## 7. Refactor Plan

### Phase 1 — Rapikan shared schema/boundary

- Jangan pindahkan semua logic. Buat boundary kecil dulu.
- Definisikan protocol murni untuk bridge:
  - `BridgeEnvelope`
  - `BridgeMessageType`
  - `BridgeToolCall`
  - `BridgeToolResult`
  - `BridgeToolError`
  - `AgentEvent`
  - `SessionState`
  - `ApprovalRequest`
  - `ApprovalDecision`
  - `RiskLevel`
- Pisahkan Android-only dari shared:
  - UI Compose tetap di `ui/`.
  - Room/DataStore/Context tetap di `data/`/`impl/`.
  - Protocol tidak import Android, Compose, Hilt, Room.
- Untuk sekarang bisa di package Android `domain/bridge` atau `domain/protocol` dulu. Jika nanti Electron butuh TS package, buat schema JSON/TS terpisah.

### Phase 2 — Transport bridge Android

- Buat `BridgeSessionClient` terpisah dari `RemoteSessionClient` Antigravity.
- Tambahkan connection/pairing lifecycle:
  - pairing QR/token
  - session.created/closed
  - reconnect policy
  - seq/ack/replay
- Tambahkan approval request/result typed.
- Tambahkan `WindowsBridgeIntelligenceService` atau `BridgeToolExecutor` tergantung keputusan product:
  - Jika agent tetap Android: bridge sebagai remote tool executor.
  - Jika bridge juga punya planner: butuh service sendiri.

### Phase 3 — Tool registry Windows + Risk policy

- Buat registry descriptor shared untuk tools Windows.
- Buat `PermissionPolicy`, `RiskPolicy`, `ApprovalPolicy`.
- Buat Android UI rendering untuk bridge tools memakai existing `ToolCallCard`.
- Jangan reuse `CommandValidator` langsung; buat Windows-specific validator.

### Phase 4 — Electron bridge MVP

- Buat `windows-bridge/` package baru.
- Electron tray app sebagai UI shell + WebSocket server/client.
- Native helper executor untuk privileged OS actions.
- MVP tools:
  - `screen.capture`
  - `mouse.click`
  - `keyboard.type`
  - `audit.event`
- Mandatory:
  - visible tray indicator
  - pause/emergency stop
  - approval for HIGH
  - audit log local on Windows

### Phase 5 — Browser automation, UI Automation, shell/file tools

- Browser automation via Playwright/CDP where possible.
- Windows UI Automation for `ui.tree`, `ui.find_text`, `ui.click_element`.
- File tools with folder allowlist/trash backup.
- Shell tools with allowlist/blocklist and process cancellation.
- Clipboard handling with secret detection and approval.

---

## 8. Files to Modify

Belum dimodifikasi sekarang. Ini daftar rencana jika implementasi disetujui.

### CREATE

| Path | Reason |
|---|---|
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeEnvelope.kt` | Envelope typed untuk Android bridge messages. |
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeMessage.kt` | Type/event payload model. |
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeTool.kt` | `BridgeToolCall`, `BridgeToolResult`, descriptor. |
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeSessionState.kt` | Session lifecycle + pause/resume/cancel state. |
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeApproval.kt` | `ApprovalRequest`, `ApprovalDecision`, timeout metadata. |
| `app/src/main/java/com/amaya/intelligence/domain/bridge/BridgeRiskPolicy.kt` | Shared risk/permission decision enums. |
| `app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeSessionClient.kt` | Android WebSocket client untuk Windows Bridge. |
| `app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeIntelligenceService.kt` | Optional service adapter ke Chat UI jika bridge jadi mode sendiri. |
| `app/src/main/java/com/amaya/intelligence/impl/bridge/windows/WindowsBridgeEventHandler.kt` | Event-to-UI mapper bridge. |
| `windows-bridge/package.json` | New Electron/native helper package. |
| `windows-bridge/electron/main.ts` | Tray app + session indicator. |
| `windows-bridge/protocol/*.ts` | TS mirror untuk protocol models. |
| `windows-bridge/tool-executor/*.ts` | Tool registry/executor contracts. |
| `windows-bridge/audit-log/*.ts` | Durable audit log writer. |

### MODIFY

| Path | Reason |
|---|---|
| `app/src/main/java/com/amaya/intelligence/domain/ai/IntelligenceSessionManager.kt` | Tambah `WINDOWS_BRIDGE` mode jika bridge tampil sebagai remote mode. |
| `app/src/main/java/com/amaya/intelligence/di/IntelligenceModule.kt` | Bind bridge service atau bridge tool executor. |
| `app/src/main/java/com/amaya/intelligence/domain/models/ChatModels.kt` | Kurangi coupling domain ke data/tools secara bertahap. |
| `app/src/main/java/com/amaya/intelligence/impl/common/mappers/ToolUiMapper.kt` | Tambah mapping UI untuk `screen.*`, `mouse.*`, `keyboard.*`, `file.*`, `shell.*`, `window.*`, `ui.*`. |
| `app/src/main/java/com/amaya/intelligence/ui/components/shared/ToolCallCard.kt` | Render approval bridge typed jika tidak cukup lewat metadata. |
| `app/src/main/java/com/amaya/intelligence/ui/screens/remote/RemoteSessionScreen.kt` | Tambah pairing/connection UI Windows Bridge atau buat screen baru. |
| `app/src/main/AndroidManifest.xml` | Tambah activity/service jika perlu; verifikasi missing `AiOperationService`. |
| `docs/` | Tambah `windows-bridge-protocol.md` dan safety policy docs. |

### DEPRECATE / AVOID

| Path | Reason |
|---|---|
| `ChatViewModel.confirmationRequest` path | Saat ini tampak tidak terhubung ke actual inline approval; jangan tambah bridge ke path lama tanpa audit. |
| `impl/ide/antigravity/client/RemoteSessionClient.RemoteEvent` as bridge model | Jangan dipakai langsung untuk Windows Bridge karena Antigravity-specific. |
| `amaya-remote-extension/src/controllers/support/HostWorkspaceService.ts` direct reuse | VS Code-specific dan minim permission/audit. Pakai sebagai referensi saja. |
| `domain/models/IdeConfig.kt` as shared model | Mengandung Compose `ImageVector`; tidak platform-neutral. |

---

## 9. Risks

| Risiko | Detail | Mitigasi |
|---|---|---|
| Coupling Android-Windows | Jika protocol langsung memakai `UiMessage`, `RemoteEvent`, atau Antigravity names, bridge akan sulit berkembang. | Buat bridge protocol versioned terpisah; gunakan mapper. |
| Security OS control | Mouse/keyboard/shell/file tools bisa merusak komputer. | Risk policy, approval HIGH, allowlist, audit log, emergency stop. |
| Credential exposure | Screen/clipboard/UI tree/browser DOM bisa mengandung password/token. | Redaction, sensitive field detection, approval, never store raw secrets. |
| Agent salah klik | Koordinat screen raw rentan salah karena scaling/multi-monitor/window focus. | Prefer UI element refs/accessibility tree, screen preview, click confirmation for risky targets. |
| File destructive operation | `file.delete/write/move` bisa irreversible. | Trash/backup default, folder allowlist, explicit confirmation. |
| Shell abuse | Command injection, chained destructive commands, exfiltration. | WindowsCommandValidator, allowlist/blocklist, timeout, output limit, approval, cwd allowlist. |
| Transport latency/desync | Screen frames/tool results can arrive out of order. | seq/ack/replay, idempotent commands, session state machine. |
| Cross-platform schema mismatch | Kotlin and TS schema can drift. | JSON schema/golden tests or generate TS/Kotlin from one schema. |
| Pairing/auth weakness | Local network WebSocket can be discovered. | Pairing token, session key, origin/device validation, expiration. |
| Audit tampering | Audit log only in Electron can be edited. | Append-only local log, hash chain optional, send summaries to Android. |
| Over-abstraction | Moving all Android logic to shared prematurely can break app. | Start with small protocol models and mappers only. |

---

## 10. Final Recommendation

### Tetap Android-only

- Compose UI: `ui/activities`, `ui/screens`, `ui/components`.
- Room/DataStore/Hilt implementations.
- Android WebView executor (`impl/local/browser/AndroidBrowserController`, `BrowserSessionManager`).
- Android file/shell executor implementations.
- Android runtime permissions and foreground services.
- Provider implementations (`OpenAiProvider`, `GeminiProvider`, `AnthropicProvider`) and Android settings storage.

### Masuk shared/domain protocol

- Message/session/tool-call schema minimal:
  - `MessageRole`
  - `ToolCall`
  - `ToolResult`
  - `ToolError`
  - `AgentEvent`
  - `SessionState`
  - `ApprovalRequest/Decision`
  - `RiskLevel/PermissionDecision`
  - `BridgeEnvelope`
- Browser safety taxonomy:
  - sensitive field types
  - pause reasons
  - allowed next actions
- Transport common concepts:
  - seq id
  - session id
  - device id
  - ack/replay/reconnect policy

### Khusus Windows Bridge

- Electron tray UI, pairing screen, visible session indicator.
- Native helper executor for screen/input/window/UI automation/shell/file.
- Windows permission policy and validators.
- Windows audit log.
- Browser automation implementation via Playwright/CDP/UI Automation.

### Monorepo package baru?

Ya, disarankan buat package baru:

```txt
windows-bridge/
├─ electron/
├─ native-helper/
├─ protocol/
├─ tool-executor/
├─ permissions/
├─ audit-log/
└─ tests/
```

Untuk shared schema lintas Kotlin/TypeScript, ada dua opsi:

1. **Short-term**: Kotlin models di `app/domain/bridge` + TS mirror di `windows-bridge/protocol`, dijaga dengan golden JSON tests.
2. **Long-term**: JSON Schema sebagai source of truth, generate Kotlin/TypeScript types.

### Native helper terpisah?

Ya. Electron sebaiknya menjadi UI shell/tray/pairing/log viewer. Native helper menjalankan tindakan OS berisiko:

- screen capture
- input simulation
- UI Automation
- shell process management
- privileged file operations

Electron tidak sebaiknya memegang semua privilege tanpa boundary.

### Kesimpulan

Codebase sudah punya pondasi kuat untuk bridge:

- Agent loop lokal lengkap.
- Tool executor + confirmation callback.
- Risk validation awal.
- Browser automation dengan safety pause.
- Remote WebSocket client/server dengan seq/reconnect/event buffer.
- ToolCall UI yang bisa menampilkan approval inline.

Namun belum ada shared protocol murni. Boundary yang benar adalah: **Android agent tetap planner/controller**, Windows Bridge menjadi **remote tool executor dengan permission, approval, audit, dan visible control session**. Jangan memindahkan memory/persona/skills ke Windows Bridge dulu; kirim hanya context/tool calls yang dibutuhkan per session.

Langkah paling aman berikutnya adalah Phase 1: definisikan schema bridge kecil dan mapper, tanpa mengubah flow Antigravity atau local agent yang sudah berjalan.
