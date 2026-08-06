package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Failure response from the Windows Bridge for a prior [BridgeToolCall]. See
 * [BridgeToolResult] for the success path.
 *
 * This file also exposes the generic [BridgeError] type used for transport-level
 * errors carried on [BridgeMessageType.ERROR] envelopes.
 */
data class BridgeToolError(
    /** Unique id for this error envelope payload. */
    val id: String = UUID.randomUUID().toString(),
    /** Correlates with [BridgeToolCall.id]. */
    val toolCallId: String,
    /** Session this error belongs to. */
    val sessionId: String,
    /** Tool name from the originating call, duplicated for convenience. */
    val tool: String,
    /** Stable machine-readable error code. See [BridgeToolErrorCode]. */
    val code: BridgeToolErrorCode,
    /** Short human-readable message. Safe to log and display as-is. */
    val message: String,
    /** Optional structured details (exception type, stderr snippet, blocked path, ...). */
    val details: Map<String, Any?> = emptyMap(),
    /** Hint to the caller whether retrying is sensible without user intervention. */
    val recoverable: Boolean = false,
    /** Epoch millis when the error was produced on the bridge side. */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stable wire-level error codes for [BridgeToolError.code]. Keep this list additive:
 * prefer adding new codes over repurposing existing ones.
 */
enum class BridgeToolErrorCode(val wireName: String) {
    INVALID_ARGS("INVALID_ARGS"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    APP_NOT_ALLOWED("APP_NOT_ALLOWED"),
    PATH_NOT_ALLOWED("PATH_NOT_ALLOWED"),
    COMMAND_BLOCKED("COMMAND_BLOCKED"),
    APPROVAL_REQUIRED("APPROVAL_REQUIRED"),
    APPROVAL_REJECTED("APPROVAL_REJECTED"),
    EXECUTION_FAILED("EXECUTION_FAILED"),
    TIMEOUT("TIMEOUT"),
    SESSION_CLOSED("SESSION_CLOSED"),
    UNKNOWN("UNKNOWN");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeToolErrorCode? =
            if (value == null) null else byWireName[value.uppercase()]
    }
}

/**
 * Transport-level error envelope payload. Used for failures that are not scoped to a
 * single tool call (handshake failure, protocol violation, session drop, etc.).
 */
data class BridgeError(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String?,
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
