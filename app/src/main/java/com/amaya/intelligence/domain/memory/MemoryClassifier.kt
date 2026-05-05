package com.amaya.intelligence.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryClassifier @Inject constructor(
    private val safetyFilter: MemorySafetyFilter,
    private val contentNormalizer: MemoryContentNormalizer
) {
    fun classify(
        content: String,
        requestedType: MemoryType? = null,
        requestedAction: MemoryAction = MemoryAction.ADD,
        requestedScope: MemoryScope? = null,
        requestedTitle: String? = null,
        reason: String = "Requested by agent",
        confidence: Double = 0.8,
        importance: Double = 0.5
    ): MemoryProposal {
        val trimmed = content.trim()
        val safeConfidence = confidence.coerceIn(0.0, 1.0)
        val safeImportance = importance.coerceIn(0.0, 1.0)
        val safety = safetyFilter.check(trimmed)
        val safeContent = safety.redactedContent.trim()
        val unsafe = safeContent.isBlank() || safeConfidence < MIN_CONFIDENCE || !safety.safe
        val type = requestedType ?: inferType(safeContent)
        val action = if (unsafe) MemoryAction.IGNORE else normalizeAction(requestedAction, safeContent)
        val scope = requestedScope ?: defaultScope(type)
        val normalized = if (unsafe) {
            NormalizedMemoryText("Rejected memory", safeContent, if (!safety.safe) "Rejected by memory safety rules: ${safety.reasons.joinToString()}" else "Rejected by memory safety/classification rules")
        } else {
            contentNormalizer.normalize(safeContent, type, action, reason, requestedTitle)
        }

        return MemoryProposal(
            type = type,
            action = action,
            scope = scope,
            title = normalized.title,
            content = normalized.content,
            reason = normalized.reason,
            confidence = safeConfidence,
            importance = safeImportance
        )
    }

    fun containsSecret(text: String): Boolean = !safetyFilter.check(text).safe

    fun checkSafety(text: String): SafetyCheckResult = safetyFilter.check(text)

    private fun inferType(content: String): MemoryType {
        val lower = content.lowercase()
        return when {
            listOf("prefers", "preference", "call me", "nickname", "likes replies", "bahasa", "language").any { it in lower } -> MemoryType.USER_PROFILE
            listOf("remind", "reminder", "ingatkan", "jadwalkan").any { it in lower } -> MemoryType.REMINDER
            listOf("today", "session", "discussed", "completed", "decided", "hari ini").any { it in lower } -> MemoryType.DAILY_LOG
            listOf("workspace", "project", "repo", "repository", "environment").any { it in lower } -> MemoryType.WORKSPACE_FACT
            else -> MemoryType.LONG_TERM_MEMORY
        }
    }

    private fun normalizeAction(action: MemoryAction, content: String): MemoryAction {
        return when {
            action == MemoryAction.REPLACE && !hasReplacementDelimiter(content) -> MemoryAction.ADD
            action == MemoryAction.REMOVE && content.trim().isBlank() -> MemoryAction.IGNORE
            else -> action
        }
    }

    private fun hasReplacementDelimiter(content: String): Boolean {
        return listOf("=>", "->", "→").any { delimiter ->
            val index = content.indexOf(delimiter)
            index > 0 && index < content.lastIndex
        }
    }

    private fun defaultScope(type: MemoryType): MemoryScope = when (type) {
        MemoryType.USER_PROFILE -> MemoryScope.USER
        MemoryType.WORKSPACE_FACT -> MemoryScope.WORKSPACE
        MemoryType.DAILY_LOG -> MemoryScope.SESSION
        MemoryType.SKILL_CANDIDATE -> MemoryScope.GLOBAL
        MemoryType.REMINDER -> MemoryScope.USER
        MemoryType.LONG_TERM_MEMORY -> MemoryScope.GLOBAL
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.55

    }
}
