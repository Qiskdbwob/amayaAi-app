# `impl/bridge/windows/tools` — Bridge Tool Executor Adapter (Phase 3 + 3B)

This package turns the Phase 2 Windows Bridge client into something the existing
Android tool system can call. Phase 3 added the adapter classes; Phase 3B wires them
into `AiRepository.buildToolDefinitions()` and `McpToolExecutor.execute()` so the AI
agent can actually use bridge tools.

## Scope

Phase 3 introduced:

- Bridge tool catalog + risk/approval metadata (`WindowsBridgeToolDefinitions`).
- Static registry filtered by a per-phase `enabledByDefault` flag
  (`WindowsBridgeToolRegistry`).
- Availability snapshot based on `WindowsBridgeSessionClient.connectionState`
  (`WindowsBridgeToolAvailability`).
- Mapping layer: Android args → `BridgeToolCall`, and `BridgeToolResult` /
  `BridgeToolError` → existing `ToolResult`
  (`WindowsBridgeToolMapper`, `WindowsBridgeToolResultMapper`).
- Executor that owns the pending-call map and times out safely
  (`WindowsBridgeToolExecutor`).
- Approval request → `ConfirmationRequest` mapping
  (`WindowsBridgeApprovalMapper`). UI wiring is tagged TODO for Phase 6.

Phase 3B added:

- `WindowsBridgeController` — `@Singleton` Hilt object that owns the client +
  executor. Exposes `connect()`, `disconnect()`, `setAgentControlEnabled()`,
  `availability()`, and `visibleToolNames()`.
- `WindowsBridgeToolProvider` — now `@Inject`able facade that delegates to the
  controller. Consumed by `AiRepository.buildToolDefinitions()` and
  `McpToolExecutor.execute()`.
- Agent-Control gate: MEDIUM-risk input tools (`mouse.click`, `keyboard.type`,
  `keyboard.hotkey`, `clipboard.write`) are hidden from the model until
  `setAgentControlEnabled(true)` is called. LOW-risk tools (`screen.capture`,
  `window.list`) are visible as soon as the client is connected.

Out of scope:

- Electron, native helper, or any Windows-side executor.
- Touching the non-bridge code paths of `ToolExecutor` or `McpToolExecutor`.
- UI approval dialogs, pairing UI, or a foreground service.
- Re-using Antigravity remote session code.

## Agent loop integration

```
AiRepository.buildToolDefinitions()
  = local tools + MCP tools + bridge tools (when connected + gated)

McpToolExecutor.execute(toolName, ...)
  → mcp__*     : mcpClientManager.callTool(...)
  → bridge tool: windowsBridgeToolProvider.executeBridgeTool(...)
  → otherwise  : toolExecutor.execute(...)
```

Only `McpToolExecutor.execute()` routes bridge tools. `ToolExecutor` remains
unchanged, so no existing tool definition or confirmation flow is altered.

## Safety defaults

| Tool | Risk | Enabled | Visible when connected | Agent-Control gated |
| --- | --- | --- | --- | --- |
| `screen.capture` | LOW | yes | yes | no |
| `window.list` | LOW | yes | yes | no |
| `mouse.click` | MEDIUM | yes | only with Agent Control | yes |
| `keyboard.type` | MEDIUM | yes | only with Agent Control | yes |
| `keyboard.hotkey` | MEDIUM | yes | only with Agent Control | yes |
| `clipboard.write` | MEDIUM | yes | only with Agent Control | yes |
| `file.list/read` | MEDIUM | yes | only with Agent Control | yes |
| `shell.cancel` | MEDIUM | yes | only with Agent Control | yes |
| `clipboard.read` | HIGH | no | never | — |
| `file.write/edit/delete` | HIGH | no | never unless bridge policy explicitly enables them | — |
| `shell.run` | HIGH | no | never unless bridge policy explicitly enables it | — |
| `browser.*`, `ui.*` | MEDIUM/HIGH | no | never (phase 3) | — |

`WindowsBridgeToolExecutor.execute` always returns a `ToolResult`. Failure modes:

- `unknown(toolName)` — tool not registered.
- `disabled(toolName)` — registered but `enabledByDefault = false`.
- `unavailable(toolName, reason)` — bridge offline / paused / closing / missing
  sessionId / Agent Control not unlocked.
- `timeout(toolName, ...)` — per-call budget exceeded (defaults: 30 s, 45 s for
  `screen.capture`, 60 s for `shell.*`).
- `toError(...)` — bridge reported `BridgeToolError`.

## Pending-call bookkeeping

- `toolCallId → CompletableDeferred<Outcome>` via `ConcurrentHashMap`.
- Completed by `ToolResultReceived` / `ToolErrorReceived` events keyed on
  `toolCallId`.
- Failed with `SESSION_CLOSED` whenever the transport drops, the peer closes the
  session, the device unpairs, or a protocol error arrives.
- Always cleaned up in a `finally` block so a timed-out call cannot leak.

## Approval forwarding

`WindowsBridgeToolExecutor.addApprovalListener { request -> ... }` surfaces incoming
`ApprovalRequest` envelopes. Phase 3 only delivers them; the chat / tool-card
approval UI will subscribe in Phase 6.

## How to connect

```kotlin
val controller: WindowsBridgeController = …  // @Inject
controller.connect(
    WindowsBridgeClientConfig(
        host = "192.168.1.25",
        port = 47111,
        deviceId = androidDeviceId,
        sessionId = null, // fresh pair
    )
)
// Later, once the user explicitly unlocks input tools:
controller.setAgentControlEnabled(true)
```

Until `connect(...)` is called, `AiRepository` advertises zero bridge tools and the
model never sees them.

