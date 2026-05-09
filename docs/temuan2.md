# Temuan 2 — Full Android Audit Windows Bridge

Tanggal audit: 2026-05-09  
Scope: Android only (`app/src/main/java/com/amaya/intelligence/`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`).  
Tidak mengaudit detail `windows-bridge/` / Electron.

## 1. Executive Summary

- Integrasi Android Windows Bridge **sudah terpisah cukup baik secara folder besar** (`domain/bridge`, `impl/bridge/windows`, UI bridge terpisah), tetapi **belum sehat untuk lanjut ke file/shell tools tanpa patch safety**.
- Masalah terbesar:
  1. **HIGH-risk tools (`file.write`, `file.edit`, `file.delete`, `shell.run`) saat ini `enabledByDefault = true` dan ikut visible ketika Agent Control ON** karena filter `risk == LOW || agentControl`.
  2. `WindowsBridgeScreen` bisa mengaktifkan Agent Control **tanpa confirmation dialog**.
  3. Pairing/profile layer dibuat tetapi **belum dipakai**; ViewModel masih menyimpan host/port/deviceId sendiri via SharedPreferences.
  4. Routing Windows Bridge ditempel di `McpToolExecutor`, sehingga MCP executor mulai menjadi host routing non-MCP.
  5. Metadata target Windows Bridge tidak sampai ke UI execution karena `AgentEvent.ToolCallResult` tidak membawa `ToolResult.metadata`.
- Must fix sebelum lanjut fase file/shell tools: **gate HIGH tools**, tambah confirmation di bridge screen, wire profile/pairing store atau hapus/stub dengan jelas, dan rapikan routing executor minimal agar MCP tidak menjadi pusat logic Windows.

## 2. Android Folder / File Tree Review

Relevant tree terbaru:

```text
app/src/main/java/com/amaya/intelligence/
├─ data/
│  ├─ remote/
│  │  └─ mcp/McpToolExecutor.kt
│  └─ repository/AiRepository.kt
├─ domain/
│  ├─ bridge/
│  │  ├─ BridgeApproval.kt
│  │  ├─ BridgeAuditEvent.kt
│  │  ├─ BridgeEnvelope.kt
│  │  ├─ BridgeError.kt
│  │  ├─ BridgeMessageType.kt
│  │  ├─ BridgeRiskPolicy.kt
│  │  ├─ BridgeSessionState.kt
│  │  ├─ BridgeToolCall.kt
│  │  ├─ BridgeToolNames.kt
│  │  └─ BridgeToolResult.kt
│  └─ models/ToolExecutionTarget.kt
├─ impl/
│  ├─ bridge/windows/
│  │  ├─ WindowsBridgeClientConfig.kt
│  │  ├─ WindowsBridgeClientEvent.kt
│  │  ├─ WindowsBridgeConnectionState.kt
│  │  ├─ WindowsBridgeEnvelopeMapper.kt
│  │  ├─ WindowsBridgeEventHandler.kt
│  │  ├─ WindowsBridgeLogger.kt
│  │  ├─ WindowsBridgeSessionClient.kt
│  │  ├─ pairing/
│  │  │  ├─ WindowsBridgePairingPayload.kt
│  │  │  ├─ WindowsBridgeProfile.kt
│  │  │  └─ WindowsBridgeProfileStore.kt
│  │  └─ tools/
│  │     ├─ WindowsBridgeController.kt
│  │     ├─ WindowsBridgeToolProvider.kt
│  │     ├─ WindowsBridgeToolExecutor.kt
│  │     ├─ WindowsBridgeToolRegistry.kt
│  │     ├─ WindowsBridgeToolDefinitions.kt
│  │     ├─ WindowsBridgeToolMapper.kt
│  │     ├─ WindowsBridgeToolResultMapper.kt
│  │     ├─ WindowsBridgeApprovalMapper.kt
│  │     └─ WindowsBridgeFriendlyErrorMapper.kt
│  └─ common/mappers/ToolUiMapper.kt
├─ tools/
├─ ui/
│  ├─ activities/bridge/WindowsBridgeActivity.kt
│  ├─ components/remote/
│  │  ├─ WindowsBridgeAgentControlDialog.kt
│  │  ├─ WindowsBridgeApprovalCard.kt
│  │  ├─ WindowsBridgeChatPanelViewModel.kt
│  │  ├─ WindowsBridgeChatUiState.kt
│  │  └─ WindowsBridgeConnectionBanner.kt
│  ├─ screens/bridge/
│  │  ├─ WindowsBridgeScreen.kt
│  │  ├─ WindowsBridgeUiState.kt
│  │  └─ WindowsBridgeViewModel.kt
│  ├─ screens/chat/local/LocalChatScreen.kt
│  └─ screens/remote/RemoteSessionScreen.kt
└─ di/
```

| Folder | Purpose | Layer | Correct placement? | Problem | Recommendation |
| --- | --- | --- | --- | --- | --- |
| `domain/bridge/` | Protocol/model murni bridge | domain | Yes | Tidak ada import Android/UI/Hilt terdeteksi | Keep |
| `impl/bridge/windows/` | WebSocket client, envelope mapper, state | impl/runtime | Yes | `SessionClient` cukup besar tapi masih runtime-focused | Keep, tambah state flow lebih lengkap jika perlu |
| `impl/bridge/windows/tools/` | Registry/provider/executor/tool mapping | impl/tool-executor | Partial | `WindowsBridgeController.kt` ditempatkan di `tools/`, padahal controller adalah runtime facade | Pindah controller ke `impl/bridge/windows/` saat refactor ringan |
| `impl/bridge/windows/pairing/` | Parser dan profile store | impl/persistence pairing | Yes, but unused | Store/parser tidak dipakai ViewModel | Wire ke ViewModel atau tandai future-only |
| `ui/screens/bridge/` | Setup/control screen | UI screen | Partial | Expected architecture menyebut `ui/screens/remote/`; folder bridge lebih isolated tapi menyimpang | Boleh keep jika keputusan arsitektur: Windows Bridge bukan remote IDE. Kalau ingin konsisten, dokumentasikan |
| `ui/components/remote/` | Banner/approval/dialog bridge | UI component | Partial | Nama `remote` mencampur remote IDE dan Windows Bridge | Lebih bersih: `ui/components/bridge/` atau `ui/components/remote/bridge/` |
| `ui/screens/chat/local/` | Existing local chat wrapper | UI integration | Yes/partial | Tidak duplikasi ChatScreen, tetapi overlay top offset hardcoded | Keep integration, pertimbangkan slot/banner API di shared ChatScreen nanti |
| `ui/viewmodels/` | Existing shared ViewModel | UI VM | Yes | Tidak diambil alih oleh bridge | Do not change |
| `data/repository/` | Agent loop/tool definitions | data orchestration | Partial | Menambah bridge tool definitions langsung bergantung ke impl provider | Accept short-term; later introduce neutral tool-provider aggregator |
| `data/remote/mcp/` | MCP executor | remote MCP | No/partial | `McpToolExecutor` kini meroute Windows Bridge, bukan hanya MCP | Refactor later ke composite executor/router di layer yang netral |
| `tools/` | Local Android tools | local tool runtime | Yes | Tidak dicampur Windows executor | Keep |
| `di/` | Hilt wiring | DI | Yes | Tidak ada module baru diperlukan karena constructor injection | Keep, cek lifecycle |

## 3. Git Diff Summary

Wajib command yang dipakai:

```bash
git status --short
git diff -- app/src/main/java/com/amaya/intelligence
git diff -- app/src/main/AndroidManifest.xml
git diff -- app/build.gradle.kts
```

`app/build.gradle.kts`: **no diff**.

| File | Change Type | What Changed | Layer | Correct Placement? | Risk |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | MODIFY | Register `WindowsBridgeActivity` | manifest | Yes | Low |
| `data/repository/AiRepository.kt` | MODIFY | Inject bridge provider; append bridge tools to model tool definitions | data/repository | Partial | Medium: data depends on impl provider |
| `data/remote/mcp/McpToolExecutor.kt` | MODIFY | Route known bridge tools before local executor | tool-executor/MCP | Partial/No | Medium: MCP class becomes generic router |
| `impl/common/mappers/ToolUiMapper.kt` | MODIFY | Add UI metadata for Windows tool names | shared mapper | Yes | Low/Medium: names hardcoded, target metadata not used |
| `ui/screens/chat/local/LocalChatScreen.kt` | MODIFY | Overlay bridge banner, approval card, Agent Control dialog | ui/screen | Yes/partial | Medium: brittle top offset, local-only integration |
| `ui/screens/remote/RemoteSessionScreen.kt` | MODIFY | Add Windows Bridge entry card | ui/screen | Partial | Low: remote IDE screen now has bridge entry |
| `domain/bridge/*` | CREATE | Protocol, risk, approval, envelope, tool/result models | domain | Yes | Low |
| `domain/models/ToolExecutionTarget.kt` | CREATE | Execution target enum | domain model | Partial | Low: currently unused / metadata type mismatch risk |
| `impl/bridge/windows/*` | CREATE | Client config, events, connection state, mapper, logger, session client | impl/runtime | Yes | Medium: lifecycle/config edge cases |
| `impl/bridge/windows/pairing/*` | CREATE | Pairing payload parser, profile model/store | impl/pairing | Yes, unused | Medium: expiry bug + not wired |
| `impl/bridge/windows/tools/*` | CREATE | Provider, executor, registry, definitions, result/approval/error mappers, controller | impl/tool-executor | Partial | High: risk gating bug |
| `ui/activities/bridge/WindowsBridgeActivity.kt` | CREATE | Standalone bridge setup/control Activity | ui/activity | Yes | Low |
| `ui/screens/bridge/*` | CREATE | Bridge screen, state, ViewModel | ui/screen/vm | Partial | Medium: screen VM stores config manually, no confirmation on AC |
| `ui/components/remote/WindowsBridge*.kt` | CREATE | Banner, approval card, chat panel VM/state, dialog | ui/component | Partial | Medium: remote namespace mixing, duplicate state |

Other git status outside Android: deleted/moved docs (`codex-auth*.md`, `models.md`), new `windows-bridge/`, phase docs, `temuan.md`. Not audited in detail because outside Android scope.

## 4. Flow Review

### Pairing/connect flow

Current observed flow:

```text
WindowsBridgeActivity
→ WindowsBridgeScreen
→ WindowsBridgeViewModel.connect()
→ manual SharedPreferences load/save for host/port/deviceId
→ WindowsBridgeController.connect(config)
→ WindowsBridgeSessionClient.connect()
→ WebSocket open
→ incoming session.created/device.paired events
→ controller activeSessionId update on session.created
→ UI polls controller every 500ms
```

Findings:

- Token: not saved in `WindowsBridgeProfileStore`; `WindowsBridgeViewModel.saveConfig()` saves only host/port/deviceId. Pass.
- But `WindowsBridgeProfileStore` itself is not used at all.
- Pairing payload parser is not used at all.
- DeviceId is stable once saved; fallback generated from `ANDROID_ID`, then persisted.
- Reconnect saved profile is partial: host/port/deviceId load works, but profile list/reconnect UX is absent.
- `WindowsBridgePairingPayload.isExpired` likely wrong if `expiresAt` follows doc example seconds epoch; it compares millis to seconds and will mark valid payloads expired.
- `WindowsBridgeSessionClient.connect(host, port, token)` reconnects with overridden network target, but internal `config` remains original for `deviceId/sessionId` in later envelopes. If caller ever changes `deviceId/sessionId` with an existing client, it can desync.

### Agent Control flow

Current observed flow:

```text
Chat banner
→ click VO/AC
→ if enabling: WindowsBridgeAgentControlDialog
→ WindowsBridgeChatPanelViewModel.confirmEnableAgentControl()
→ WindowsBridgeController.setAgentControlEnabled(true)
→ WindowsBridgeSessionClient.sendAgentControlStatus(true)

WindowsBridgeScreen
→ Switch
→ WindowsBridgeViewModel.toggleAgentControl()
→ WindowsBridgeController.setAgentControlEnabled(next)
```

Findings:

- Default OFF: pass (`MutableStateFlow(false)`).
- Chat banner enabling requires confirmation: pass.
- Bridge management screen enabling does **not** require confirmation: fail.
- OFF is direct: pass.
- MEDIUM tools are hidden while OFF: pass.
- HIGH tools are **not safely hidden** when ON: fail. Current filter is `risk == LOW || agentControl`, so all enabled HIGH tools become visible when Agent Control ON.
- Windows/Android desync possible: Android sends status on toggle and after `SessionCreated`, but does not consume remote status/policy changes into local UI.

### Emergency Stop flow

Current observed flow:

```text
Chat banner / WindowsBridgeScreen
→ emergencyStop()
→ WindowsBridgeController.emergencyStop()
→ _agentControlEnabled = false
→ client.cancelSession() sends agent.cancelled
→ _pendingApproval = null
```

Findings:

- Real signal is sent (`agent.cancelled`): pass.
- Pending approval is cleared: pass.
- Agent Control is disabled: pass.
- UI paused state is unclear: `WindowsBridgeConnectionState.PAUSED` exists, but controller/session client never sets it on local emergency stop. Chat may still show connected/view-only, not paused.
- Pending executing tool calls are not locally cancelled immediately unless Windows responds/closes/errors; executor waits until result/timeout.

### Tool definition flow

Current observed flow:

```text
AiRepository.buildToolDefinitions()
→ local ToolExecutor definitions
→ MCP tools from McpClientManager
→ WindowsBridgeToolProvider.getAvailableBridgeTools()
→ returned only if visibleToolNames() non-empty
```

Findings:

- Local tools remain intact.
- Bridge tools hidden when disconnected: pass.
- LOW tools visible when connected/session active: pass.
- MEDIUM input tools gated by Agent Control: pass.
- HIGH tools are not safe: fail because enabled high-risk file/shell tools become model-visible when Agent Control ON.

### Tool execution flow

Current observed flow:

```text
Model emits tool call
→ AiRepository calls McpToolExecutor.execute()
→ if mcp prefix: McpClientManager.callTool()
→ else if WindowsBridgeToolProvider.isBridgeTool(): executeBridgeTool()
→ else ToolExecutor.execute()
```

Findings:

- Routing works but placement is questionable: `McpToolExecutor` now acts as generic composite executor.
- Offline hallucination of a known bridge tool returns safe `BRIDGE_UNAVAILABLE` error.
- Unknown non-MCP/non-bridge still routes to local `ToolExecutor` and returns unknown local tool error.
- ToolResult mapping is JSON-shaped and consistent enough for model loop.
- Target/origin metadata is incomplete: bridge success sets metadata `executionTarget=WINDOWS_BRIDGE`, but `AgentEvent.ToolCallResult` drops metadata, and `ToolCallStart` marks every execution as `source=local` in `LocalIntelligenceService`.

### ChatScreen integration flow

Current observed flow:

```text
LocalChatScreen
→ existing shared ChatScreen remains main conversation UI
→ WindowsBridgeChatPanelViewModel state
→ WindowsBridgeConnectionBanner overlay
→ WindowsBridgeApprovalCard overlay
→ View Screen / Agent Control / Stop callbacks
```

Findings:

- ChatScreen is not duplicated: pass.
- ChatViewModel is not taken over: pass.
- Input composer remains existing: pass.
- Banner is overlayed with hardcoded `statusBar + 88.dp`; potential layout overlap on different top-bar heights.
- Approval card is custom bridge card, not the existing `ConfirmationRequest` path.

## 5. Redundancy Findings

| Redundancy | Location A | Location B | Problem | Recommendation | Classification |
| --- | --- | --- | --- | --- | --- |
| Local executor vs bridge executor | `tools/ToolExecutor.kt` | `WindowsBridgeToolExecutor.kt` | Responsibilities differ, no direct duplicate | Keep separate | Keep separate |
| MCP executor vs bridge routing | `data/remote/mcp/McpToolExecutor.kt` | `WindowsBridgeToolProvider` | MCP executor now routes non-MCP | Introduce neutral composite router later | Refactor later |
| LocalToolMapper vs WindowsBridgeToolMapper | `impl/local/tools/LocalToolMapper.kt` | `WindowsBridgeToolMapper.kt` | Different argument normalization; okay | Keep separate, reuse helper only for `__` stripping if needed | Keep separate |
| Tool UI mapping | `ToolUiMapper.kt` | bridge hardcoded names | Reused but bridge target metadata not propagated | Keep mapper, add metadata propagation later | Merge helper only |
| ConfirmationRequest vs ApprovalRequest | `tools/ConfirmationRequest` | `domain/bridge/ApprovalRequest` | `WindowsBridgeApprovalMapper` exists but unused | Do not merge models; wire adapter if using existing UI | Refactor later |
| Chat confirmation path vs bridge approval path | `ChatViewModel.confirmationRequest` | `WindowsBridgeApprovalCard` | Two approval UIs | Keep separate short-term; share visual component later | Refactor later |
| RemoteSessionClient vs WindowsBridgeSessionClient | Antigravity client | Bridge client | Protocols differ | Concept only, no code merge | Keep separate |
| RemoteSessionScreen cards vs WindowsBridgeActivity | `RemoteSessionScreen.kt` | `WindowsBridgeActivity.kt` | Bridge entry inside remote IDE selector may confuse category | Keep entry or move to dedicated menu; document boundary | Refactor later |
| Bridge screen VM vs chat panel VM | `WindowsBridgeViewModel` | `WindowsBridgeChatPanelViewModel` | Duplicate polling/controller collection | Share state adapter/helper later | Merge helper only |
| Bridge UI states | `WindowsBridgeUiState` | `WindowsBridgeChatUiState` | Different screen needs, but duplicated labels/status | Keep separate, extract status label helper if grows | Keep separate |
| Profile persistence | `WindowsBridgeProfileStore` | ViewModel `amaya_bridge_config` prefs | Duplicate storage, profile store unused | Wire ViewModel to ProfileStore | Bug/refactor now |
| Approval UI card | `WindowsBridgeScreen.ApprovalCard` | `WindowsBridgeApprovalCard` | Same approval display implemented twice | Use reusable component in screen too | Merge helper only |

## 6. Shared Logic Reuse Opportunities

| Existing Logic | Current Use | Can Reuse for Bridge? | Should Reuse? | Why |
| --- | --- | ---: | ---: | --- |
| `ToolUiMapper` | Bridge names added | Yes | Yes | Correct shared UI mapping point |
| `ToolCallCard` / `MessageBubble` | Existing tool rendering | Yes | Yes later | Needs target metadata propagated through AgentEvent/ToolExecution |
| `ConfirmationRequest` | Local high-risk confirmations | Maybe | Maybe | Bridge approval has session/toolCall/expiresAt; adapter exists but unused |
| `CommandValidator` | Local shell/file validation | Concept only | No direct reuse | Windows paths/commands must be validated by Windows side policy |
| `RemoteSessionClient` | Antigravity remote IDE | Concept only | No | Protocol-specific; keep separate |
| `ChatScreen` | Main local chat UI | Yes | Yes | Current approach reuses it; avoid duplicate ChatScreen |
| `toAiToolDefinition()` | Local bridge tool defs | Yes | Yes | Already reused in AiRepository |
| `ApplicationScope` | App-scoped async | Yes | Yes | Controller uses it; lifecycle is process-wide |
| Existing storage/settings pattern | DataStore/Repo stores | Yes | Yes | Current manual prefs in ViewModel should move to store/repository pattern |
| Existing confirmation components | Chat/local approval UI | Maybe | Should reuse visual atoms | Avoid duplicate cards while keeping bridge model separate |

## 7. OOP Responsibility Review

| Class | Responsibility | Too Much? | Should Move? | Notes |
| --- | --- | ---: | ---: | --- |
| `WindowsBridgeController` | Runtime facade, client/executor lifecycle, Agent Control, approvals | Partial | Yes | Good facade, but path should be `impl/bridge/windows/`; also owns policy gating |
| `WindowsBridgeSessionClient` | WebSocket, reconnect, queue, encode/decode dispatch | Partial | No | Large but cohesive transport class; config override edge case |
| `WindowsBridgeEnvelopeMapper` | JSON envelope and typed payload mapping | No | No | Good no-throw mapper |
| `WindowsBridgeToolProvider` | Agent-facing bridge tool facade | No | No | Thin and correct |
| `WindowsBridgeToolExecutor` | Send tool call, await result/error, timeout | No/partial | No | Missing Android-side approval/risk enforcement |
| `WindowsBridgeToolRegistry` | Static specs lookup | No | No | OK |
| `WindowsBridgeToolMapper` | Tool args → BridgeToolCall | No | No | OK |
| `WindowsBridgeToolResultMapper` | Bridge result/error → ToolResult | No | No | OK, but error JSON exposed to UI in places |
| `WindowsBridgeApprovalMapper` | ApprovalRequest → ConfirmationRequest | No | No | Dead/unused right now |
| `WindowsBridgeViewModel` | Screen state, config prefs, capture screen, polling | Yes | Partial | Should delegate profile persistence; avoid polling if controller exposes state |
| `WindowsBridgeChatPanelViewModel` | Chat banner state/actions | No/partial | No | Duplicates polling with screen VM |
| `WindowsBridgeUiState` | Full screen state | No | No | OK |
| `WindowsBridgeChatUiState` | Compact banner state | No | No | OK |
| `WindowsBridgeActivity` | Host Compose bridge screen | No | No | OK |
| `WindowsBridgeScreen` | Full UI | Partial | No | 443 lines; internal approval card duplicates reusable component |
| `WindowsBridgeConnectionBanner` | Stateless banner | No | No | OK |
| `WindowsBridgeApprovalCard` | Stateless compact approval card | No | No | OK, but does not show `argsPreview` |
| `WindowsBridgeAgentControlDialog` | Confirmation dialog | No | No | OK, not used by full bridge screen |
| `WindowsBridgeProfileStore` | Persist saved profiles | No | No | Correct responsibility, unused |
| `WindowsBridgePairingPayload` | Parse/validate payload | No | No | Never throws, but expiry unit bug |
| `WindowsBridgeProfile` | Saved profile model | No | No | OK |

## 8. DI / Lifecycle Review

| DI Area | Status | Issue | Recommendation |
| --- | --- | --- | --- |
| `WindowsBridgeController` | Pass | `@Singleton`, injected with `@ApplicationScope` | Keep |
| `WindowsBridgeSessionClient` | Partial | Constructed manually by controller; okay due runtime config, but not Hilt-managed | Keep for now; ensure dispose/cancel on disconnect |
| `WindowsBridgeToolProvider` | Pass | `@Singleton` constructor injection | Keep |
| `McpToolExecutor` injection | Partial | Now depends on Windows provider; increases coupling | Refactor later to neutral router |
| ViewModels | Pass | `@HiltViewModel` and injected deps | Keep |
| `WindowsBridgeProfileStore` | Partial | Injectable singleton but unused | Wire into `WindowsBridgeViewModel` |
| Activity manifest | Pass | Activity registered/exported false | Keep |
| Coroutine collection | Partial | Controller event pump appScope lasts until disconnect; VMs poll forever in viewModelScope | Replace polling with `StateFlow` from controller when practical |
| Pending tool calls | Partial | Emergency stop does not locally fail all pending calls immediately | On emergency, signal executor to fail/cancel pending |

## 9. UI Placement Review

| UI Area | Status | Issue | Recommendation |
| --- | --- | --- | --- |
| `WindowsBridgeActivity` | Pass | Setup/control panel only | Keep |
| `WindowsBridgeScreen` | Partial | Agent Control toggle lacks confirmation; duplicates approval card | Add confirmation dialog; reuse `WindowsBridgeApprovalCard` or shared component |
| `WindowsBridgeConnectionBanner` | Pass/partial | Compact, non-intrusive, but hardcoded top overlay controlled by parent | Later add proper slot in shared ChatScreen |
| `WindowsBridgeApprovalCard` | Partial | Separate from existing confirmation pattern | Keep bridge-specific model; share visual treatment later |
| `RemoteSessionScreen` entry card | Partial | Windows Bridge placed beside IDE providers | OK short-term; clarify label/category |
| `LocalChatScreen` wrapper | Pass/partial | Does not duplicate ChatScreen; overlay may overlap header | Consider ChatScreen `topOverlay` slot later |
| `ToolCallCard` changes | Not changed | Bridge target badge cannot show because metadata is not propagated | Add target metadata propagation before polishing UI |

## 10. Safety Review

| Safety Check | Pass/Fail/Unknown | Evidence | Recommendation |
| --- | --- | --- | --- |
| Agent Control default OFF | Pass | `MutableStateFlow(false)` and UI state defaults | Keep |
| Confirmation before ON | Partial/Fail | Chat banner uses dialog; full screen switch directly toggles | Add confirmation on `WindowsBridgeScreen` |
| Emergency stop sends real signal | Pass | `client.cancelSession()` sends `agent.cancelled` | Keep |
| Pending approval clear on disconnect | Pass | Controller clears on session closed/device disconnected/disconnected | Keep |
| Pending approval clear on emergency | Pass | `_pendingApproval.value = null` | Keep |
| Agent Control disabled on emergency | Pass | `_agentControlEnabled.value = false` | Keep |
| Pending executing tools cancelled on emergency | Fail/Unknown | Executor only fails pending on close/disconnect/protocol error | Add executor cancel/fail on emergency |
| Token not stored plaintext | Pass/partial | Token not saved in ViewModel prefs/ProfileStore | Avoid logs; consider secure storage if persisted later |
| Pairing payload expired rejected | Fail/Unknown | Parser has `isExpired`, but not used; seconds vs millis bug likely | Normalize expiry units and enforce before connect |
| Bridge tools hidden while disconnected | Pass | `visibleToolNames()` returns empty when inactive | Keep |
| MEDIUM hidden while Agent Control OFF | Pass | Filter only LOW when AC false | Keep |
| HIGH approval required | Fail | HIGH specs enabled; visible when AC true; no Android-side approval gate | Set HIGH disabled or require approval path before advertise/execute |
| User-facing error does not expose raw payload | Partial | Tool errors are JSON; screen capture error shows raw `result.message` | Map through friendly error mapper before UI display |
| imageBase64 not logged | Pass | Logger logs type/id/tool only | Keep |
| Typed text not displayed in approval/audit | Unknown/partial | Approval card hides argsPreview, mapper would show if used | Ensure Windows redacts; Android should redact known sensitive args |

## 11. Recommended Refactor / Patch Plan

### Must fix now

1. Change bridge visibility policy:
   - LOW visible when connected.
   - MEDIUM visible only when Agent Control ON.
   - HIGH **not visible by default** until explicit approval path is implemented.
2. Set HIGH-risk file/shell tools `enabledByDefault = false` or change `visibleToolNames()` to exclude HIGH regardless of Agent Control for now.
3. Add confirmation dialog before enabling Agent Control in `WindowsBridgeScreen`.
4. Wire `WindowsBridgeProfileStore` into `WindowsBridgeViewModel`, or remove duplicate/manual profile claims until it is used.
5. Fix `WindowsBridgePairingPayload.expiresAt` unit handling and actually reject expired payloads if pairing paste UI is added.

### Should fix soon

1. Move `WindowsBridgeController.kt` from `impl/bridge/windows/tools/` to `impl/bridge/windows/`.
2. Move bridge UI components from `ui/components/remote/` to `ui/components/bridge/` or `ui/components/remote/bridge/`.
3. Replace polling in bridge VMs with controller-exposed `StateFlow<WindowsBridgeConnectionState>` / session id flow.
4. Propagate tool execution target metadata through `AgentEvent.ToolCallStart/Result` and `LocalIntelligenceService`.
5. Use `WindowsBridgeFriendlyErrorMapper` in UI-facing error displays.
6. Make emergency stop fail/cancel pending executor waits locally.

### Can defer

1. Composite tool router abstraction to replace `McpToolExecutor` as generic router.
2. Shared visual approval component for local confirmation + bridge approval.
3. Saved profile list UI and reconnect UX.
4. Streamed screen frames / live remote display.
5. Dedicated bridge entry outside Remote Session screen.

### Do not change

1. Do not merge WindowsBridgeSessionClient with Antigravity RemoteSessionClient.
2. Do not move Windows executor logic into `tools/` local Android tools.
3. Do not reuse Android `CommandValidator` directly for Windows paths/shell.
4. Do not duplicate `ChatScreen` or replace `ChatViewModel`.

## 12. Files Recommended to Modify

```text
CREATE:
- app/src/main/java/com/amaya/intelligence/ui/components/bridge/ — optional later if moving bridge UI components out of remote namespace.
- app/src/main/java/com/amaya/intelligence/data/repository/ToolExecutionRouter.kt — optional later neutral router for local/MCP/bridge.

MODIFY:
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolDefinitions.kt — disable HIGH-risk defaults for now.
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeController.kt — fix visibility policy; optionally expose connection/session flows; later move package.
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolProvider.kt — enforce HIGH-risk unavailable unless approval path exists.
- app/src/main/java/com/amaya/intelligence/ui/screens/bridge/WindowsBridgeScreen.kt — confirmation before Agent Control ON; reuse approval component.
- app/src/main/java/com/amaya/intelligence/ui/screens/bridge/WindowsBridgeViewModel.kt — use `WindowsBridgeProfileStore`; parse/reject pairing payload when UI supports it.
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/pairing/WindowsBridgePairingPayload.kt — fix expiry unit handling.
- app/src/main/java/com/amaya/intelligence/impl/local/LocalIntelligenceService.kt — later propagate execution target/source metadata.
- app/src/main/java/com/amaya/intelligence/data/remote/mcp/McpToolExecutor.kt — later move bridge branch to neutral router.

DEPRECATE:
- Manual `amaya_bridge_config` SharedPreferences usage in `WindowsBridgeViewModel` once `WindowsBridgeProfileStore` is wired.
- `WindowsBridgeApprovalMapper` / `WindowsBridgeFriendlyErrorMapper` should not remain dead code; wire or remove in a cleanup pass.
```

## 13. Build Result

Preferred build command attempted:

```bash
./gradlew assembleDebug
```

Result: failed in this shell with:

```text
Error: Could not find or load main class "-Xmx64m"
Caused by: java.lang.ClassNotFoundException: "-Xmx64m"
```

Windows PowerShell rerun:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& .\\gradlew.bat assembleDebug"
```

Result:

```text
BUILD SUCCESSFUL in 19s
41 actionable tasks: 41 up-to-date
```

---

## 14. Follow-up Audit — Windows Bridge Chat Separation Issue

Tanggal follow-up: 2026-05-09

Trigger audit: setelah connect Windows Bridge, flow chat masih masuk ke **LocalChatScreen/LocalIntelligenceService**, bukan screen/session chat Windows Bridge khusus. User expectation: Windows Bridge punya screen chat sendiri seperti konsep remote screen, database chat sendiri/terpisah, dan sidebar conversation sendiri.

### 14.1 Temuan utama

| Area | Current Behavior | Expected Behavior | Status | Risk |
| --- | --- | --- | --- | --- |
| Entry chat setelah connect | `WindowsBridgeActivity` membuka `LocalChatActivity` | Buka `WindowsBridgeChatActivity` / screen khusus | Salah konsep | High |
| Chat runtime | Pakai `LocalIntelligenceService` | Pakai service/session mode Windows Bridge khusus | Salah boundary | High |
| Tool definitions | Local tools + MCP tools + bridge tools digabung | Windows Bridge chat hanya expose bridge-safe tools (+ optional shared non-executor tools) | Salah routing | High |
| Conversation DB/sidebar | Pakai `conversations` local yang sama | Conversation scope/table/filter khusus Windows Bridge | Belum ada | Medium/High |
| UI state | Local chat state + bridge banner overlay | Windows Bridge chat state penuh, bukan banner di local | Partial workaround | Medium |
| Tool UI metadata | Tool execution selalu diberi `source=local` di `LocalIntelligenceService` | Bridge execution diberi `source=windows_bridge` / `executionTarget=WINDOWS_BRIDGE` | Salah label | Medium |

### 14.2 Kenapa setelah connect terasa “tergabung ke local screen”

Patch sebelumnya hanya memperbaiki tombol **Start Chat with Windows Bridge** agar membuka chat, tetapi targetnya adalah:

```kotlin
LocalChatActivity.start(this@WindowsBridgeActivity)
```

Artinya flow sekarang:

```text
WindowsBridgeActivity
→ Start Chat
→ LocalChatActivity
→ LocalChatScreen
→ shared ChatScreen
→ ChatViewModel
→ active IntelligenceService mode LOCAL
→ LocalIntelligenceService
→ AiRepository
→ local ToolExecutor + MCP + WindowsBridgeToolProvider
```

Jadi benar: Windows Bridge saat ini bukan mode chat sendiri. Ia hanya menjadi **tambahan tool provider** di atas local chat. Ini menjelaskan kenapa local tools masih muncul/terembed.

### 14.3 Jawaban: kenapa AI hanya melihat `screen.capture` dan `window.list`?

Itu **wajar jika Agent Control OFF**.

Current policy di `WindowsBridgeController.visibleToolNames()`:

```kotlin
return registry.enabledSpecs()
    .filter { it.risk == BridgeRiskLevel.LOW || agentControl }
    .map { it.name }
    .toSet()
```

Saat connected tetapi Agent Control OFF:

- LOW tools visible:
  - `screen.capture`
  - `window.list`
- MEDIUM tools hidden:
  - `window.focus`
  - `mouse.click`
  - `keyboard.type`
  - `keyboard.hotkey`
  - `clipboard.write`
  - dll.

Jadi kalau AI hanya melihat 2 tools, itu expected untuk **view-only mode**.

Namun ada bug safety yang tetap harus dipatch: ketika Agent Control ON, filter `risk == LOW || agentControl` akan membuka semua enabled specs, termasuk HIGH-risk file/shell tools yang saat ini masih `enabledByDefault = true`. Jadi hasil “hanya 2 tools” wajar saat OFF, tetapi policy ON belum aman.

### 14.4 Bukti local tools masih terembed ke Windows Bridge chat

Lokasi:

- `app/src/main/java/com/amaya/intelligence/data/repository/AiRepository.kt`
- `app/src/main/java/com/amaya/intelligence/data/remote/mcp/McpToolExecutor.kt`
- `app/src/main/java/com/amaya/intelligence/impl/local/LocalIntelligenceService.kt`

Current tool definition build:

```kotlin
val localTools = toolExecutor.getToolDefinitions()
val mcpTools = mcpClientManager.getCachedToolDefinitions()
val bridgeTools = windowsBridgeToolProvider.getAvailableBridgeTools()
return localTools + mcpTools + bridgeTools
```

Current execution routing:

```kotlin
when {
    toolName.startsWith(McpClientManager.TOOL_PREFIX) -> mcpClientManager.callTool(...)
    windowsBridgeToolProvider.isBridgeTool(toolName) -> windowsBridgeToolProvider.executeBridgeTool(...)
    else -> toolExecutor.execute(...)
}
```

Current UI tool start mapping in local service:

```kotlin
metadata = mapOf(
    "source" to "local",
    "animateOnMount" to "true"
)
```

Kesimpulan: selama Windows Bridge chat memakai local chat path, model tetap menerima local tools, MCP tools, dan bridge tools sekaligus. UI juga tetap melabeli tool execution sebagai local.

### 14.5 Conversation database / sidebar issue

Existing local conversation table:

```text
ConversationEntity
- id
- title
- workspacePath
- createdAt
- updatedAt
- messagesJson
```

DAO sidebar query:

```sql
SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json
FROM conversations
ORDER BY updated_at DESC
```

Tidak ada field seperti:

```text
session_mode
chat_scope
runtime_target
bridge_profile_id
bridge_session_id
```

Akibatnya jika Windows Bridge memakai `LocalIntelligenceService`, conversation masuk ke table/sidebar local yang sama. Untuk konsep “screen baru seperti remote screen + database chat baru embed sidebar”, perlu salah satu pendekatan:

1. **Tambah kolom scope pada table existing**:
   - `scope = LOCAL | WINDOWS_BRIDGE | ANTIGRAVITY`
   - `bridge_profile_id`, `bridge_session_id` optional
   - DAO sidebar difilter per scope.
2. **Buat table baru khusus Windows Bridge**:
   - `windows_bridge_conversations`
   - `windows_bridge_messages` atau `messages_json`
   - repository/DAO sendiri.

Rekomendasi minimal: tambah `conversation_scope` di `ConversationEntity` agar sidebar reusable tetap bisa dipakai tanpa membuat duplikasi UI besar.

### 14.6 Rekomendasi arsitektur yang lebih benar

Target structure:

```text
impl/bridge/windows/services/
  WindowsBridgeIntelligenceService.kt

ui/activities/bridge/
  WindowsBridgeChatActivity.kt
  WindowsBridgeActivity.kt

ui/screens/chat/bridge/
  WindowsBridgeChatScreen.kt
  WindowsBridgeChatScreenConfig.kt

ui/components/bridge/
  WindowsBridgeConnectionBanner.kt
  WindowsBridgeApprovalCard.kt
  WindowsBridgeAgentControlDialog.kt

data/local/entity/
  ConversationEntity.kt + conversationScope
  atau WindowsBridgeConversationEntity.kt

data/local/dao/
  ConversationDao.kt + scoped queries
  atau WindowsBridgeConversationDao.kt
```

Flow yang diinginkan:

```text
WindowsBridgeActivity
→ connect succeeds
→ Start Chat
→ WindowsBridgeChatActivity
→ WindowsBridgeChatScreen
→ ChatViewModel.switchMode(WINDOWS_BRIDGE) atau VM khusus
→ WindowsBridgeIntelligenceService
→ AiRepository / tool router dengan ToolScope.WINDOWS_BRIDGE
→ bridge tools only, local Android executor disabled
→ sidebar query scope WINDOWS_BRIDGE
```

### 14.7 SessionMode gap

Current `IntelligenceSessionManager.SessionMode` hanya punya:

```kotlin
LOCAL,
ANTIGRAVITY,
CURSOR,
WINDSURF
```

Tidak ada `WINDOWS_BRIDGE`. DI active service juga hanya switch:

```kotlin
LOCAL -> localService
else -> antigravityService
```

Jadi Windows Bridge belum bisa menjadi first-class chat mode. Jika ditambah `WINDOWS_BRIDGE`, `IntelligenceModule.provideActiveIntelligenceService()` juga harus route ke service baru.

### 14.8 Patch plan tambahan

#### Must fix now

1. Jangan arahkan Start Chat ke `LocalChatActivity` lagi.
2. Buat `WindowsBridgeChatActivity` dan `WindowsBridgeChatScreen` sebagai wrapper terpisah.
3. Tambahkan `WINDOWS_BRIDGE` session mode atau ViewModel/service khusus.
4. Filter tool definitions untuk Windows Bridge chat:
   - include bridge tools only;
   - optionally include memory/skill/session tools jika memang diinginkan;
   - exclude local Android file/shell/browser tools.
5. Fix HIGH-risk bridge tool visibility sebelum Agent Control ON dipakai luas.

#### Should fix soon

1. Tambah conversation scope di DB atau table khusus Windows Bridge.
2. Reuse shared `ChatScreen` dengan config `windowsBridgeChatScreenConfig`, bukan fork penuh.
3. Pindahkan bridge UI component dari `ui/components/remote` ke `ui/components/bridge`.
4. Propagate execution target metadata ke `ToolExecution` supaya card bisa badge `WINDOWS` dan bukan `local`.

#### Can defer

1. Full profile-aware sidebar grouping per Windows PC.
2. Cross-session bridge memory/context.
3. Migration UI untuk existing local conversations yang terlanjur dipakai sebagai bridge chat.

### 14.9 Jawaban singkat untuk pertanyaan user

- Ya, benar: saat ini Windows Bridge masih tergabung ke local chat path.
- Ya, wajar AI hanya melihat `screen.capture` dan `window.list` kalau Agent Control OFF.
- Tidak wajar kalau local function-call tools masih ikut tersedia di mode Windows Bridge yang diharapkan dedicated; itu karena belum ada Windows Bridge chat mode/tool scope.
- Solusi arsitektur: buat Windows Bridge chat sebagai first-class mode/screen/service dengan scoped conversations dan scoped tool definitions.

---

## 15. Patch Status — Windows Bridge First-Class Chat Mode

Tanggal patch: 2026-05-09

### 15.1 Yang sudah dipatch

| Problem dari audit | Patch | Status |
| --- | --- | --- |
| Start Chat membuka `LocalChatActivity` | `WindowsBridgeActivity` sekarang membuka `WindowsBridgeChatActivity` | Fixed |
| Tidak ada mode chat Windows Bridge | Tambah `IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE` | Fixed |
| Active `IntelligenceService` hanya Local/Antigravity | `IntelligenceModule` sekarang route `WINDOWS_BRIDGE` ke `WindowsBridgeIntelligenceService` | Fixed |
| Belum ada chat screen khusus | Tambah `ui/screens/chat/bridge/WindowsBridgeChatScreen.kt` | Fixed |
| Belum ada Activity chat khusus | Tambah `ui/activities/bridge/WindowsBridgeChatActivity.kt` dan manifest registration | Fixed |
| Windows Bridge chat memakai local tool list | `AiRepository.chat(runtimeTarget = WINDOWS_BRIDGE)` sekarang hanya build bridge tool definitions | Fixed |
| Local/MCP hallucinated tool masih bisa dieksekusi di Bridge mode | `AiRepository` menolak tool call yang tidak ada di `allowedToolNames` saat runtime Windows Bridge | Fixed |
| System instruction masih membawa persona/memory/skill/local tools | Tambah `ContextManager.buildWindowsBridgeContext()` dengan prompt khusus bridge tanpa persona, memory, skills, local tools, MCP, browser, workspace hints | Fixed |
| Conversation sidebar campur local | Tambah `ConversationScope` dan `scope` column di `ConversationEntity`; Windows Bridge observe `scope='windows_bridge'`, local query filter `scope='local'` | Fixed |
| DB migration belum ada | Tambah `MIGRATION_7_8`, bump DB version 8, generated schema `8.json` | Fixed |
| Profile store dibuat tapi belum dipakai | `WindowsBridgeViewModel` sekarang load/save lewat `WindowsBridgeProfileStore` sambil mempertahankan legacy prefs fallback | Fixed |
| Pairing expiry raw seconds vs millis | `WindowsBridgePairingPayload` sekarang normalize `expiresAt` ke millis sebelum `isExpired` | Fixed |
| HIGH-risk tools terbuka saat Agent Control ON | HIGH-risk `file.write`, `file.edit`, `file.delete`, `shell.run` disabled by default dan controller filter exclude HIGH/BLOCKED | Fixed |
| Agent Control screen bisa ON tanpa dialog | `WindowsBridgeScreen` sekarang menampilkan `WindowsBridgeAgentControlDialog` sebelum ON | Fixed |
| Flow masuk chat tidak seperti remote IDE | Setelah user menekan Connect, `WindowsBridgeScreen` auto-open chat saat connection state connected, mirip `RemoteSessionActivity.onConnected` | Fixed |

### 15.2 File utama yang berubah / dibuat

```text
CREATE:
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/services/WindowsBridgeIntelligenceService.kt
- app/src/main/java/com/amaya/intelligence/ui/activities/bridge/WindowsBridgeChatActivity.kt
- app/src/main/java/com/amaya/intelligence/ui/screens/chat/bridge/WindowsBridgeChatScreen.kt
- app/schemas/com.amaya.intelligence.data.local.db.AppDatabase/8.json

MODIFY:
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/amaya/intelligence/domain/ai/IntelligenceSessionManager.kt
- app/src/main/java/com/amaya/intelligence/di/IntelligenceModule.kt
- app/src/main/java/com/amaya/intelligence/data/repository/AiRepository.kt
- app/src/main/java/com/amaya/intelligence/data/repository/ContextManager.kt
- app/src/main/java/com/amaya/intelligence/data/local/entity/ConversationEntity.kt
- app/src/main/java/com/amaya/intelligence/data/local/dao/ConversationDao.kt
- app/src/main/java/com/amaya/intelligence/data/local/db/AppDatabase.kt
- app/src/main/java/com/amaya/intelligence/data/local/db/migrations/Migrations.kt
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeToolDefinitions.kt
- app/src/main/java/com/amaya/intelligence/impl/bridge/windows/tools/WindowsBridgeController.kt
- app/src/main/java/com/amaya/intelligence/ui/screens/bridge/WindowsBridgeScreen.kt
- app/src/main/java/com/amaya/intelligence/ui/screens/chat/shared/ChatScreenConfig.kt
```

### 15.3 Windows Bridge system prompt policy

Windows Bridge mode sekarang memakai system prompt khusus dari:

```text
ContextManager.buildWindowsBridgeContext()
```

Karakteristik:

- Diawali dengan: `Amaya is a versatile AI assistant running on Android and controlling a paired Windows computer through Windows Bridge.`
- Tidak memasukkan persona prompt.
- Tidak memasukkan saved memory.
- Tidak memasukkan skill index / skill rules.
- Tidak memasukkan local tool list / Android file-shell-browser rules.
- Tidak memasukkan workspace context lokal.
- Tidak menjalankan post-chat self-improvement pipeline untuk bridge runtime.
- Tool schemas yang diberikan ke provider hanya berasal dari `WindowsBridgeToolProvider.getAvailableBridgeTools()`.

### 15.4 Current expected behavior setelah patch

```text
RemoteSessionScreen / Windows Bridge card
→ WindowsBridgeActivity
→ user Connect
→ WindowsBridgeController.connect()
→ when connected: WindowsBridgeChatActivity opens
→ ChatViewModel switches SessionMode.WINDOWS_BRIDGE
→ active IntelligenceService = WindowsBridgeIntelligenceService
→ shared ChatScreen with windowsBridgeChatScreenConfig
→ sidebar shows only windows_bridge scoped conversations
→ AiRepository runtimeTarget WINDOWS_BRIDGE
→ system prompt bridge-only
→ tools bridge-only
```

Saat Agent Control OFF, model hanya melihat LOW bridge tools:

```text
screen.capture
window.list
```

Saat Agent Control ON, model melihat LOW + MEDIUM bridge tools, tetapi HIGH tetap tidak dibuka sampai approval-first path benar-benar siap.

### 15.5 Build result setelah patch

Command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& .\\gradlew.bat assembleDebug"
```

Result:

```text
BUILD SUCCESSFUL
```
