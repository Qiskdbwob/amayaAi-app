# Android App Instructions

## Scope
- This file applies to `app/` and its children.
- It covers the Android module, Gradle configuration, Compose UI, Hilt wiring, persistence, and runtime services.

## Android Rules
- Keep Kotlin, Compose, Hilt, and Gradle changes consistent with the current code style.
- Preserve the split between remote and local responsibilities.
- Keep UI, domain, data, implementation, and service code separated by package intent.
- Keep memory/skills domain rules in `domain/memory/` and `domain/skills/`; keep persistence implementations in `data/local/` + `data/repository/`; keep user-facing controls in `ui/screens/amaya/` and `ui/screens/selfimprovement/`.
- Keep browser automation logic inside `impl/local/browser/`, browser UI inside `ui/activities/browser/` and `ui/screens/browser/`, and the parent tool wrapper inside `tools/BrowserUseToolset.kt`.
- Do not move extension-specific logic into the Android module.

## Remote vs Local
- Remote Android work is handled by the deeper instruction files under `data/remote/` and `impl/ide/antigravity/`.
- Local Android work is handled by the deeper instruction files under `data/local/` and `impl/local/`.
- If a change touches both, update the shared Android file first, then the more specific subtree file.

## Testing and Runtime
- Keep JVM test behavior stable. If you touch local unit tests that call Android logging APIs, preserve the existing default-values setup used by the project.
- Respect existing foreground service, WorkManager, and persistence patterns.
- File-backed local repositories must serialize read-modify-write operations with a repository-local lock/mutex and must not report queued/ignored work as applied.

## File Tree
```text
app/
├─ AGENTS.md
├─ build.gradle.kts
├─ schemas/
└─ src/
	└─ main/
		├─ AndroidManifest.xml
		├─ assets/
		├─ java/
		│	├─ com/amaya/intelligence/data/local/files/
		│	├─ com/amaya/intelligence/domain/memory/
		│	├─ com/amaya/intelligence/domain/skills/
		│	├─ com/amaya/intelligence/impl/local/browser/
		│	├─ com/amaya/intelligence/tools/
		│	├─ com/amaya/intelligence/ui/activities/browser/
		│	├─ com/amaya/intelligence/ui/components/shared/
		│	├─ com/amaya/intelligence/ui/screens/browser/
		│	└─ com/amaya/intelligence/utils/
		└─ res/
```

## File Functions
- `AGENTS.md`: Android-wide development rules and scope routing.
- `build.gradle.kts`: Android module build config, dependencies, and test settings.
- `schemas/`: exported Room schema snapshots.
- `src/main/AndroidManifest.xml`: app components, services, receivers, and permissions.
- `src/main/java/com/amaya/intelligence/data/remote/`: remote APIs, settings, and provider models.
- `src/main/java/com/amaya/intelligence/data/local/`: local storage and database layer, including Room and file-backed stores.
- `src/main/java/com/amaya/intelligence/impl/ide/antigravity/`: remote IDE runtime and Antigravity integration.
- `src/main/java/com/amaya/intelligence/impl/local/`: local runtime, browser automation, services, and background behavior.
- `src/main/java/com/amaya/intelligence/impl/local/browser/`: WebView controller, session manager, DOM inspection, and safety guard.
- `src/main/java/com/amaya/intelligence/tools/`: built-in local tools, memory/skill/recall tools, browser tool wrappers, and tool execution helpers.
- `src/main/java/com/amaya/intelligence/service/`: app services, receivers, and workers.
- `src/main/java/com/amaya/intelligence/ui/activities/browser/`: fullscreen browser operator activity.
- `src/main/java/com/amaya/intelligence/ui/`: Compose UI screens, activities, and theme.
- `src/main/java/com/amaya/intelligence/ui/screens/browser/`: browser operator screen and control dock.
- `src/main/java/com/amaya/intelligence/ui/components/shared/`: reusable shared UI components, including browser tool cards.
- `src/main/java/com/amaya/intelligence/utils/`: temporary runtime utilities such as local stream profiling.

## Key Source Code
- `src/main/java/com/amaya/intelligence/domain/`: shared state, models, memory/skill domain logic, and service contracts used across remote/local flows.
- `src/main/java/com/amaya/intelligence/data/remote/api/`: provider clients such as Gemini, OpenAI, Anthropic, and settings managers.
- `src/main/java/com/amaya/intelligence/data/remote/mcp/`: MCP client and tool executor integration.
- `src/main/java/com/amaya/intelligence/data/repository/`: repository layer that orchestrates AI, personas, files, conversations, memory, skills, pending proposals, context recall, and maintenance.
- `src/main/java/com/amaya/intelligence/data/local/db/`: Room database, entities, and DAOs.
- `src/main/java/com/amaya/intelligence/data/local/files/`: file-backed stores for local session recall and reusable skill documents.
- `src/main/java/com/amaya/intelligence/impl/common/`: mappers and shared implementation utilities.
- `src/main/java/com/amaya/intelligence/impl/ide/antigravity/`: remote IDE provider, protocol, event handling, and streaming client.
- `src/main/java/com/amaya/intelligence/impl/local/`: local AI service, browser runtime, and local runtime integrations.
- `src/main/java/com/amaya/intelligence/tools/`: file, shell, memory, todo, reminder, subagent, and browser tools.
- `src/main/java/com/amaya/intelligence/ui/`: chat, settings, browser, and remote/local UI entry points.
