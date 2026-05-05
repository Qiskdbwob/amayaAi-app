package com.amaya.intelligence.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryCompactor @Inject constructor(
    private val deduper: MemoryDeduper,
    private val classifier: MemoryClassifier
) {
    fun compactHotMemory(markdown: String, maxChars: Int = 12_000): String {
        val safeLines = markdown.lineSequence()
            .filterNot { classifier.containsSecret(it) }
            .joinToString("\n")
        val deduped = deduper.dedupeLines(safeLines)
        return if (deduped.length <= maxChars) deduped else deduped.take(maxChars).trimEnd() + "\n"
    }
}
