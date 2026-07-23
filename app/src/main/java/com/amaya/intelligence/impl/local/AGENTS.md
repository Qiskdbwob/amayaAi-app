# Android Local Runtime Instructions

## Scope
- This file applies to `app/src/main/java/com/amaya/intelligence/impl/local/` and its children.

## Local Runtime Rules
- Keep device-local execution, persistence, browser automation, and runtime services in this subtree.
- This is the correct place for local tool orchestration, background services, browser control, and non-remote behaviors.
- Keep remote API assumptions out of this layer.
- Prefer Android-native patterns for services, background work, and local state.
- Local chat must persist or resolve the active conversation id before starting a model turn when downstream session-memory or reflection code needs a stable session id.
- Before changing local runtime flow, crosscheck the current workspace diff and recent commits for the touched subtree.

## Coordination
- Coordinate with `data/local/` for storage-backed behavior, `data/repository/` for memory/skill/session repositories, and with `tools/` and `service/` for execution/runtime behavior.
- If a change needs remote integration, move the remote-specific part to the remote instruction subtree instead of broadening this file.
- If files, folders, or features change here, update this AGENTS file and the nearest runtime docs in the same change.

## File Tree
```text
impl/local/
├─ AGENTS.md
├─ LocalIntelligenceService.kt
├─ browser/
├─ tools/
└─ providers/
```

## File Functions
- `AGENTS.md`: rules for local runtime and services.
- `LocalIntelligenceService.kt`: local AI orchestration and persistence-backed chat flow.
- `ToolConfirmationRegistry.kt`: turn-bound, idempotent inline tool approval state.
- `browser/`: GeckoView controller, WebExtension JavaScript bridge, session manager, DOM inspection, and browser safety handling.
- `tools/`: local tool mapping, browser tool wrapping, and execution helpers.
- `providers/`: local provider adapters and implementation-specific helpers.

## Key Source Code
- `LocalIntelligenceService.kt`: local conversation flow, stable conversation-id persistence before model turns, repository integration, concurrent per-conversation turns, target-switch-safe UI projection, rendered-history/context clearing, Hermes-style model-summary injection into the next main-session prompt, cancellation, and composer progress state.
- `browser/AndroidBrowserController.kt`: GeckoView interaction, navigation, and DOM-backed browser actions.
- `browser/GeckoBrowserRuntime.kt`: process-wide Gecko runtime plus built-in WebExtension JavaScript bridge.
- `browser/BrowserSessionManager.kt`: parent browser task state, pause/resume/cancel flow, and GeckoSession ownership.
- `browser/DomInspector.kt`: safe DOM summaries, selector mapping, and interaction helpers.
- `browser/BrowserResponseFormatter.kt`: compact browser JSON formatting for parent and sub-tool responses.
- `browser/BrowserActionCatalog.kt`: canonical model-exposed browser action inventory shared by tool schema and debug coverage.
- `browser/SafetyGuard.kt`: documents unrestricted local browser input policy; no credential/OTP gating.
- `src/debug/.../BrowserDebugActivity.kt`: debug-only ADB-launchable browser action harness using the production parent-tool path and an in-process test page.
- `tools/LocalToolMapper.kt`: local tool normalization, capability display-name mapping, and UI metadata mapping.
- `tools/BrowserUseToolset.kt`: parent browser tool wrapper and legacy alias compatibility.
- `providers/`: local provider implementations and compatibility adapters.
- Background session status is projected by `service/AiSessionNotificationService`; the local service must persist turn state before streaming and never cancel a turn merely because the visible target changes. Activity, completed messages, approvals, and issues use separate notification channels. Completed message history keys by Chat conversation, Project owner, or Agent group; Agent sender names remain distinct within the shared group thread. Completed messages are suppressed only while their exact source conversation is resumed, while approval remains alerting. Notification inline replies start directly from the persisted target conversation and must not mutate visible ChatScreen selection. Any host-authorized tool confirmation uses `tools/LocalToolMapper.displayLabel`; notification actions appear only while that request is pending, remain turn-bound, and fail closed when stale. `delegate_agent` emits named start/completion progress; only one stable active named delegation requests Live Update promotion.
- `providers/LocalProviderFactory.kt` if present: provider registration and lookup for local mode.
- `services/` if added in this subtree: local background orchestration and execution helpers.
