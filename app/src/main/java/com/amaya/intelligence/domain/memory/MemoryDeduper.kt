package com.amaya.intelligence.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryDeduper @Inject constructor() {
    fun isDuplicate(candidate: String, existingText: String): Boolean {
        val normalizedCandidate = normalize(candidate)
        if (normalizedCandidate.isBlank()) return true
        return existingText.lineSequence()
            .map { it.trim().trimStart('-', '*').trim() }
            .filter { it.isNotBlank() }
            .any { existing ->
                val normalizedExisting = normalize(existing)
                normalizedExisting == normalizedCandidate ||
                    (normalizedCandidate.length >= 4 && normalizedExisting.contains(normalizedCandidate)) ||
                    (normalizedExisting.length >= 12 && normalizedCandidate.contains(normalizedExisting)) ||
                    similarity(normalizedExisting, normalizedCandidate) >= 0.92
            }
    }

    fun dedupeLines(text: String): String {
        val seen = linkedSetOf<String>()
        return text.lineSequence()
            .filter { line ->
                val key = normalize(line.trim().trimStart('-', '*').trim())
                key.isBlank() || seen.add(key)
            }
            .joinToString("\n")
            .trimEnd() + "\n"
    }

    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val left = a.split(' ').toSet()
        val right = b.split(' ').toSet()
        val union = left + right
        if (union.isEmpty()) return 0.0
        return (left intersect right).size.toDouble() / union.size.toDouble()
    }
}
