# Repository Instructions

## Scope
- This file applies to the whole monorepo.
- There are three major areas in this repo:
  - `amaya-remote-extension/` for the VS Code extension.
  - `app/` for the Android app.
  - `windows-bridge/` for the Electron Windows bridge and native helper.
- Read the nearest AGENTS file before making changes in a subdirectory.
- Before editing, crosscheck the current workspace tree, `git status`, and recent commit diffs for the touched area.

## Build
- ./gradlew installDebug
- ./gradlew assembleDebug
- ./gradlew :app:installBenchmark
- ./gradlew :baselineprofile:connectedBenchmarkAndroidTest
- npx @vscode/vsce package
- (cd windows-bridge && npm run verify)
- (cd windows-bridge && npm run package)

## General Rules
- Keep changes minimal and local to the requested area.
- Do not modify unrelated modules.
- Prefer existing patterns over introducing new abstractions.
- If a task spans extension and Android, inspect both module-level instruction files before editing.
- If a task spans Android and the Windows bridge, inspect the shared bridge contract plus the relevant workspace instructions before editing.
- For browser automation changes, review `docs/android-browser-use-toolcall.md` and `docs/browser-toolcall-schema.ts` first.
- When implementing code, prefer standard OOP structure and align with the existing architecture.
- Do not introduce redundancy, duplicate logic, or dead code.
- Before building a feature or adding new code paths, inspect the codebase structure first and choose the most natural placement for the change.
- If a task adds, removes, or moves any file, folder, or feature, update the nearest AGENTS file and any parent AGENTS that describe that scope in the same change.
- If the right location is unclear, trace the nearest related modules, controllers, services, or mappers before editing.
- Before wrapping up, crosscheck the touched area's git diff and nearby recent commits.

## Area Routing
- Extension work belongs under `amaya-remote-extension/AGENTS.md`.
- Android-wide work belongs under `app/AGENTS.md`.
- Windows bridge work belongs under `windows-bridge/` and should be crosschecked against the shared Android bridge contract when the feature spans both sides.
- Remote Android implementation details belong to the deeper Android remote instruction files.
- Local Android implementation details belong to the deeper Android local instruction files.

## File Tree
```text
amaya/
├─ AGENTS.md
├─ amaya-remote-extension/
├─ app/
├─ baselineprofile/
├─ docs/
└─ windows-bridge/
```

## File Functions
- `AGENTS.md`: repo-wide coordination and routing rules.
- `amaya-remote-extension/AGENTS.md`: extension-specific rules for TypeScript, controllers, IDE abstraction, and tests.
- `docs/`: browser toolcall references plus the current provider/model settings contract in `models.md`.
- `app/AGENTS.md`: Android-wide rules for Compose, Gradle, Hilt, persistence, runtime services, and browser UI/runtime work.
- `app/src/main/java/com/amaya/intelligence/data/remote/AGENTS.md`: Android remote API, settings, and model mapping guidance.
- `app/src/main/java/com/amaya/intelligence/data/local/AGENTS.md`: Android local storage and database guidance.
- `app/src/main/java/com/amaya/intelligence/impl/ide/antigravity/AGENTS.md`: Antigravity remote runtime guidance.
- `app/src/main/java/com/amaya/intelligence/domain/bridge/AGENTS.md`: shared Android bridge contract guidance.
- `app/src/main/java/com/amaya/intelligence/impl/bridge/windows/AGENTS.md`: Android Windows bridge runtime guidance.
- `app/src/main/java/com/amaya/intelligence/impl/local/AGENTS.md`: local Android runtime and service guidance.
- `windows-bridge/AGENTS.md`: Electron Windows bridge and native helper guidance.
- `windows-bridge/native-helper/AGENTS.md`: C# helper-specific Win32 and JSON-RPC guidance.
- `baselineprofile/AGENTS.md`: macrobenchmark baseline-profile generator guidance.

