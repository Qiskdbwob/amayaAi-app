package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Request from Android (or from the AI planner on Android's behalf) asking the Windows
 * Bridge to execute a named tool. Mirrors the shape of a typical tool-call on the wire
 * so it can be serialized directly as [BridgeEnvelope.payload] for
 * [BridgeMessageType.TOOL_CALL] envelopes.
 */
data class BridgeToolCall(
    /** Unique id for this tool call. Used to correlate results/errors/audit events. */
    val id: String = UUID.randomUUID().toString(),
    /** Session this call belongs to. */
    val sessionId: String,
    /** Fully-qualified tool name. See [BridgeToolNames] for stable identifiers. */
    val tool: String,
    /** Tool-specific arguments. JSON-serializable primitives, maps, and lists only. */
    val args: Map<String, Any?> = emptyMap(),
    /** Declared risk level for this invocation. */
    val risk: BridgeRiskLevel = BridgeRiskLevel.LOW,
    /**
     * Explicit hint from the caller that this call must be approved before execution.
     * The Windows Bridge may still require approval even when this is false, based on
     * its own policy.
     */
    val requiresApproval: Boolean = false,
    /** Epoch millis when the call was created on the caller side. */
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Soft execution budget in milliseconds. `null` means "use bridge default".
     * The bridge will respond with [BridgeToolErrorCode.TIMEOUT] if exceeded.
     */
    val timeoutMs: Long? = null,
    /** Free-form non-functional metadata (trace ids, origin, etc.). */
    val metadata: Map<String, String> = emptyMap()
)
