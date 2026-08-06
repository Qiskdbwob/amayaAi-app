# Android Bridge Contract Instructions

## Scope
- This file applies to `app/src/main/java/com/amaya/intelligence/domain/bridge/` and its children.
- It covers the shared bridge protocol models used by Android and the Windows bridge.

## Contract Rules
- Keep this package platform-neutral and dependency-free.
- Use plain Kotlin data classes, enums, and objects only.
- Do not add Android runtime code, DI, persistence, transport, or UI here.
- Keep wire names additive and stable; do not repurpose existing values.
- If the shared bridge contract changes, update the related Android and Windows bridge instructions in the same change.

## Editing Guidance
- Prefer small, explicit edits in the shared protocol models and enums.
- Before changing any contract type, crosscheck recent commits in both the Android bridge runtime and the Windows bridge workspace.
- If files, folders, or features change here, refresh this AGENTS file and the parent Android instructions.

## File Tree
```text
domain/bridge/
├─ AGENTS.md
├─ BridgeApproval.kt
├─ BridgeAuditEvent.kt
├─ BridgeEnvelope.kt
├─ BridgeError.kt
├─ BridgeMessageType.kt
├─ BridgeRiskPolicy.kt
├─ BridgeSessionState.kt
├─ BridgeToolCall.kt
├─ BridgeToolNames.kt
└─ BridgeToolResult.kt
```

## File Functions
- `AGENTS.md`: contract-only rules and routing.
- `BridgeEnvelope.kt`: wire-level wrapper around each bridge message.
- `BridgeMessageType.kt`: stable on-wire message type names.
- `BridgeToolCall.kt`: tool call payload contract.
- `BridgeToolResult.kt`: tool result payload contract.
- `BridgeError.kt`: error payload and codes.
- `BridgeApproval.kt`: approval request/decision/status contracts.
- `BridgeRiskPolicy.kt`: risk and permission contract helpers.
- `BridgeSessionState.kt`: session and capability snapshot contracts.
- `BridgeAuditEvent.kt`: audit event contract.
- `BridgeToolNames.kt`: stable tool-name constants.

## Key Source Code
- `BridgeEnvelope.kt`: shared envelope contract.
- `BridgeToolNames.kt`: canonical tool identifiers.
- `BridgeMessageType.kt`: canonical message types.
