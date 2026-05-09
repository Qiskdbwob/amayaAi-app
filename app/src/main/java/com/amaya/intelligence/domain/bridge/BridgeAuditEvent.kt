package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Event type recorded in the bridge audit log. Ordered roughly by typical lifecycle:
 * request → approval → execution → outcome → session transitions.
 */
enum class BridgeAuditEventType(val wireName: String) {
    TOOL_REQUESTED("tool_requested"),
    APPROVAL_REQUESTED("approval_requested"),
    APPROVAL_ACCEPTED("approval_accepted"),
    APPROVAL_REJECTED("approval_rejected"),
    TOOL_STARTED("tool_started"),
    TOOL_SUCCEEDED("tool_succeeded"),
    TOOL_FAILED("tool_failed"),
    TOOL_CANCELLED("tool_cancelled"),
    SESSION_PAUSED("session_paused"),
    SESSION_RESUMED("session_resumed"),
    SESSION_CLOSED("session_closed");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeAuditEventType? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Actor responsible for producing an audit event. Helps distinguish between actions
 * initiated by the human, the planner, the bridge, the native helper, or the system
 * itself (timeouts, heartbeats, etc.).
 */
enum class BridgeAuditActor(val wireName: String) {
    ANDROID_USER("android_user"),
    ANDROID_AGENT("android_agent"),
    WINDOWS_BRIDGE("windows_bridge"),
    NATIVE_HELPER("native_helper"),
    SYSTEM("system");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeAuditActor? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Audit log entry describing one observable event in the bridge's lifecycle. Entries
 * are append-only and are expected to be stored both on-device (Android) and on the
 * bridge side for later inspection by the user.
 */
data class BridgeAuditEvent(
    /** Unique id for this audit entry. */
    val id: String = UUID.randomUUID().toString(),
    /** Session this event belongs to. */
    val sessionId: String,
    /** Correlates with [BridgeToolCall.id] when relevant. */
    val toolCallId: String? = null,
    /** Event type. */
    val eventType: BridgeAuditEventType,
    /** Tool name when the event is tool-scoped. */
    val tool: String? = null,
    /** Risk classification at the time the event was recorded. */
    val risk: BridgeRiskLevel? = null,
    /** Policy decision applied when the event was recorded, if any. */
    val decision: BridgePermissionDecision? = null,
    /**
     * Redacted preview of the originating args. Must never contain secrets or raw
     * binary payloads.
     */
    val argsPreview: Map<String, Any?> = emptyMap(),
    /** Redacted preview of the result payload. Same redaction rules as args. */
    val resultPreview: Map<String, Any?> = emptyMap(),
    /** Epoch millis when the event was produced. */
    val timestamp: Long = System.currentTimeMillis(),
    /** Who produced the event. */
    val actor: BridgeAuditActor
)
