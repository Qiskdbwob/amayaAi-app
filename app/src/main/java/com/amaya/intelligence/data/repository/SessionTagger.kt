package com.amaya.intelligence.data.repository

internal object SessionTagger {
    private val knownTags = listOf(
        "android", "webview", "oauth", "browser", "skill", "memory", "reminder", "kotlin", "gradle"
    )

    fun infer(text: String, limit: Int = knownTags.size): List<String> {
        val lower = text.lowercase()
        return knownTags.filter(lower::contains).take(limit.coerceAtLeast(0))
    }
}
