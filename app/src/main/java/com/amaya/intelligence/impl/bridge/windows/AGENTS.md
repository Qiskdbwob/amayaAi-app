# Android Windows Bridge Runtime Instructions

## Scope
- This file applies to `app/src/main/java/com/amaya/intelligence/impl/bridge/windows/` and its children.
- It covers the Android-side Windows Bridge client, controller, event handling, tool mapping, and pairing state.

## Status

Windows Bridge logic is **incomplete and deferred** while product direction changes. See `docs/local/audits/VISION-HOLD.md`.

## Runtime Rules
- Keep bridge transport, client state, event mapping, and tool execution glue in this subtree.
- Use the shared bridge contract from `domain/bridge/` and keep the rest of the app provider-neutral.
- Do not mix local browser/runtime behavior into this subtree.
- Keep UI wiring, repositories, and background services separate from transport and mapping code.

## Editing Guidance
- Do not extract, rename, delete, or redesign this runtime without explicit renewed direction. Fix only build, security, or data-loss blockers.
- Prefer small, explicit changes in `client/`, `pairing/`, `services/`, and `tools/`.
- When bridge tool names, payload shapes, or risk rules change, verify the Android bridge runtime against the Windows bridge workspace.
- Before finishing, crosscheck the touched-area diff and recent bridge commits.
- If files, folders, or features change here, refresh this AGENTS file and the parent Android bridge instructions in the same change.

## File Tree
```text
impl/bridge/windows/
├─ AGENTS.md
├─ WindowsBridgeClientConfig.kt
├─ WindowsBridgeClientEvent.kt
├─ WindowsBridgeConnectionState.kt
├─ WindowsBridgeEnvelopeMapper.kt
├─ WindowsBridgeEnvelopeDispatcher.kt
├─ WindowsBridgeEventHandler.kt
├─ WindowsBridgeLogger.kt
├─ BridgeJson.kt
├─ WindowsBridgeConnectionStateMapper.kt
├─ WindowsBridgeSessionClient.kt
├─ pairing/
├─ service/                      # target extraction placeholder
├─ services/
├─ tools/
└─ transport/                    # target extraction placeholder
```

## File Functions
- `AGENTS.md`: runtime rules and bridge-scope routing.
- `WindowsBridgeSessionClient.kt`: transport client, reconnect logic, and event delivery.
- `WindowsBridgeEnvelopeMapper.kt`: tolerant bridge envelope mapping.
- `WindowsBridgeEnvelopeDispatcher.kt`: envelope-type to client-event dispatch separated from socket lifecycle.
- `WindowsBridgeClientConfig.kt`: bridge connection settings.
- `WindowsBridgeConnectionState.kt`: transport state enum.
- `WindowsBridgeClientEvent.kt`: client event hierarchy.
- `WindowsBridgeEventHandler.kt`: optional callback bridge for consumers.
- `WindowsBridgeLogger.kt`: bridge logging helpers.
- `BridgeJson.kt`: canonical bridge map/list to JSON conversion.
- `WindowsBridgeConnectionStateMapper.kt`: shared bridge-to-chat connection-state projection.
- `pairing/`: pairing payloads and persisted bridge profile state.
- `services/`: current bridge service facades and integration points.
- `service/`, `transport/`: reserved placeholders for the audited runtime split; no behavior moved yet.
- `tools/`: bridge tool definitions, execution, availability, and result mapping.

## Key Source Code
- `WindowsBridgeSessionClient.kt`: WebSocket lifecycle and message flow.
- `WindowsBridgeToolExecutor.kt`: bridge tool execution and pending-call tracking.
- `WindowsBridgeToolDefinitions.kt`: bridge tool catalog and metadata.
- `WindowsBridgeController.kt`: controller that owns the client and executor.
- `WindowsBridgeToolProvider.kt`: injectable facade used by repositories and executors.
- `WindowsBridgeApprovalMapper.kt`: approval request mapping.
