package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.BridgeToolCall
import com.amaya.intelligence.domain.bridge.BridgeToolNames
import java.util.UUID

/**
 * Android tool-call → [BridgeToolCall] mapper.
 *
 * The existing `ToolExecutor` hands out a `Map<String, Any?>` for every invocation
 * together with the tool name. This mapper normalizes those args, strips planner-only
 * keys (anything prefixed with `__`), and stamps the resulting [BridgeToolCall] with
 * the risk / approval metadata declared in [WindowsBridgeToolDefinitions].
 */
internal object WindowsBridgeToolMapper {

    private const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    private const val SCREEN_CAPTURE_TIMEOUT_MS: Long = 45_000L
    private const val SHELL_TIMEOUT_MS: Long = 60_000L

    /**
     * Build a [BridgeToolCall] for [toolName].
     *
     * @param sessionId The active bridge session id. Required — callers must have
     *   already confirmed availability via [WindowsBridgeToolAvailability].
     */
    fun toBridgeToolCall(
        spec: WindowsBridgeToolDefinitions.BridgeToolSpec,
        sessionId: String,
        arguments: Map<String, Any?>,
        extraMetadata: Map<String, String> = emptyMap()
    ): BridgeToolCall {
        val normalized = normalizeArguments(spec.name, arguments)
        val metadata = buildMap<String, String> {
            put("source", "android_agent")
            put("phase", "phase_3")
            put("risk", spec.risk.wireName)
            put("category", spec.category.name)
            putAll(extraMetadata)
        }
        return BridgeToolCall(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            tool = spec.name,
            args = normalized,
            risk = spec.risk,
            requiresApproval = spec.requiresApproval,
            createdAt = System.currentTimeMillis(),
            timeoutMs = defaultTimeoutFor(spec),
            metadata = metadata
        )
    }

    /**
     * Remove planner-only keys (prefixed with `__`) and pass primitives/lists/maps
     * through unchanged. Unknown value types are coerced to string so that the JSON
     * mapper in the transport layer never sees something it can't encode.
     */
    private fun normalizeArguments(toolName: String, raw: Map<String, Any?>): Map<String, Any?> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>(raw.size)
        for ((key, value) in raw) {
            if (key.startsWith("__")) continue
            out[key] = normalizeValue(value)
        }
        if (toolName == BridgeToolNames.KEYBOARD_HOTKEY) {
            hotkeyKeys(out["keys"] ?: out["combo"] ?: out["hotkey"] ?: out["shortcut"])?.let { keys ->
                out["keys"] = keys
            }
        }
        return out
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Double, is Float -> value
        is Number -> value
        is Map<*, *> -> value.entries.asSequence()
            .filter { it.key is String && !(it.key as String).startsWith("__") }
            .associate { (it.key as String) to normalizeValue(it.value) }
        is List<*> -> value.map { normalizeValue(it) }
        is Array<*> -> value.map { normalizeValue(it) }
        else -> value.toString()
    }

    private fun hotkeyKeys(value: Any?): List<String>? = when (value) {
        is String -> value.trim()
            .takeIf { it.isNotEmpty() }
            ?.split(if (value.contains('+')) Regex("\\s*\\+\\s*") else Regex("\\s*,\\s*|\\s+"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        is List<*> -> value.flatMap { hotkeyKeys(it).orEmpty() }.takeIf { it.isNotEmpty() }
        is Array<*> -> value.flatMap { hotkeyKeys(it).orEmpty() }.takeIf { it.isNotEmpty() }
        is Map<*, *> -> hotkeyKeys(value["keys"] ?: value["combo"] ?: value["hotkey"] ?: value["shortcut"])
            ?: value.entries
                .sortedBy { (key, _) -> key?.toString()?.toIntOrNull() ?: Int.MAX_VALUE }
                .flatMap { (_, nested) -> hotkeyKeys(nested).orEmpty() }
                .takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun defaultTimeoutFor(spec: WindowsBridgeToolDefinitions.BridgeToolSpec): Long =
        when (spec.category) {
            WindowsBridgeToolDefinitions.Category.SCREEN -> SCREEN_CAPTURE_TIMEOUT_MS
            WindowsBridgeToolDefinitions.Category.SHELL -> SHELL_TIMEOUT_MS
            else -> DEFAULT_TIMEOUT_MS
        }
}
