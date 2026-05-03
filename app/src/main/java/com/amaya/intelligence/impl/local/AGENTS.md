# Android Local Runtime Instructions

## Scope
- This file applies to `app/src/main/java/com/amaya/intelligence/impl/local/` and its children.

## Local Runtime Rules
- Keep device-local execution, persistence, browser automation, and runtime services in this subtree.
- This is the correct place for local tool orchestration, background services, browser control, and non-remote behaviors.
- Keep remote API assumptions out of this layer.
- Prefer Android-native patterns for services, background work, and local state.

## Coordination
- Coordinate with `data/local/` for storage-backed behavior and with `tools/` and `service/` for execution/runtime behavior.
- If a change needs remote integration, move the remote-specific part to the remote instruction subtree instead of broadening this file.

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
- `browser/`: WebView controller, session manager, DOM inspection, and browser safety handling.
- `tools/`: local tool mapping, browser tool wrapping, and execution helpers.
- `providers/`: local provider adapters and implementation-specific helpers.

## Key Source Code
- `LocalIntelligenceService.kt`: local conversation flow, persistence, and repository integration.
- `browser/AndroidBrowserController.kt`: WebView interaction, navigation, and DOM-safe browser actions.
- `browser/BrowserSessionManager.kt`: parent browser task state, pause/resume/cancel flow, and shared WebView ownership.
- `browser/DomInspector.kt`: safe DOM summaries, selector mapping, and interaction helpers.
- `browser/BrowserResponseFormatter.kt`: compact browser JSON formatting for parent and sub-tool responses.
- `browser/SafetyGuard.kt`: sensitive-input detection and user-decision gating.
- `tools/LocalToolMapper.kt`: local tool normalization and UI metadata mapping.
- `tools/BrowserUseToolset.kt`: parent browser tool wrapper and legacy alias compatibility.
- `providers/`: local provider implementations and compatibility adapters.
- `providers/LocalProviderFactory.kt` if present: provider registration and lookup for local mode.
- `services/` if added in this subtree: local background orchestration and execution helpers.
