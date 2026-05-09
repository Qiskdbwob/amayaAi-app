package com.amaya.intelligence.domain.bridge

/**
 * Canonical message types exchanged between the Android AI agent and a Windows Bridge.
 *
 * The [wireName] is the stable string used on the wire (WebSocket/JSON payload).
 * Kotlin enum identifiers follow SCREAMING_SNAKE_CASE for readability; pairing with
 * [wireName] keeps the on-wire contract decoupled from refactors on either side.
 *
 * Phase 1 only defines the contract. No runtime transport is implemented here.
 */
enum class BridgeMessageType(val wireName: String) {
    // Session lifecycle
    SESSION_CREATED("session.created"),
    SESSION_CLOSED("session.closed"),
    DEVICE_PAIRED("device.paired"),
    DEVICE_DISCONNECTED("device.disconnected"),

    // Screen transport
    SCREEN_FRAME("screen.frame"),
    SCREEN_CAPTURE_RESULT("screen.capture_result"),

    // Tool execution
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    TOOL_ERROR("tool.error"),

    // Agent status
    AGENT_STATUS("agent.status"),
    AGENT_STEP("agent.step"),
    AGENT_PAUSED("agent.paused"),
    AGENT_RESUMED("agent.resumed"),
    AGENT_CANCELLED("agent.cancelled"),

    // Approval flow
    APPROVAL_REQUEST("approval.request"),
    APPROVAL_ACCEPTED("approval.accepted"),
    APPROVAL_REJECTED("approval.rejected"),

    // Audit + transport-level error
    AUDIT_EVENT("audit.event"),
    ERROR("error");

    companion object {
        private val byWireName: Map<String, BridgeMessageType> =
            values().associateBy { it.wireName }

        /** Parse a wire-name into an enum, returning null if unknown. */
        fun fromWireName(value: String?): BridgeMessageType? =
            if (value == null) null else byWireName[value]
    }
}
