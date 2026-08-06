# `domain/bridge` — Windows Bridge Shared Protocol (Phase 1)

This package defines the **platform-neutral contract** used by the Android AI agent to
talk to a future Windows Bridge peer. It is the shared vocabulary that Android and the
Windows-side executor will serialize to JSON and exchange over a transport that will be
wired up in a later phase.

## Scope

Phase 1 only introduces the contract. This package intentionally does **not** include:

- any transport (WebSocket, HTTP, IPC) or streaming client
- any executor that runs the declared tools
- any persistence, DI, Compose UI, or Context-dependent code
- any changes to the existing local agent loop, Antigravity remote flow, browser
  operator, memory, skills, or self-improvement subsystems

## Constraints enforced here

- No `android.*`, `androidx.*`, Hilt, Room, DataStore, or Compose imports.
- Plain Kotlin `data class`, `enum class`, and `object` declarations.
- No Moshi/kotlinx.serialization codegen. Payloads ride on `Map<String, Any?>` so any
  serializer at the transport edge can round-trip the shape described in `fase-1.md`.
- Enum `wireName` values are the stable on-wire identifiers. Keep them additive — do
  not repurpose existing values.

## File map

| File | Purpose |
| --- | --- |
| `BridgeEnvelope.kt` | Wire-level wrapper around every message. |
| `BridgeMessageType.kt` | Enum of message types (`tool.call`, `approval.request`, ...). |
| `BridgeToolCall.kt` | Tool-call request payload. |
| `BridgeToolResult.kt` | Success / cancelled / timeout response payload. |
| `BridgeError.kt` | `BridgeToolError` + transport-level `BridgeError` + error codes. |
| `BridgeApproval.kt` | `ApprovalRequest`, `ApprovalDecision`, `ApprovalStatus`. |
| `BridgeRiskPolicy.kt` | `BridgeRiskLevel`, `BridgePermissionDecision`, helper defaults. |
| `BridgeSessionState.kt` | `BridgeSessionStatus`, `BridgeCapability`, session snapshot. |
| `BridgeAuditEvent.kt` | Audit log entry, event types, actors. |
| `BridgeToolNames.kt` | Stable constants for `BridgeToolCall.tool`. |

## What the next phases add

- Phase 2: minimal WebSocket/JSON codec that round-trips `BridgeEnvelope`.
- Phase 3: Windows Bridge executor (Electron + native helper) that consumes the
  contract, with the Android side producing approvals and surfacing screen frames.
- Phase 4+: policy engine, audit storage, and integration with the AI planner.

Until then, treat every model here as **contract-only**. Do not couple runtime logic
from other packages to these types.
