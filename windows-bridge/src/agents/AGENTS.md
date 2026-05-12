# Windows Bridge Agents Runtime Instructions

## Scope
- This file applies to `windows-bridge/src/agents/` and its children.
- It covers the abstraction for CLI coding-agent runtimes (opencode, claude-code, codex, ...) that the bridge hosts alongside native OS tools.

## Rules
- Keep every runtime behind the shared `AgentProvider` contract in `agent-provider.ts`.
- Runtime implementations must normalise events into `AgentEventPayload` before emitting them — no opencode-specific / claude-code-specific types may leak out of this subtree.
- Never expose secrets (API keys, OAuth tokens) through config/event payloads. Use `opencode-config-sanitizer.ts` or equivalent before sending bytes to Android.
- Share the bridge WebSocket transport — do not open new WS servers for agent events; the router emits and `websocket-server.ts` forwards.

## Editing Guidance
- Prefer small, explicit changes per runtime subfolder (e.g. `opencode/`).
- When adding a new runtime, mirror the folder structure: `<runtime>-binary.ts`, `<runtime>-server-manager.ts`, `<runtime>-rest-client.ts` / `<runtime>-stdio-client.ts`, `<runtime>-event-stream.ts`, `<runtime>-event-mapper.ts`, `<runtime>-agent-provider.ts`, `<runtime>-config-sanitizer.ts`.
- Before touching `agent-router.ts`, crosscheck the WebSocket handler in `../transport/websocket-server.ts` — the two move in lockstep.
- If files, folders, or features change here, refresh this AGENTS file and the parent `windows-bridge/AGENTS.md`.

## File Tree
```text
agents/
├─ AGENTS.md
├─ agent-provider.ts
├─ agent-router.ts
└─ opencode/
   ├─ opencode-agent-provider.ts
   ├─ opencode-binary.ts
   ├─ opencode-config-sanitizer.ts
   ├─ opencode-event-mapper.ts
   ├─ opencode-event-stream.ts
   ├─ opencode-rest-client.ts
   └─ opencode-server-manager.ts
```

## File Functions
- `agent-provider.ts`: abstract base class every CLI runtime extends.
- `agent-router.ts`: keeps a catalog of providers and fan-outs events.
- `opencode/opencode-binary.ts`: resolve an opencode executable on PATH / npm globals.
- `opencode/opencode-server-manager.ts`: spawn / monitor `opencode serve`.
- `opencode/opencode-rest-client.ts`: tiny fetch wrapper over the opencode REST surface.
- `opencode/opencode-event-stream.ts`: SSE consumer for `/event`.
- `opencode/opencode-event-mapper.ts`: convert opencode events → neutral `AgentEventPayload`.
- `opencode/opencode-config-sanitizer.ts`: redact secrets before the config leaves the bridge.
- `opencode/opencode-agent-provider.ts`: glues the pieces together and implements `AgentProvider`.
