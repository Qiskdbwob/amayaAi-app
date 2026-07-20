# Chat, Project, and Agent Modes

Amaya uses one host-owned runtime with separate Chat, Project, and Agent Group scopes. The host selects owner, workspace, capability profile, recall boundary, and approval policy. The model cannot widen them.

## Capability matrix

| Capability | Chat | Project | Agent Group |
|---|---:|---:|---:|
| Web search and fetch | Yes | Yes | Yes |
| Global MCP servers | Yes | Yes | Yes |
| User memory and skills | Yes | Yes | Yes |
| Workspace files | No | Yes | Configurable |
| Terminal | No | Yes | Configurable |
| Browser automation | No | No | Configurable |
| Read-only subagents | No | Yes | Configurable |
| Named member delegation | No | No | Configurable |

The executor filters advertised definitions and rejects disabled execution. MCP remains global; there is no per-mode MCP allowlist.

## Ownership

Each conversation stores:

```text
assistant_mode: CHAT | PROJECT | AGENT
owner_id: null | project ID | agent-group ID
workspace_path: nullable host execution binding
```

Drawer history uses the active owner only. Session recall also uses mode and owner ID. Agent groups sharing one workspace cannot recall each other's sessions.

## Settings scopes

Settings exposes four native tabs:

- **Global:** models, provider connections, MCP, appearance, app information.
- **Chat:** user memory, reusable skills, recall, reminders, privacy boundaries.
- **Project:** project instructions, imported references, project memory/review, terminal policy.
- **Agent:** group instructions, imported references, capability toggles, group context/review, terminal policy.

## Projects

A Project has stable ID, replaceable workspace path, instructions, imported reference documents, project memory, and owned chat sessions. Legacy path-owned conversations remap to the project ID when opened. Deleting a project removes project history and imported references; workspace files remain untouched.

## Agent groups

An Agent Group owns one workspace, shared instructions, references, capability profile, sessions, and many member agents. Every member has a name, role, and instructions. The drawer for an active group shows only that group's members, delegation tasks, and conversation history.

`delegate_agent` accepts an exact member name and focused task. The host verifies membership, runs the member with read-only research tools, and persists status/result in `delegation_tasks`. Full conversation history is not copied.

## Terminal policy

Project and enabled Agent sessions run `/system/bin/sh -c`. Redirection, pipes, chaining, substitutions, and multiline commands work.

Terminal policy order:

1. host hard block;
2. Declined wildcard;
3. Trusted wildcard;
4. explicit review.

Wildcards match the full command. Trusted patterns cannot override host hard blocks.

## Context and retention

Current conversation history receives 55% of the input budget. Older messages compress only when needed.

Past-session recall:

- lexical relevance and coverage;
- exact Chat/Project/Agent owner scope;
- maximum five results;
- 900-token section ceiling;
- 90-day ranking decay without deletion;
- matching user text plus deterministic summary, not raw assistant/tool output.

Imported references are app-owned copies. At most three text references and 8,000 characters each enter context. Reference contents are labeled untrusted data.

## Removed surfaces

- Persona repository, UI, navigation, and prompt section
- user-memory pending proposals
- self-improvement auto-save modes
- memory-write “Tool only” labels
- non-shell parser blocking standard terminal grammar
- backend/UI tool-output truncation

## Deferred only when needed

- SQLite FTS/vector recall: add after measured JSONL latency or relevance failures.
- Large reference indexing: add after the bounded three-document text path becomes insufficient.
- Writable delegation: excluded; named delegation remains read-only for safety.

## Acceptance criteria

```text
Chat has no terminal, workspace, browser automation, subagents, or delegation.
Project has workspace and terminal, no browser automation or named delegation.
Agent capability toggles affect both schemas and execution.
MCP remains global in all local modes.
Project drawer history never crosses project owner IDs.
Agent drawer shows only active-group members, tasks, and history.
Agent recall never crosses group owner IDs.
One group can contain many agents.
Project and Agent references enter bounded untrusted context.
Declined terminal patterns reject before review.
Host hard blocks override Trusted patterns.
Persona, USER_PROFILE proposals, and auto-save mode references are absent.
```
