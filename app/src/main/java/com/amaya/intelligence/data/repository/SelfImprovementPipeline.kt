package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ProviderConnection
import com.amaya.intelligence.data.remote.api.AiProvider
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.data.remote.api.AnthropicProvider
import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.ChatRequest
import com.amaya.intelligence.data.remote.api.ChatResponse
import com.amaya.intelligence.data.remote.api.GeminiProvider
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.OpenAiProvider
import com.amaya.intelligence.data.remote.api.ProviderAdapter
import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalAction
import com.amaya.intelligence.domain.memory.PendingProposalStatus
import com.amaya.intelligence.domain.memory.PendingProposalType
import com.amaya.intelligence.util.errorLog
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class CompletedInteractionContext(
    val sessionId: String,
    val userMessages: List<String>,
    val assistantMessages: List<String>,
    val toolCalls: List<String>,
    val toolResults: List<String>,
    val timestamp: Long
)

data class SelfImprovementResult(
    val memoryProposals: List<MemoryProposal>,
    val dailyLogEntries: List<String>,
    val skillProposals: List<PendingProposal> = emptyList()
)

private data class DailyLogCandidate(
    val title: String,
    val content: String,
    val reason: String
)

@Singleton
class SelfImprovementPipeline @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val brainSettingsRepository: BrainSettingsRepository,
    private val classifier: MemoryClassifier,
    private val policy: SelfImprovementPolicy,
    private val pendingProposalRepository: PendingProposalRepository,
    private val settingsManager: AiSettingsManager,
    private val anthropicProvider: AnthropicProvider,
    private val openAiProvider: OpenAiProvider,
    private val geminiProvider: GeminiProvider
) {
    suspend fun analyzeAndImprove(context: CompletedInteractionContext): SelfImprovementResult {
        val settings = brainSettingsRepository.getBrainSettings()

        val dailyCandidates = if (settings.memory.dailyNotesEnabled) buildDailyLogCandidates(context) else emptyList()
        val dailyEntries = dailyCandidates.map { it.content }
        val dailyProposals = dailyCandidates.map { entry ->
            classifier.classify(
                content = entry.content,
                requestedType = MemoryType.DAILY_LOG,
                requestedTitle = entry.title,
                reason = entry.reason,
                confidence = 0.8,
                importance = 0.4
            )
        }
        val memoryProposals = (if (settings.memory.suggestNewMemories) extractMemoryCandidates(context) else emptyList()) + dailyProposals

        memoryProposals.forEach { proposal ->
            when (policy.decideMemory(proposal, settings.memory).route) {
                SelfImprovementRoute.APPLY_NOW -> memoryRepository.applyProposal(proposal)
                SelfImprovementRoute.REQUIRE_APPROVAL -> pendingProposalRepository.addProposal(proposal.toPendingProposal(context.sessionId))
                SelfImprovementRoute.IGNORE -> Unit
            }
        }

        val skillProposals = extractSkillCandidates(context)
        skillProposals.forEach { pendingProposalRepository.addProposal(it) }

        if (dailyEntries.isNotEmpty()) {
            sessionMemoryRepository.saveMessage(
                SessionMessage(
                    sessionId = context.sessionId,
                    role = "summary",
                    content = dailyCandidates.joinToString("\n") { "${it.title}: ${it.content}" },
                    timestamp = context.timestamp,
                    tags = listOf("reflection")
                )
            )
        }

        return SelfImprovementResult(
            memoryProposals = memoryProposals,
            dailyLogEntries = dailyEntries,
            skillProposals = skillProposals
        )
    }

    private suspend fun buildDailyLogCandidates(context: CompletedInteractionContext): List<DailyLogCandidate> {
        // Daily notes must be true model reflections, not deterministic/fake status lines.
        // If no reflection model is available or the model returns a generic note, save nothing.
        modelDailyLogCandidate(context)?.let { return listOf(it) }
        return emptyList()
    }

    private suspend fun modelDailyLogCandidate(context: CompletedInteractionContext): DailyLogCandidate? = runCatching {
        val userText = stripThinking(context.userMessages.takeLast(3).joinToString("\n---\n")).trim().take(2_000)
        val assistantText = stripThinking(context.assistantMessages.takeLast(2).joinToString("\n---\n")).trim().take(2_000)
        if (userText.isBlank() && assistantText.isBlank()) return@runCatching null
        if (isTrivialGreeting(userText.lowercase()) && assistantText.isBlank()) return@runCatching null

        val (provider, connection, model) = resolveReflectionModel() ?: return@runCatching null
        val prompt = buildReflectionPrompt(userText, assistantText)
        val output = StringBuilder()
        provider.chat(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = MessageRole.USER, content = prompt)),
                systemPrompt = DAILY_REFLECTION_SYSTEM_PROMPT,
                tools = emptyList(),
                maxTokens = 2_048,
                temperature = 0.2f,
                stream = false,
                connectionId = connection.id
            )
        ).collect { response ->
            when (response) {
                is ChatResponse.TextDelta -> output.append(response.text)
                is ChatResponse.Incomplete -> throw IllegalStateException(response.reason)
                is ChatResponse.Error -> throw IllegalStateException(response.message)
                else -> Unit
            }
        }
        parseDailyReflection(output.toString(), userText)
    }.onFailure { errorLog("SelfImprovementPipeline", "AI daily reflection failed", it) }.getOrNull()

    private suspend fun resolveReflectionModel(): Triple<AiProvider, ProviderConnection, String>? {
        val settings = settingsManager.getSettings()
        val selection = settings.activeSelection ?: return null
        val connection = settings.connections.firstOrNull { it.id == selection.connectionId }
            ?: return null
        val provider = when (AmayaProviderRegistry.require(connection.providerId).adapter) {
            ProviderAdapter.ANTHROPIC -> anthropicProvider
            ProviderAdapter.GEMINI -> geminiProvider
            ProviderAdapter.OPENAI_RESPONSES, ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.CODEX -> openAiProvider
        }

        val model = selection.modelId
        if (model.isBlank()) return null
        return Triple(provider, connection, model)
    }

    private fun buildReflectionPrompt(
        userText: String,
        assistantText: String
    ): String = buildString {
        appendLine("Create one daily-note candidate for this completed Amaya session.")
        appendLine("Return JSON only. Do not include markdown fences.")
        appendLine()
        appendLine("USER MESSAGES:")
        appendLine(userText.ifBlank { "None" })
        appendLine()
        appendLine("ASSISTANT OUTCOME:")
        appendLine(assistantText.ifBlank { "None" })

    }

    private fun parseDailyReflection(raw: String, userText: String): DailyLogCandidate? {
        val jsonText = extractJsonObject(raw) ?: return null
        val root = JSONObject(jsonText)
        val note = root.optJSONObject("daily_note") ?: root
        if (!note.optBoolean("should_save", true)) return null
        val title = note.optString("title").cleanReflectionText().take(80)
        val summary = note.optString("summary").cleanReflectionText().take(260)
        val reason = note.optString("reason").cleanReflectionText().take(220)
        if (title.isBlank() || summary.isBlank()) return null
        if (isGenericDailyTitle(title) || isGenericDailySummary(summary)) return null
        if (isInternalToolCentric(summary) || isInternalToolCentric(title)) return null
        return DailyLogCandidate(
            title = title,
            content = summary,
            reason = reason.ifBlank { "The session produced a specific outcome worth preserving as a daily note." }
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private fun String.cleanReflectionText(): String = replace(Regex("(?is)<think>.*?</think>"), "")
        .replace(Regex("(?is)<think>.*"), "")
        .replace(Regex("""(?i)^\s*(user asked|user discussed|outcome)\s*:\s*"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '\n', '\t', '.', ',', ';', ':')

    private fun isGenericDailyTitle(title: String): Boolean {
        val lower = title.lowercase().trim().removeSuffix(".")
        return lower in setOf(
            "daily summary", "interaction summary", "session summary", "chat task", "tool task",
            "browser task", "memory activity", "completed interaction"
        )
    }

    private fun isGenericDailySummary(summary: String): Boolean {
        val lower = summary.lowercase().trim().removeSuffix(".")
        return lower in setOf(
            "user completed a chat task with amaya",
            "user completed a tool-assisted task",
            "user completed a browser/search task",
            "interaction summarized",
            "the session completed successfully",
            "the session focused on a browser or search request from the user",
            "the session focused on a browser or search request",
            "the session handled a request to find and remove saved memory",
            "the session reviewed saved memory and memory-management behavior"
        ) || lower.startsWith("user asked/discussed") ||
            lower.startsWith("the session focused on") ||
            lower.startsWith("the session handled") ||
            lower.startsWith("the session reviewed") ||
            lower.startsWith("the session captured")
    }

    private fun isInternalToolCentric(summary: String): Boolean {
        val lower = summary.lowercase()
        return listOf(
            "tool", "tools", "tool-assisted", "tools used", "tool used", "tool call", "toolcall", "internal tool",
            "internal execution", "called the", "used the tool", "run_shell", "update_memory", "memory_manage", "skill_manage", "skill_view"
        ).any { it in lower }
    }

    private fun extractMemoryCandidates(context: CompletedInteractionContext): List<MemoryProposal> {
        val recentUserMessages = context.userMessages.takeLast(3)
        val proposals = mutableListOf<MemoryProposal>()
        recentUserMessages.forEach { userMessage ->
            val lower = userMessage.lowercase()
            val removeIntent = listOf(
                "forget", "lupakan", "hapus memory", "hapus memori", "jangan ingat", "don't remember", "do not remember", "stop remembering"
            ).any { it in lower }
            val addIntent = listOf(
                "mulai sekarang", "from now", "prefer", "preference", "jawab saya", "call me", "panggil", "remember", "ingat", "selalu", "project ini", "workspace ini",
                "namaku", "nama ku", "nama saya", "my name is"
            ).any { it in lower }

            if (removeIntent) {
                val target = cleanMemoryContent(userMessage, remove = true)
                if (target.isNotBlank()) {
                    proposals.add(classifier.classify(
                        content = target,
                        requestedType = inferCandidateType(target),
                        requestedAction = MemoryAction.REMOVE,
                        reason = "User explicitly asked Amaya to forget or remove this memory.",
                        confidence = 0.86,
                        importance = 0.55
                    ))
                }
            } else if (addIntent) {
                val type = inferCandidateType(userMessage)
                val content = cleanMemoryContent(userMessage, remove = false)
                if (content.isNotBlank()) {
                    proposals.add(classifier.classify(
                        content = content,
                        requestedType = type,
                        reason = "User explicitly stated a durable preference, stable fact, or workspace fact.",
                        confidence = 0.82,
                        importance = if (type == MemoryType.USER_PROFILE) 0.72 else 0.66
                    ))
                }
            }
        }
        return proposals
    }

    private fun extractSkillCandidates(context: CompletedInteractionContext): List<PendingProposal> {
        val tools = context.toolCalls.map { it.substringBefore(':').trim() }
            .filter { it.isNotBlank() && it !in SELF_IMPROVEMENT_TOOLS }
        val distinctTools = tools.distinct()
        val assistantText = context.assistantMessages.joinToString(" ").lowercase()
        val userText = context.userMessages.joinToString(" ").lowercase()
        val completedSuccessfully = listOf("done", "completed", "fixed", "success", "berhasil", "selesai", "sudah").any { it in assistantText }
        val failureCount = context.toolResults.count { result -> listOf("error", "failed", "timeout", "cancelled").any { it in result.lowercase() } }
        val oneOffTask = listOf("sekali ini", "one time", "one-off", "cuma kali ini", "just this once").any { it in userText }
        val repeatedToolUse = tools.groupingBy { it }.eachCount().values.any { it >= 3 }
        val complexReusableFlow = tools.size >= 8 && distinctTools.size >= 3 && repeatedToolUse
        if (!completedSuccessfully || failureCount > 1 || oneOffTask || !complexReusableFlow) return emptyList()

        val name = "learned-${distinctTools.take(3).joinToString("-")}".lowercase().replace(Regex("[^a-z0-9-]+"), "-").take(60)
        val firstUser = context.userMessages.firstOrNull().orEmpty().take(220)
        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: Candidate reusable workflow learned from a successful repeated tool sequence.")
            appendLine("version: 0.1.0")
            appendLine("createdBy: self-improvement")
            appendLine("---")
            appendLine()
            appendLine("# Candidate Workflow")
            appendLine()
            appendLine("Use this only after review/approval. It was inferred from a completed task, not auto-activated.")
            appendLine()
            appendLine("## Trigger")
            appendLine("- Similar user task: ${firstUser.ifBlank { "Repeated multi-tool workflow" }}")
            appendLine()
            appendLine("## Observed Tool Sequence")
            distinctTools.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Notes")
            appendLine("- Review this candidate before applying. Remove task-specific details and secrets before approval.")
        }.trim()
        return listOf(PendingProposal(
            id = "skill_${context.sessionId}_${name}".replace(Regex("[^A-Za-z0-9_-]"), "_"),
            sourceSessionId = context.sessionId,
            type = PendingProposalType.SKILL_CREATE,
            target = name,
            action = PendingProposalAction.CREATE,
            title = "Review reusable workflow: $name",
            content = content,
            reason = "Successful repeated multi-tool flow detected; queued for review, not auto-created.",
            confidence = 0.62,
            importance = 0.45,
            createdAt = context.timestamp,
            status = PendingProposalStatus.PENDING
        ))
    }

    private fun inferCandidateType(text: String): MemoryType {
        val lower = text.lowercase()
        return when {
            listOf("project", "workspace", "repo", "repository", "kode", "codebase").any { it in lower } -> MemoryType.WORKSPACE_FACT
            listOf("call me", "panggil", "namaku", "nama ku", "nama saya", "my name", "prefer", "preference", "jawab saya", "bahasa", "tone", "gaya").any { it in lower } -> MemoryType.USER_PROFILE
            else -> MemoryType.LONG_TERM_MEMORY
        }
    }

    private fun cleanMemoryContent(text: String, remove: Boolean): String {
        var cleaned = text.trim()
        val prefixes = if (remove) listOf(
            "please forget", "forget", "lupakan", "hapus memory", "hapus memori", "jangan ingat", "don't remember", "do not remember", "stop remembering"
        ) else listOf(
            "please remember that", "remember that", "remember", "ingat bahwa", "ingat", "mulai sekarang", "from now on", "from now"
        )
        prefixes.forEach { prefix ->
            cleaned = cleaned.replace(Regex("(?i)^\\s*${Regex.escape(prefix)}[:,]?\\s*"), "")
        }
        return cleaned.trim().trim('.', ';')
    }

    private fun isTrivialGreeting(text: String): Boolean {
        val clean = text.lowercase().replace(Regex("[^a-z0-9\\p{L}]+"), " ").trim()
        return clean in setOf("hai", "halo", "hello", "hi", "hey", "pagi", "siang", "malam")
    }

    private fun stripThinking(text: String): String = text
        .replace(Regex("(?is)<think>.*?</think>"), "")
        .replace(Regex("(?is)<think>.*"), "")
        .replace(Regex("(?is)</think>"), "")
        .trim()

    companion object {
        private val DAILY_REFLECTION_SYSTEM_PROMPT = """
            You are Amaya's private post-chat reflection writer.
            Produce a real daily note from the completed session, not a generic template.

            Return strict JSON only:
            {
              "daily_note": {
                "should_save": true,
                "title": "3-7 word concrete title",
                "summary": "One polished sentence summarizing the specific user-facing outcome or decision.",
                "reason": "Why this session is worth keeping as a daily note."
              }
            }

            Rules:
            - If the session is only a greeting, empty, or has no meaningful outcome, set should_save=false.
            - Summarize the user-facing interaction: what the user wanted, what was decided, and what changed.
            - Never mention internal tools, tool names, tool calls, or "tool-assisted". Daily notes are about the conversation outcome, not implementation mechanics.
            - Do not copy the user's raw wording.
            - Do not write generic lines like "User completed a task" or "User asked/discussed".
            - Do not include secrets, credentials, tokens, OTPs, cookies, or payment details.
            - Prefer concrete wording: what setting, memory, skill, browser task, code change, or decision changed.
        """.trimIndent()

        private val SELF_IMPROVEMENT_TOOLS = setOf(
            "update_memory", "memory_manage", "skill_view", "skill_manage", "session_search", "update_todo"
        )
    }
}
