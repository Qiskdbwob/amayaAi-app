package com.amaya.intelligence.data.repository.chat

private const val TITLE_FALLBACK = "New Chat"
private val TITLE_PREFIX = Regex("(?i)^\\s*(?:title|judul)\\s*:\\s*")
private val TITLE_TAG = Regex("(?is)<title>\\s*(.*?)\\s*</title>")
private val TITLE_QUOTED = Regex("[\"“]([^\"”\\r\\n]{2,60})[\"”]")
private val TITLE_SEPARATOR = Regex("\\s+(?:[–—|]|-)\\s+")
private val TITLE_EDGE_MARKUP = Regex("^[\\s*#>`_-]+|[\\s*#>`_-]+$")
private val TITLE_META = Regex("(?i)^(?:here is|this is|the title|your title|berikut|judulnya)\\b")
private val TITLE_TRAILING_PUNCTUATION = Regex("[.!?:;]+$")
private val TITLE_WHITESPACE = Regex("\\s+")
private val THINK_BLOCK = Regex("(?is)<think>.*?</think>")

internal fun fallbackConversationTitle(userMessage: String): String =
    userMessage.replace(TITLE_WHITESPACE, " ").trim().ifBlank { TITLE_FALLBACK }

internal fun extractConversationTitle(raw: String): String? =
    sanitizeConversationTitle(raw, "").takeIf(String::isNotBlank)

internal fun sanitizeConversationTitle(raw: String, fallback: String): String {
    val cleaned = raw.replace(THINK_BLOCK, " ").trim()
    val candidates = buildList {
        TITLE_TAG.find(cleaned)?.groupValues?.getOrNull(1)?.let(::add)
        TITLE_QUOTED.findAll(cleaned).forEach { add(it.groupValues[1]) }
        cleaned.lineSequence().firstOrNull { it.isNotBlank() }
            ?.split(TITLE_SEPARATOR, limit = 2)?.firstOrNull()?.let(::add)
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate.replace(TITLE_EDGE_MARKUP, "")
            .replace(TITLE_PREFIX, "")
            .replace(TITLE_EDGE_MARKUP, "")
            .replace(TITLE_TRAILING_PUNCTUATION, "")
            .replace(TITLE_WHITESPACE, " ").trim()
            .takeIf { title -> !TITLE_META.containsMatchIn(title) && title.length <= 60 && title.split(TITLE_WHITESPACE).size in 2..5 }
    } ?: fallback
}
