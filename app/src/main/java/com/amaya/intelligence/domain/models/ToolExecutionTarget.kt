package com.amaya.intelligence.domain.models

/**
 * Origin/target of a tool execution. Used in [ToolExecution.metadata] under the
 * key [KEY] so the UI can badge tool-call cards without knowing implementation
 * details.
 */
enum class ToolExecutionTarget(val wireName: String) {
    LOCAL_ANDROID("LOCAL_ANDROID"),
    WINDOWS_BRIDGE("WINDOWS_BRIDGE"),
    MCP("MCP"),
    REMOTE_IDE("REMOTE_IDE"),
    UNKNOWN("UNKNOWN");

    companion object {
        const val KEY = "executionTarget"

        fun fromMetadata(metadata: Map<String, String>?): ToolExecutionTarget {
            val value = metadata?.get(KEY) ?: return UNKNOWN
            return entries.firstOrNull { it.wireName == value } ?: UNKNOWN
        }
    }
}
