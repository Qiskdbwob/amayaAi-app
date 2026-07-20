# Android Local Data Instructions

## Scope
- This file applies to `app/src/main/java/com/amaya/intelligence/data/local/` and its children.

## Local Data Rules
- Keep local persistence, database entities, DAOs, file stores, and cached state in this subtree.
- Treat this layer as device-local storage only.
- Keep remote API clients, network transport, and provider logic out of this subtree.
- Prefer the existing Room and storage patterns used by the app.
- File-backed stores belong under `files/` and should expose file locations only; repository logic, classification, and orchestration stay outside this subtree.
- Do not name plain file stores as databases; reserve `db/` and database terminology for Room database classes.
- When a Room schema or migration changes, crosscheck `app/schemas/` and `app/schemas/AGENTS.md` before finishing.

## Coordination
- Coordinate with `impl/local/` for runtime behavior that consumes local storage.
- If a change needs a remote dependency, move that part to the remote instruction subtree instead of broadening local storage responsibilities.
- Before closing a local-storage change, check the current workspace diff and recent commits for the affected database or file store area.

## File Tree
```text
data/local/
├─ AGENTS.md
├─ dao/
├─ entity/
├─ files/
│	├─ FileSessionStore.kt
│	├─ FileSkillStore.kt
│	└─ FileWorkspaceMemoryStore.kt
└─ db/
	├─ migrations/
	└─ AppDatabase.kt
```

## File Functions
- `AGENTS.md`: rules for local persistence and storage.
- `entity/`: Room entities for projects, files, metadata, conversations, and cron jobs. Provider/model settings live in DataStore, not Room.
- `dao/`: Room DAO interfaces for data access.
- `db/AppDatabase.kt`: Room database definition and wiring.
- `db/migrations/`: Database migration scripts.
- `app/schemas/`: exported Room schema snapshots that must stay aligned with migration updates.
- `files/FileSessionStore.kt`: file locations for session recall records and summaries.
- `files/FileSkillStore.kt`: file locations and safe names for local reusable skill documents.
- `files/FileWorkspaceMemoryStore.kt`: stable workspace UUID metadata, canonical-root resolution, and explicit moved-root remapping for memory/session scoping.

## Key Source Code
- `entity/ProjectEntity.kt`: persisted project metadata.
- `entity/FileEntity.kt`: local file index entries.
- `entity/FileFtsEntity.kt`: full-text-search support for local files.
- `entity/FileMetadataEntity.kt`: detailed file information.
- `entity/ConversationEntity.kt`: stored conversation records with explicit Chat/Project/Agent owner scope plus active Agent member ID.
- `entity/AgentGroupEntity.kt`: persisted shared group workspace, instructions, and references.
- `entity/AgentEntity.kt`: many named roles, instructions, per-agent capability profiles, and private reference paths belonging to one agent group.
- `entity/DelegationTaskEntity.kt`: persisted intra-group task status and result.
- `dao/AgentDao.kt`: agent-group/member list, create, update, and delete persistence.
- `dao/DelegationTaskDao.kt`: group-scoped delegation history.
- `entity/CronJobEntity.kt`: scheduled local job records, optionally owned by one Agent.
- `dao/ProjectDao.kt`: project persistence access.
- `dao/FileDao.kt`: file index and FTS access.
- `dao/ConversationDao.kt`: conversation persistence access.
- `dao/CronJobDao.kt`: cron job persistence access, including Agent-scoped lists.
- `db/AppDatabase.kt`: database configuration and migration wiring.
- `files/FileSessionStore.kt`: file-backed session recall root, JSONL record file, summary file, and legacy `sessions.db` migration.
- `files/FileSkillStore.kt`: file-backed skill root, `SKILL.md`, metadata file, and skill-name sanitization.
