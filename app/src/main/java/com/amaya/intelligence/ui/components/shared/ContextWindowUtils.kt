package com.amaya.intelligence.ui.components.shared

/**
 * Best-effort context-window metadata for the session info UI.
 *
 * Token usage comes from provider responses. Context window does not: the
 * OpenAI-compatible /models endpoint only returns id/object/created/owned_by in
 * the official schema, so compatible providers need either provider-specific
 * metadata or a local model-id table like this one.
 */
object ContextWindowUtils {
    data class ContextWindowInfo(
        val tokens: Int?,
        val label: String,
        val source: String
    )

    fun getContextWindowInfo(modelName: String): ContextWindowInfo {
        val model = modelName.trim().lowercase()
        if (model.isBlank()) return unknown()

        explicitWindowFromName(model)?.let { return known(it, "model id") }

        val tokens = when {
            model.contains("gpt-4.1") -> 1_047_576
            model.contains("gpt-4o") -> 128_000
            model.contains("gpt-4-turbo") -> 128_000
            model.contains("gpt-4") -> 128_000
            model.contains("gpt-3.5") -> 16_000
            model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") -> 128_000
            model.contains("claude") -> 200_000
            model.contains("gemini-1.5") || model.contains("gemini-2") -> 1_000_000
            model.contains("gemini") -> 128_000
            model.contains("mistral-large") -> 128_000
            model.contains("mistral") -> 32_000
            model.contains("deepseek") -> 64_000
            model.contains("llama") -> 128_000
            model.contains("qwen") -> 128_000
            else -> null
        }
        return tokens?.let { known(it, "model table") } ?: unknown()
    }

    /** Backward-compatible display helper. */
    fun getContextWindow(modelName: String): String = getContextWindowInfo(modelName).label

    fun formatTokenCount(count: Int): String = when {
        count >= 1_000_000 -> compact(count, 1_000_000, "M")
        count >= 1_000 -> compact(count, 1_000, "k")
        else -> count.toString()
    }

    private fun compact(count: Int, unit: Int, suffix: String): String {
        return if (count % unit == 0) "${count / unit}$suffix" else String.format("%.1f%s", count / unit.toDouble(), suffix)
    }

    private fun known(tokens: Int, source: String): ContextWindowInfo = ContextWindowInfo(
        tokens = tokens,
        label = formatTokenCount(tokens).uppercase(),
        source = source
    )

    private fun unknown(): ContextWindowInfo = ContextWindowInfo(
        tokens = null,
        label = "-",
        source = ""
    )

    private fun explicitWindowFromName(model: String): Int? {
        val match = Regex("(?:^|[-_/])([0-9]+(?:\\.[0-9]+)?)(k|m)(?:[-_/]|$)").find(model) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "k" -> (value * 1_000).toInt()
            "m" -> (value * 1_000_000).toInt()
            else -> null
        }
    }
}