## Key Source Code
- `amaya-remote-extension/src/extension.ts`: extension bootstrap and command registration.
- `amaya-remote-extension/src/controllers/`: message handling, lifecycle, quota, workspace, and stream orchestration.
- `amaya-remote-extension/src/ide/`: provider-neutral IDE contracts and Antigravity-specific implementation.
- `amaya-remote-extension/src/connectivity/`: WebSocket and transport wiring.
- `amaya-remote-extension/test/`: raw captures, debug harnesses, and reverse-engineering scripts.
- `app/src/main/java/com/amaya/intelligence/domain/`: shared models, interfaces, and app-level contracts.
- `app/src/main/java/com/amaya/intelligence/data/remote/`: remote API clients, provider presets/discovery, settings, and transport-facing models.
- `app/src/main/java/com/amaya/intelligence/data/local/`: local entities, DAOs, Room database, file-backed stores, and stable workspace-memory UUID metadata.
- `app/schemas/`: exported Room schema snapshots and versioning notes.
- `app/src/main/java/com/amaya/intelligence/data/repository/`: repositories and orchestration for AI, persona, memory, skills, session recall, and maintenance.
- `app/src/main/java/com/amaya/intelligence/domain/memory/`: user/workspace memory classification, safety, normalization, proposals, dedupe, and compaction domain logic; no global catch-all or model-owned importance score.
- `app/src/main/java/com/amaya/intelligence/domain/skills/`: reusable skill domain models and patch/usage helpers.
- `app/src/main/java/com/amaya/intelligence/impl/ide/antigravity/`: Antigravity provider, protocol, client, and event mapping.
- `app/src/main/java/com/amaya/intelligence/impl/ide/opencode/`: Opencode CLI agent client, models, and IntelligenceService driven by the Windows Bridge `agent.*` envelopes.
- `app/src/main/java/com/amaya/intelligence/impl/common/mappers/ModelUiMapper.kt`: provider/runtime model mapping into shared chat options.
- `app/src/main/java/com/amaya/intelligence/impl/local/`: local runtime, browser automation, service, and tool execution flow.
- `app/src/main/java/com/amaya/intelligence/ui/activities/browser/`: fullscreen browser operator entry point.
- `app/src/main/java/com/amaya/intelligence/ui/activities/models/`: Manage Models entry point for provider connections and chat-visible models.
- `app/src/main/java/com/amaya/intelligence/ui/activities/opencode/`: activities for Opencode landing, chat, and settings screens.
- `app/src/main/java/com/amaya/intelligence/ui/screens/browser/`: browser operator Compose screen and control dock.
- `app/src/main/java/com/amaya/intelligence/ui/screens/opencode/`: Opencode landing and settings Compose screens.
- `app/src/main/java/com/amaya/intelligence/ui/screens/chat/opencode/`: Opencode chat Compose wrapper reusing the shared ChatScreen.
- `app/src/main/java/com/amaya/intelligence/ui/components/shared/ModelIcon.kt`: shared model/provider leading-icon resolver and renderer.
- `app/src/main/java/com/amaya/intelligence/ui/components/shared/BrowserToolCallCard.kt`: browser parent tool renderer in chat.
- `app/src/main/java/com/amaya/intelligence/tools/BrowserUseToolset.kt`: parent browser tool wrapper and legacy aliases.
- `app/src/main/java/com/amaya/intelligence/tools/ToolExecutor.kt`: local capability dispatcher, host-owned workspace context, approval, and legacy display mapping boundary.
- `app/src/main/java/com/amaya/intelligence/tools/WorkspacePathResolver.kt`: host-side relative workspace-path resolution and boundary enforcement.
- `app/src/main/java/com/amaya/intelligence/tools/CapabilityToolMapper.kt`: canonical capability-operation mapping to existing handlers.
- `app/src/main/java/com/amaya/intelligence/tools/MemoryManageTool.kt`: active user/workspace saved-memory list/search/update tool with optimistic version checks. `About You` writes require explicit model tool invocation; chat text, reflection, and pending proposals cannot write it. Daily-log, global Important Memory, model-owned importance, and memory archive/delete/restore capabilities are removed.
- `app/src/main/java/com/amaya/intelligence/tools/SkillManageTool.kt`: explicit reusable-skill management tool.
- `app/src/main/java/com/amaya/intelligence/utils/LocalStreamPerfLog.kt`: temporary local streaming profiler.
- `app/src/main/java/com/amaya/intelligence/impl/local/browser/`: WebView controller, session manager, DOM inspection, and safety guard.
- `windows-bridge/`: Electron Windows bridge main process, transport, permissions, audit, and native helper runtime.
- `windows-bridge/src/agents/`: CLI coding-agent runtimes (opencode, claude-code, codex) behind the shared `AgentProvider` contract.
- `baselineprofile/src/main/java/com/amaya/intelligence/baselineprofile/BaselineProfileGenerator.kt`: macrobenchmark that captures the app cold-start baseline profile bundled into `:app`.
