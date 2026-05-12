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
    ERROR("error"),

    // CLI Coding Agent runtime (opencode, claude-code, codex, ...)
    // Android → Bridge
    AGENT_RUNTIME_STATUS_REQUEST("agent.runtime.status.request"),
    AGENT_RUNTIME_START("agent.runtime.start"),
    AGENT_RUNTIME_STOP("agent.runtime.stop"),
    AGENT_RUNTIME_RESTART("agent.runtime.restart"),
    AGENT_CONFIG_REQUEST("agent.config.request"),
    AGENT_PROVIDER_LIST_REQUEST("agent.provider.list.request"),
    AGENT_MODEL_LIST_REQUEST("agent.model.list.request"),
    AGENT_MCP_LIST_REQUEST("agent.mcp.list.request"),
    AGENT_SESSION_LIST_REQUEST("agent.session.list.request"),
    AGENT_SESSION_CREATE("agent.session.create"),
    AGENT_SESSION_DELETE("agent.session.delete"),
    AGENT_SESSION_PROMPT("agent.session.prompt"),
    AGENT_SESSION_ABORT("agent.session.abort"),
    AGENT_PERMISSION_REPLY("agent.permission.reply"),
    AGENT_QUESTION_REPLY("agent.question.reply"),

    // Bridge → Android
    AGENT_RUNTIME_STATUS("agent.runtime.status"),
    AGENT_CONFIG("agent.config"),
    AGENT_PROVIDER_LIST("agent.provider.list"),
    AGENT_MODEL_LIST("agent.model.list"),
    AGENT_MCP_LIST("agent.mcp.list"),
    AGENT_SESSION_LIST("agent.session.list"),
    AGENT_SESSION_CREATED("agent.session.created"),
    AGENT_SESSION_DELETED("agent.session.deleted"),
    AGENT_EVENT("agent.event"),

    // PTY pass-through (opencode /pty/{id}/connect)
    AGENT_PTY_OPEN("agent.pty.open"),
    AGENT_PTY_RESIZE("agent.pty.resize"),
    AGENT_PTY_INPUT("agent.pty.input"),
    AGENT_PTY_CLOSE("agent.pty.close"),
    AGENT_PTY_OPENED("agent.pty.opened"),
    AGENT_PTY_OUTPUT("agent.pty.output"),
    AGENT_PTY_CLOSED("agent.pty.closed");

    companion object {
        private val byWireName: Map<String, BridgeMessageType> =
            values().associateBy { it.wireName }

        /** Parse a wire-name into an enum, returning null if unknown. */
        fun fromWireName(value: String?): BridgeMessageType? =
            if (value == null) null else byWireName[value]
    }
}
