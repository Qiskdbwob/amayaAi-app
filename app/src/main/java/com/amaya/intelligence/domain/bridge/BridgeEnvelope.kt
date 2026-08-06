package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Transport-neutral wrapper for every message exchanged between the Android AI agent
 * and a Windows Bridge peer.
 *
 * The envelope deliberately carries a generic [payload] map rather than a typed sealed
 * hierarchy so that serializers (Moshi, kotlinx.serialization, or manual JSON) can map
 * directly to the shape documented in Phase 1 without requiring polymorphic adapters.
 * Typed models such as [BridgeToolCall], [BridgeToolResult], [BridgeToolError],
 * [ApprovalRequest], and [BridgeAuditEvent] can be converted to and from this map at the
 * edge of the transport.
 *
 * Phase 1 only defines this contract — no transport is wired up yet.
 */
data class BridgeEnvelope(
    /** Unique id for this envelope. Used for correlation/logging. */
    val id: String = UUID.randomUUID().toString(),
    /** Message type on the wire. See [BridgeMessageType.wireName]. */
    val type: BridgeMessageType,
    /** Session this envelope belongs to. `null` only for pre-session handshake frames. */
    val sessionId: String?,
    /** Originating device id (Android device id or Windows Bridge id). */
    val deviceId: String,
    /** Monotonically increasing sequence number per (session, sender). */
    val seq: Long,
    /** Epoch millis when the envelope was produced. */
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Free-form, JSON-serializable payload. Keys and value types depend on [type].
     * Prefer primitives, maps, and lists so that Moshi / kotlinx.serialization can
     * round-trip the content without custom adapters.
     */
    val payload: Map<String, Any?> = emptyMap(),
    /** Optional non-functional metadata (trace ids, client version, etc.). */
    val metadata: Map<String, String> = emptyMap()
)
