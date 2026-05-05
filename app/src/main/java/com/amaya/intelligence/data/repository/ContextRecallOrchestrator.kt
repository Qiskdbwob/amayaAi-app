package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.skills.SkillMetadata
import com.amaya.intelligence.domain.skills.SkillStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the recall layer for a single chat turn. Persona stays separate; this
 * class only decides which memory, skill, project, and past-session snippets are
 * relevant enough to enter the prompt.
 */
data class RecallContextBundle(
    val userMemory: String,
    val importantMemory: String,
    val projectMemory: String,
    val skillIndex: String,
    val pastSessions: String,
    val workspaceContext: String,
    val dailyNotesHint: String
)

@Singleton
class ContextRecallOrchestrator @Inject constructor(
    private val brainSettingsRepository: BrainSettingsRepository,
    private val memoryRepository: MemoryRepository,
    private val skillRepository: SkillRepository,
    private val sessionMemoryRepository: SessionMemoryRepository
) {
    suspend fun buildRecall(userMessage: String, workspacePath: String?): RecallContextBundle {
        val settings = brainSettingsRepository.getBrainSettings()
        val maxItems = settings.context.maxRecallItems.coerceIn(1, 20)

        val userMemory = if (settings.memory.useSavedMemory && settings.context.relevantMemoryEnabled) {
            relevantMemoryRecords(
                title = "Relevant User Memory",
                type = MemoryType.USER_PROFILE,
                query = userMessage,
                limit = maxItems,
                fallbackToRecent = true
            )
        } else "Saved user memory is disabled."

        val importantMemory = if (settings.memory.useSavedMemory && settings.context.relevantMemoryEnabled) {
            relevantMemoryRecords(
                title = "Relevant Important Memory",
                type = MemoryType.LONG_TERM_MEMORY,
                query = userMessage,
                limit = maxItems,
                fallbackToRecent = false
            )
        } else "Saved important memory is disabled."

        val projectMemory = if (settings.context.workspaceContextEnabled) {
            relevantMemoryRecords(
                title = "Relevant Project Memory",
                type = MemoryType.WORKSPACE_FACT,
                query = userMessage,
                limit = maxItems,
                fallbackToRecent = true
            )
        } else "Workspace memory is disabled."

        val skills = if (settings.skills.useSavedSkills) {
            buildRelevantSkillIndex(userMessage, maxItems)
        } else {
            "Saved skills are disabled."
        }

        val sessions = if (settings.context.pastChatRecallEnabled && shouldRecallPastChats(userMessage)) {
            buildPastSessionRecall(userMessage, maxItems)
        } else if (!settings.context.pastChatRecallEnabled) {
            "Previous chat recall is disabled."
        } else {
            "No past sessions injected. Use session_search only if the user clearly refers to old chats."
        }

        val workspaceContext = if (settings.context.workspaceContextEnabled && workspacePath != null) {
            """
            Path: $workspacePath

            When the user asks to list files, read files, or perform any file operation,
            use this workspace path as the base directory.
            """.trimIndent()
        } else if (!settings.context.workspaceContextEnabled) {
            "Workspace context is disabled."
        } else "No active workspace path."

        val dailyHint = if (settings.memory.dailyNotesEnabled) {
            "Daily notes are stored for recall, but not injected automatically to avoid long-term context noise. Use session_search when old chronological context is needed."
        } else "Daily notes are disabled."

        return RecallContextBundle(
            userMemory = userMemory,
            importantMemory = importantMemory,
            projectMemory = projectMemory,
            skillIndex = skills,
            pastSessions = sessions,
            workspaceContext = workspaceContext,
            dailyNotesHint = dailyHint
        )
    }

    private suspend fun relevantMemoryRecords(
        title: String,
        type: MemoryType,
        query: String,
        limit: Int,
        fallbackToRecent: Boolean
    ): String {
        val ranked = memoryRepository.listMemoryRecords(type = type, query = query, limit = limit)
        val selected = if (ranked.isNotEmpty()) ranked else if (fallbackToRecent) {
            memoryRepository.listMemoryRecords(type = type, limit = limit)
        } else emptyList()
        if (selected.isEmpty()) return "No strongly relevant saved items for this turn."
        return buildString {
            appendLine("# $title")
            selected.forEach { record ->
                appendLine("- [${record.id}] ${record.title}: ${record.content}")
            }
        }.trim()
    }

    private suspend fun buildRelevantSkillIndex(query: String, limit: Int): String {
        val active = skillRepository.listSkills()
            .filter { it.status != SkillStatus.ARCHIVED && it.enabled }
            .map { it to scoreSkill(it, query) }
            .filter { it.second > 0.0 || query.isBlank() }
            .sortedWith(compareByDescending<Pair<SkillMetadata, Double>> { it.second }
                .thenByDescending { it.first.lastUsedAt ?: 0L }
                .thenByDescending { successRate(it.first) })
            .take(limit)
            .map { it.first }

        if (active.isEmpty()) {
            return "No relevant reusable skills for this turn. When the user explicitly asks to save a reusable workflow, use skill_manage(action=create)."
        }
        return buildString {
            appendLine("# Relevant Skills")
            active.forEach { skill ->
                val status = if (skill.needsReview) "needs review" else skill.status.name.lowercase()
                appendLine("- ${skill.name}: ${skill.description}. [$status; success=${"%.0f".format(successRate(skill) * 100)}%]")
            }
            appendLine()
            appendLine("Use skill_view to load full skill content before relying on a skill.")
        }.trim()
    }

    private suspend fun buildPastSessionRecall(query: String, limit: Int): String {
        val results = sessionMemoryRepository.searchSessions(query, limit)
        if (results.isEmpty()) return "No matching previous sessions found."
        return buildString {
            appendLine("# Relevant Past Sessions")
            results.take(limit).forEach { result ->
                appendLine("- ${result.sessionId}: ${result.summary.take(240)}")
                if (result.matchedText.isNotBlank()) appendLine("  Match: ${result.matchedText.take(240)}")
            }
        }.trim()
    }

    private fun shouldRecallPastChats(query: String): Boolean {
        val lower = query.lowercase()
        return listOf(
            "sebelumnya", "kemarin", "tadi", "waktu itu", "pernah", "chat lama",
            "obrolan lama", "percakapan", "last time", "previous", "earlier", "remember when"
        ).any { it in lower }
    }

    private fun scoreSkill(skill: SkillMetadata, query: String): Double {
        val text = buildString {
            append(skill.name).append(' ')
            append(skill.description).append(' ')
            append(skill.tags.joinToString(" "))
        }
        var score = scoreText(text, query)
        score += successRate(skill)
        if (skill.lastUsedAt != null) score += 0.25
        if (skill.needsReview) score -= 1.0
        return score.coerceAtLeast(0.0)
    }

    private fun scoreText(text: String, query: String): Double {
        val terms = tokenize(query).filter { it.length > 2 }
        if (terms.isEmpty()) return 0.0
        val haystack = tokenize(text).toSet()
        var score = 0.0
        terms.forEach { term ->
            if (term in haystack) score += 2.0
            else if (haystack.any { it.contains(term) || term.contains(it) }) score += 0.75
        }
        return score
    }

    private fun tokenize(text: String): List<String> {
        val terms = text.lowercase()
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toMutableSet()
        SYNONYMS.forEach { (key, values) ->
            if (key in terms || values.any { it in terms }) {
                terms.add(key)
                terms.addAll(values)
            }
        }
        return terms.toList()
    }

    private fun successRate(skill: SkillMetadata): Double {
        val outcomes = skill.successCount + skill.failureCount
        return if (outcomes <= 0) 0.5 else skill.successCount.toDouble() / outcomes.toDouble()
    }

    companion object {
        private val SYNONYMS = mapOf(
            "language" to setOf("bahasa", "jawab", "respond", "reply"),
            "tone" to setOf("gaya", "style", "nada", "cara"),
            "concise" to setOf("ringkas", "singkat", "pendek", "brief"),
            "detail" to setOf("rinci", "lengkap", "panjang", "verbose"),
            "name" to setOf("nama", "panggil", "nickname", "call"),
            "project" to setOf("workspace", "repo", "repository", "codebase", "kode"),
            "skill" to setOf("workflow", "prosedur", "cara", "reuse"),
            "memory" to setOf("ingat", "remember", "memori")
        )
    }
}
