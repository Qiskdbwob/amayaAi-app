package com.amaya.intelligence.domain.bridge

import java.util.UUID

/**
 * Successful (or terminally cancelled/timed-out) response from the Windows Bridge for a
 * prior [BridgeToolCall]. For failure responses see [BridgeToolError].
 */
data class BridgeToolResult(
    /** Unique id for this result envelope payload. */
    val id: String = UUID.randomUUID().toString(),
    /** Correlates with [BridgeToolCall.id]. */
    val toolCallId: String,
    /** Session this result belongs to. */
    val sessionId: String,
    /** Tool name from the originating call, duplicated for convenience. */
    val tool: String,
    /** Final status of the tool invocation. */
    val status: BridgeToolResultStatus,
    /** Tool-specific result payload. JSON-serializable primitives, maps, and lists only. */
    val result: Map<String, Any?> = emptyMap(),
    /** Epoch millis when the bridge started executing the call. */
    val startedAt: Long,
    /** Epoch millis when the bridge finished executing the call. */
    val finishedAt: Long,
    /** Convenience duration, normally `finishedAt - startedAt`. */
    val durationMs: Long = (finishedAt - startedAt).coerceAtLeast(0L),
    /** Free-form non-functional metadata (trace ids, screenshot refs, etc.). */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Terminal status of a tool call as reported by the Windows Bridge. "Failure" states
 * with a structured error code live on [BridgeToolError] instead.
 */
enum class BridgeToolResultStatus(val wireName: String) {
    SUCCESS("success"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeToolResultStatus? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}
