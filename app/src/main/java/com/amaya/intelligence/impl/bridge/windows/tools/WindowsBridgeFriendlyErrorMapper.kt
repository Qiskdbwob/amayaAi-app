package com.amaya.intelligence.impl.bridge.windows.tools

/**
 * Maps raw bridge error codes/messages to user-friendly strings for display in
 * ToolCallCard and the bridge activity section. Never exposes tokens, typed text,
 * or raw payloads.
 */
object WindowsBridgeFriendlyErrorMapper {

    fun friendlyMessage(rawMessage: String): String {
        // Try to extract the code from the JSON-shaped error output
        val code = extractCode(rawMessage)
        return when (code) {
            "BRIDGE_UNAVAILABLE" ->
                "Windows Bridge is not connected. Connect your PC first."
            "SESSION_CLOSED" ->
                "Windows Bridge session was closed."
            "PERMISSION_DENIED" ->
                "Permission denied by Windows Bridge."
            "APP_NOT_ALLOWED" ->
                "This app/window is not allowed by the Windows Bridge policy."
            "APPROVAL_REQUIRED" ->
                "This action needs approval."
            "APPROVAL_REJECTED" ->
                "User rejected the Windows action."
            "TIMEOUT" ->
                "Windows Bridge did not respond in time."
            "COMMAND_BLOCKED" ->
                "This action is blocked by policy."
            "EXECUTION_FAILED" ->
                "Windows action failed."
            "INVALID_ARGS" ->
                "The Windows tool received invalid arguments."
            "TOOL_DISABLED" ->
                "Windows Bridge tool is disabled in this phase."
            "UNKNOWN_TOOL" ->
                "Unknown Windows Bridge tool."
            "HELPER_UNAVAILABLE" ->
                "Windows native helper is not running."
            else -> {
                // Fallback: if the raw message is JSON, extract the "message" field
                val msg = extractMessage(rawMessage)
                if (msg.isNotBlank() && msg.length < 120) msg
                else "Windows Bridge error."
            }
        }
    }

    /** Short status label for the bridge activity list. */
    fun shortOutcome(rawMessage: String): String {
        val code = extractCode(rawMessage)
        return when (code) {
            "BRIDGE_UNAVAILABLE" -> "disconnected"
            "SESSION_CLOSED" -> "session closed"
            "PERMISSION_DENIED" -> "denied"
            "APP_NOT_ALLOWED" -> "app blocked"
            "APPROVAL_REQUIRED" -> "needs approval"
            "APPROVAL_REJECTED" -> "rejected"
            "TIMEOUT" -> "timed out"
            "COMMAND_BLOCKED" -> "blocked"
            "EXECUTION_FAILED" -> "failed"
            "INVALID_ARGS" -> "invalid args"
            "TOOL_DISABLED" -> "disabled"
            "UNKNOWN_TOOL" -> "unknown tool"
            "HELPER_UNAVAILABLE" -> "helper down"
            else -> "error"
        }
    }

    private fun extractCode(raw: String): String? {
        // Quick JSON extraction without full parser
        val codeIdx = raw.indexOf("\"code\"")
        if (codeIdx < 0) return null
        val colonIdx = raw.indexOf(':', codeIdx + 6)
        if (colonIdx < 0) return null
        val quoteStart = raw.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return null
        val quoteEnd = raw.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return null
        return raw.substring(quoteStart + 1, quoteEnd)
    }

    private fun extractMessage(raw: String): String {
        val msgIdx = raw.indexOf("\"message\"")
        if (msgIdx < 0) return ""
        val colonIdx = raw.indexOf(':', msgIdx + 9)
        if (colonIdx < 0) return ""
        val quoteStart = raw.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return ""
        val quoteEnd = raw.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return ""
        return raw.substring(quoteStart + 1, quoteEnd)
    }
}
