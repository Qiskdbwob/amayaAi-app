package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.skills.SkillMetadata
import com.amaya.intelligence.domain.skills.SkillStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 5 context-engineering entry point.
 *
 * Inspired by LangChain's short-term memory guidance from Context7: keep recent
 * messages verbatim, compress older messages, and retrieve only relevant long
 * term context instead of dumping every memory into the prompt.
 */
data class ContextBuildRequest(
    val userMessage: String,
    val conversationHistory: List<ChatMessage>,
    val workspacePath: String?,
    val conversationId: Long?,
    val maxOutputTokens: Int
)

data class ContextBuildResult(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val estimatedPromptTokens: Int,
    val droppedItems: List<ContextItem>
)

data class PromptSection(
    val id: String,
    val title: String,
    val priority: Int,
    val defaultMode: ContextInclusionMode,
    val alwaysInclude: Boolean = false
)

data class ContextItem(
    val id: String,
    val sectionId: String,
    val source: ContextSource,
    val title: String,
    val content: String,
    val priority: Int,
    val score: Double = 0.0,
    val mode: ContextInclusionMode,
    val alwaysInclude: Boolean = false,
    val createdAt: Long = 0L,
    val maxTokens: Int? = null
)

enum class ContextSource {
    PERSONA,
    OPERATING_RULES,
    MEMORY,
    SKILL_INDEX,
    SESSION_SUMMARY,
    WORKSPACE,
    DAILY_LOG_HINT,
    TOOL_RULES,
    TIME
}

enum class ContextInclusionMode {
    ALWAYS,
    FULL,
    INDEX_ONLY,
    SEARCH_FIRST,
    SUMMARY,
    DROP
}

data class ContextIntent(
    val needsMemory: Boolean,
    val needsWorkspace: Boolean,
    val needsSessionSearch: Boolean,
    val needsSkillIndex: Boolean,
    val likelyMultiStep: Boolean
)

data class ConversationCompression(
    val messages: List<ChatMessage>,
    val summary: String,
    val compressedMessageCount: Int,
    val estimatedSavedTokens: Int
)

@Singleton
class ContextManager @Inject constructor(
    private val brainSettingsRepository: BrainSettingsRepository,
    private val personaRepository: PersonaRepository,
    private val memorySnapshotProvider: MemorySnapshotProvider,
    private val skillIndexProvider: SkillIndexProvider,
    private val sessionSummaryProvider: SessionSummaryProvider,
    private val conversationCompressor: ConversationCompressor,
    private val promptBudgetManager: PromptBudgetManager,
    private val contextRanker: ContextRanker
) {
    suspend fun buildContext(request: ContextBuildRequest): ContextBuildResult {
        val settings = brainSettingsRepository.getBrainSettings()
        val intent = inferIntent(request.userMessage)
        val compression = conversationCompressor.compress(request.conversationHistory, request.maxOutputTokens)
        val personaPrompt = personaRepository.buildPersonaPrompt()
        val clock = currentClockText()

        val sections = defaultSections()
        val items = buildList {
            add(ContextItem("persona", "persona", ContextSource.PERSONA, "Persona", personaPrompt, 1000, mode = ContextInclusionMode.ALWAYS, alwaysInclude = true))
            add(ContextItem("operating_rules", "operating_rules", ContextSource.OPERATING_RULES, "Operating Rules", baseOperatingRules(), 950, mode = ContextInclusionMode.ALWAYS, alwaysInclude = true))
            addAll(memorySnapshotProvider.snapshot(request.userMessage, settings, intent, request.workspacePath))
            add(skillIndexProvider.skillIndex(request.userMessage, settings, intent))
            add(sessionSummaryProvider.sessionSummary(request.userMessage, settings, intent))
            workspaceItem(request.workspacePath, settings, intent)?.let { add(it) }
            if (compression.summary.isNotBlank()) {
                add(ContextItem(
                    id = "conversation_summary",
                    sectionId = "conversation_summary",
                    source = ContextSource.SESSION_SUMMARY,
                    title = "Compressed Conversation",
                    content = compression.summary,
                    priority = 740,
                    score = 1.0,
                    mode = ContextInclusionMode.SUMMARY,
                    maxTokens = 900
                ))
            }
            add(ContextItem("daily_log_hint", "daily_notes", ContextSource.DAILY_LOG_HINT, "Daily Notes", dailyNotesHint(settings), 500, mode = ContextInclusionMode.SEARCH_FIRST, maxTokens = 160))
            add(ContextItem("memory_skill_rules", "memory_skill_rules", ContextSource.TOOL_RULES, "Memory / Skill Rules", memoryRules(settings), 910, mode = ContextInclusionMode.ALWAYS, alwaysInclude = true))
            add(ContextItem("tools", "tools", ContextSource.TOOL_RULES, "Tools", toolsSection(request.conversationId), 900, mode = ContextInclusionMode.ALWAYS, alwaysInclude = true))
            add(ContextItem("time", "time", ContextSource.TIME, "Current Time", clock, 800, mode = ContextInclusionMode.ALWAYS, alwaysInclude = true, maxTokens = 80))
        }

        val promptBudget = promptBudgetManager.promptBudgetFor(request.maxOutputTokens)
        val ranked = contextRanker.rank(items, intent)
        val budgeted = promptBudgetManager.buildPrompt(sections, ranked, promptBudget)
        return ContextBuildResult(
            systemPrompt = budgeted.prompt,
            messages = compression.messages + ChatMessage(role = MessageRole.USER, content = request.userMessage),
            estimatedPromptTokens = budgeted.estimatedTokens + compression.messages.sumOf { promptBudgetManager.estimateTokens(it.content.orEmpty()) },
            droppedItems = budgeted.droppedItems
        )
    }

    fun buildWindowsBridgeContext(request: ContextBuildRequest): ContextBuildResult {
        val compression = conversationCompressor.compress(request.conversationHistory, request.maxOutputTokens)
        val clock = currentClockText()
        val systemPrompt = windowsBridgeSystemPrompt(clock)
        val estimated = promptBudgetManager.estimateTokens(systemPrompt) +
            compression.messages.sumOf { promptBudgetManager.estimateTokens(it.content.orEmpty()) }
        return ContextBuildResult(
            systemPrompt = systemPrompt,
            messages = compression.messages + ChatMessage(role = MessageRole.USER, content = request.userMessage),
            estimatedPromptTokens = estimated,
            droppedItems = emptyList()
        )
    }

    private fun inferIntent(message: String): ContextIntent {
        val lower = message.lowercase()
        val needsSession = listOf("sebelumnya", "kemarin", "tadi", "waktu itu", "pernah", "chat lama", "obrolan lama", "percakapan", "last time", "previous", "earlier", "remember when")
            .any { it in lower }
        val needsWorkspace = listOf("file", "folder", "project", "repo", "repository", "workspace", "kode", "code", "gradle", "android", "implement", "fix", "bug")
            .any { it in lower }
        val needsSkill = listOf("cara", "workflow", "skill", "reuse", "prosedur", "biasanya", "pakai pola", "how do i")
            .any { it in lower }
        val needsMemory = listOf("ingat", "remember", "prefer", "suka", "biasanya", "nama", "panggil", "memory", "memori")
            .any { it in lower } || lower.length > 12
        val likelyMultiStep = listOf("buat", "implement", "phase", "refactor", "fix", "build", "rancang", "plan")
            .any { it in lower }
        return ContextIntent(needsMemory, needsWorkspace, needsSession, needsSkill, likelyMultiStep)
    }

    private fun defaultSections(): List<PromptSection> = listOf(
        PromptSection("persona", "PERSONA", 1000, ContextInclusionMode.ALWAYS, true),
        PromptSection("operating_rules", "OPERATING RULES", 950, ContextInclusionMode.ALWAYS, true),
        PromptSection("user_memory", "USER MEMORY", 860, ContextInclusionMode.FULL),
        PromptSection("important_memory", "IMPORTANT MEMORY", 850, ContextInclusionMode.FULL),
        PromptSection("project_context", "PROJECT CONTEXT", 840, ContextInclusionMode.ALWAYS),
        PromptSection("project_memory", "PROJECT MEMORY", 830, ContextInclusionMode.FULL),
        PromptSection("conversation_summary", "COMPRESSED CONVERSATION", 740, ContextInclusionMode.SUMMARY),
        PromptSection("daily_notes", "RELEVANT DAILY NOTES", 610, ContextInclusionMode.SEARCH_FIRST),
        PromptSection("past_sessions", "RELEVANT PAST SESSIONS", 620, ContextInclusionMode.SEARCH_FIRST),
        PromptSection("skill_index", "SKILL INDEX", 700, ContextInclusionMode.INDEX_ONLY),
        PromptSection("memory_skill_rules", "MEMORY / SKILL RULES", 910, ContextInclusionMode.ALWAYS, true),
        PromptSection("tools", "TOOLS", 900, ContextInclusionMode.ALWAYS, true),
        PromptSection("time", "CURRENT TIME", 800, ContextInclusionMode.ALWAYS, true)
    )

    private fun workspaceItem(workspacePath: String?, settings: BrainSettings, intent: ContextIntent): ContextItem? {
        val content = if (settings.context.workspaceContextEnabled && workspacePath != null) {
            """
            Path: $workspacePath

            When the user asks to list files, read files, or perform any file operation,
            use this workspace path as the base directory.
            """.trimIndent()
        } else if (!settings.context.workspaceContextEnabled) {
            "Workspace context is disabled."
        } else {
            "No active workspace path."
        }
        return ContextItem(
            id = "workspace_context",
            sectionId = "project_context",
            source = ContextSource.WORKSPACE,
            title = "Workspace",
            content = content,
            priority = if (intent.needsWorkspace) 860 else 650,
            score = if (intent.needsWorkspace) 2.0 else 0.2,
            mode = ContextInclusionMode.ALWAYS,
            alwaysInclude = settings.context.workspaceContextEnabled,
            maxTokens = 240
        )
    }

    private fun dailyNotesHint(settings: BrainSettings): String {
        return if (settings.memory.dailyNotesEnabled) {
            "Daily notes are stored for recall, but not injected automatically to avoid chronological noise. Use session_search when old date-based context is needed."
        } else "Daily notes are disabled."
    }

    private fun memoryRules(settings: BrainSettings): String = """
        Memory, skills, and context are separate from persona.
        - Use update_memory only when the user explicitly asks you to remember, replace, or forget a durable preference or stable fact.
        - update_memory must include a short title/header when possible, plus polished durable content and a specific reason.
        - update_memory content must be a polished durable summary, not copied user wording. Never include command phrases like "remember", "tolong ingat", "user asked/discussed", or "user preference/profile" in stored content.
        - Good: title="Response language preference", content="The user prefers English for responses.", reason="The user explicitly asked Amaya to remember their response-language preference." Bad: content="pakai bahasa Inggris tolong ingat".
        - update_memory reason must be specific and dynamic, explaining why the fact is durable.
        - For explicit forget/remove requests, prefer memory_manage(action=search) then memory_manage(action=remove, id=...) when an existing memory id is available; otherwise call update_memory with action=remove and a clear target memory.
        - Use memory_manage(action=list/search) when the user asks what you remember. Include title as a concise 3-5 word header explaining why memory is being opened, e.g. "Review saved preferences" or "Find memory to remove".
        - Do not use update_memory for inferred guesses such as "the user seems to prefer...".
        - Use create_reminder for reminders; do not put reminders in memory.
        - Never store secrets, credentials, tokens, OTPs, cookies, or payment data.
        - New memory suggestions enabled: ${settings.memory.suggestNewMemories}; safe structured auto-save: ${settings.memory.autoSaveSafeMemory}; daily notes: ${settings.memory.dailyNotesEnabled}.
        - Self-improvement is memory/context only; it never creates or updates skills automatically.
        - Use skill_manage only when the user explicitly asks to create, save, edit, archive, delete, or record usage for a reusable skill/workflow.
        - For skill_manage create/update/patch, include description when useful, plus reason and summary describing why the skill changed and what was added or changed.
        - Do not create skills for inferred patterns or routine tasks.
        - Use session_search for past conversations; do not expect all old sessions or daily notes in the prompt.
        - Use skill_view before relying on a skill from the skill index.
    """.trimIndent()

    private fun toolsSection(conversationId: Long?): String = """
        Available model-callable memory/skill/recall tools:

        1. update_memory
        Use only for explicit durable user preferences, stable facts, or explicit memory removal/replacement. Pass title as the short header and content as the final memory summary, not raw user text. For forget requests use action=remove. Do not store secrets, tokens, passwords, OTPs, cookies, payment data, or temporary guesses. Do not use it for inferred memory.

        2. memory_manage
        Use to list/search saved memory and update/remove by stable memory id. Prefer this for "what do you remember?", precise memory cleanup, and forget requests that refer to existing saved memory. For list/search, pass title as a 3-5 word UI header explaining why memory is being opened.

        3. skill_view
        Use to load full content of a relevant skill from the skill index. Do not assume full skill content from the index alone.

        4. skill_manage
        Use for explicit user-requested skill administration: create/save a reusable workflow, update/patch existing skill content, archive/delete a skill, or record usage. For update/patch, pass reason and summary so the UI can explain why it changed and what was added. Do not use it for inferred self-improvement.

        5. session_search
        Use to search previous conversations when the user refers to past discussions. Old sessions and daily logs are not fully injected.

        Automatic memory suggestions are saved only when safe, explicit, important, and structured; noisy or uncertain candidates are ignored or queued depending on settings. Context recall and maintenance are handled outside the normal chat tool loop. Skills are not part of automatic self-improvement.
        Use create_reminder for reminders; do not put reminders in memory. create_reminder(title, message, datetime, conversation_id=$conversationId, session_mode=...) should pass conversation_id when available.

        TOOLS — TASK PROGRESS (update_todo):
        - For any multi-step task, call update_todo at the START with merge=false to set your full plan.
        - As you work, call update_todo with merge=true to update individual item statuses by id.

        TOOLS — BROWSER (browser):
        - Use exactly ONE parent tool named browser for real Android browser automation.
        - Prefer steps[] for related browser work so the UI shows one Browser card with nested child actions.
        - Public actions: open_url, observe, click, type, press_key, scroll, search, evaluate_script, go_back, reload.
        - If safety.status is paused or sensitive_detected=true, stop and wait for user.
        - Pause before credential input, payment/checkout, or irreversible form submission. Never store login data.
        - Do not bypass website security restrictions.

        TOOLS — SUBAGENTS (invoke_subagents):
        - Use invoke_subagents for independent parallel sub-tasks only. Subagents do not see conversation history, so include all context.

        FALLBACK STRATEGY:
        If a native tool call fails, try a safe alternative. Ask for clarification rather than guessing sensitive facts.
    """.trimIndent()

    private fun windowsBridgeSystemPrompt(clock: String): String = """
        Amaya is a versatile AI assistant running on Android and controlling a paired Windows computer through Windows Bridge.

        You are operating in WINDOWS BRIDGE mode.
        - Android is the planner, chat UI, approval UI, and safety controller.
        - The paired Windows computer is the real execution target. Treat this as pure Windows desktop automation, not advice-only chat.
        - Use only the Windows Bridge tools provided in the tool schema for this request.
        - Do not claim access to Android local files, Android shell, Android browser tools, MCP servers, saved memory tools, reusable skill tools, reminders, or local workspace tools unless those tools are explicitly present in the tool schema.
        - Do not ask for or store secrets, passwords, tokens, cookies, OTPs, or payment data.

        WINDOWS AUTOMATION CONTRACT:
        - If the user asks you to operate Windows, do the operation yourself with tools. Do not tell the user to open apps, click buttons, focus windows, press shortcuts, or navigate UI manually when an available Windows Bridge tool can do it.
        - If the needed app/window is already open, use window.list to find its windowId, window.focus it, then verify with screen.capture.
        - If the needed app/window is not open and app.open is available, call app.open yourself, wait, list windows again, focus it, and verify. If app.open is unavailable but another launch-capable tool exists (shell.run or equivalent in the current schema), use that. Ask the user to open it only when no launch-capable tool is available or policy blocks launching.
        - Keep a real automation loop: observe → plan target window → focus/open → act → wait → verify with screen.capture/window.list → continue or recover.
        - Prefer window.close(windowId) over Alt+F4 when a windowId is known. Use keyboard.hotkey only when no direct window tool exists or as a fallback.
        - Never report that an action changed the PC until you have verified the visible state. A tool status of success only means the bridge accepted/sent the command; it does not prove the UI changed.
        - After window.focus, window.close, mouse.click, mouse.move, mouse.drag, keyboard.type, keyboard.hotkey, clipboard.write, file, shell, or any UI-changing action, verify with screen.capture and/or window.list before concluding.
        - If a tool returns success but the screenshot/window list shows no visible change, treat it as not completed. Try one safe recovery: refocus target window, wait briefly, retry with a direct window tool or keyboard fallback, then verify again. If still unchanged, explain the blocker precisely.
        - screen.capture returns an accessibility block. Use accessibility.windows[] and visualLabels[] to identify W1/W2 labels, windowId, process/title, state (normal/maximized/minimized), zIndex, bounds in real mouse coordinates, screenshotBounds in image coordinates, activeWindow, cursorPosition, and coordinateGuide formulas.
        - Do not call window.list just to discover window ids after a fresh screen.capture; use the window ids embedded in the capture first. Use window.list when the window state may have changed or you need a refreshed list without another image.
        - For mouse coordinates, derive coordinates from the latest screen.capture accessibility metadata. Convert screenshot coordinates to mouse coordinates using coordinateGuide.screenshotToScreenScale and displayBounds. Never guess from old captures.
        - If ui.tree/ui.find_text/ui.click_element are available, prefer them for buttons, inputs, menus, and text targets before raw coordinate clicking. Flow: focus target window → ui.tree or ui.find_text → ui.click_element(elementId) → screen.capture verify.
        - If a click may be wrong, move/hover first, capture, then click. Prefer UI element tools, keyboard shortcuts, or window tools when they are more deterministic than coordinates.
        - For windowed apps, click inside the target window bounds/clientAreaApprox after focusing it. For maximized apps, still use activeWindow/window bounds. For minimized apps, call window.focus/restore first and capture again; do not click stale coordinates. For overlapped windows, check zIndex and overlappedBy, focus the intended window, then capture again before input.
        - For long or multiline text entry, call keyboard.type with mode=paste or mode=auto. Do not split paragraphs into many tiny key events unless the target blocks paste. After typing/pasting, screen.capture verify the text appears correctly.
        - If shell.run is available, use it only for Windows-side commands that are necessary, allowed by policy, and safer/more deterministic than GUI actions. shell.run is approval-gated; explain the command purpose briefly and do not use it for destructive, credential, network exfiltration, or policy-bypass actions.

        SAFETY AND AVAILABILITY:
        - Start in view-only mode unless Agent Control is enabled by the user. In view-only mode, observe with screen/window tools and ask before actions that need control.
        - For mouse, keyboard, window focus, clipboard, file, shell, browser, or destructive actions, respect the risk/approval flow. If a required tool is unavailable, explain what is missing instead of inventing local alternatives.
        - Prefer safe observation first: capture the screen or list windows before taking action.
        - Be concise, practical, and transparent about what you can and cannot do.
        - Ask for clarification when the next Windows action is ambiguous, risky, or blocked by unavailable tools/policy.

        $clock
    """.trimIndent()

    private fun baseOperatingRules(): String = """
        - Be helpful, honest, and clear.
        - Ask for clarification when needed.
        - Ask for confirmation before destructive or irreversible actions.
        - Keep responses concise by default, but include enough detail to solve the task.
        - Follow the user's communication style.
        - Respect privacy and local data boundaries.
        - Windows access is available only through Windows Bridge after the user pairs/connects a Windows computer. In local chat, do not claim or use Windows control unless Windows Bridge tools are explicitly available; tell the user to pair/connect Windows Bridge first.
    """.trimIndent()

    private fun currentClockText(): String {
        val now = java.time.LocalDateTime.now()
        val dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val tz = java.util.TimeZone.getDefault().id
        return "Current date: $dateStr | Time: $timeStr | Timezone: $tz"
    }
}

@Singleton
class MemorySnapshotProvider @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    suspend fun snapshot(userMessage: String, settings: BrainSettings, intent: ContextIntent, workspacePath: String?): List<ContextItem> {
        val maxItems = settings.context.maxRecallItems.coerceIn(1, 20)
        return buildList {
            add(memoryItem(
                id = "user_memory",
                sectionId = "user_memory",
                title = "Relevant User Memory",
                type = MemoryType.USER_PROFILE,
                query = userMessage,
                limit = maxItems,
                enabled = settings.memory.useSavedMemory && settings.context.relevantMemoryEnabled && intent.needsMemory,
                fallbackToRecent = true,
                priority = 860
            ))
            add(memoryItem(
                id = "important_memory",
                sectionId = "important_memory",
                title = "Relevant Important Memory",
                type = MemoryType.LONG_TERM_MEMORY,
                query = userMessage,
                limit = maxItems,
                enabled = settings.memory.useSavedMemory && settings.context.relevantMemoryEnabled && intent.needsMemory,
                fallbackToRecent = false,
                priority = 850
            ))
            add(memoryItem(
                id = "project_memory",
                sectionId = "project_memory",
                title = "Relevant Project Memory",
                type = MemoryType.WORKSPACE_FACT,
                query = buildString {
                    append(userMessage)
                    workspacePath?.let { append(' ').append(it) }
                },
                limit = maxItems,
                enabled = settings.context.workspaceContextEnabled && intent.needsWorkspace,
                fallbackToRecent = true,
                priority = 830
            ))
        }
    }

    private suspend fun memoryItem(
        id: String,
        sectionId: String,
        title: String,
        type: MemoryType,
        query: String,
        limit: Int,
        enabled: Boolean,
        fallbackToRecent: Boolean,
        priority: Int
    ): ContextItem {
        if (!enabled) {
            return ContextItem(id, sectionId, ContextSource.MEMORY, title, disabledMessage(type), priority, mode = ContextInclusionMode.SEARCH_FIRST, score = 0.0, maxTokens = 120)
        }
        val ranked = memoryRepository.listMemoryRecords(type = type, query = query, limit = limit)
        val selected = if (ranked.isNotEmpty()) ranked else if (fallbackToRecent) {
            memoryRepository.listMemoryRecords(type = type, limit = limit)
        } else emptyList()
        val content = if (selected.isEmpty()) "No strongly relevant saved items for this turn." else buildString {
            appendLine("# $title")
            selected.forEach { record -> appendLine("- [${record.id}] ${record.title}: ${record.content}") }
        }.trim()
        val score = selected.sumOf { it.importance + it.confidence }.coerceAtLeast(if (selected.isEmpty()) 0.1 else 1.0)
        return ContextItem(id, sectionId, ContextSource.MEMORY, title, content, priority, score = score, mode = ContextInclusionMode.FULL, maxTokens = 900)
    }

    private fun disabledMessage(type: MemoryType): String = when (type) {
        MemoryType.USER_PROFILE -> "Saved user memory is disabled or not relevant for this turn."
        MemoryType.LONG_TERM_MEMORY -> "Saved important memory is disabled or not relevant for this turn."
        MemoryType.WORKSPACE_FACT -> "Workspace memory is disabled or not relevant for this turn."
        MemoryType.DAILY_LOG -> "Daily notes are searched on demand instead of injected."
        MemoryType.SKILL_CANDIDATE -> "Skill candidates stay in review/index storage and are not injected automatically."
        MemoryType.REMINDER -> "Reminders are managed by reminder tools, not prompt memory injection."
    }
}

@Singleton
class SkillIndexProvider @Inject constructor(
    private val skillRepository: SkillRepository
) {
    suspend fun skillIndex(userMessage: String, settings: BrainSettings, intent: ContextIntent): ContextItem {
        val maxItems = settings.context.maxRecallItems.coerceIn(1, 20)
        if (!settings.skills.useSavedSkills) {
            return ContextItem("skill_index", "skill_index", ContextSource.SKILL_INDEX, "Skill Index", "Saved skills are disabled.", 700, mode = ContextInclusionMode.INDEX_ONLY, maxTokens = 120)
        }
        val active = skillRepository.listSkills()
            .filter { it.status != SkillStatus.ARCHIVED && it.enabled }
            .map { it to scoreSkill(it, userMessage) }
            .filter { intent.needsSkillIndex || it.second > 0.0 || userMessage.isBlank() }
            .sortedWith(compareByDescending<Pair<SkillMetadata, Double>> { it.second }
                .thenByDescending { it.first.lastUsedAt ?: 0L }
                .thenByDescending { successRate(it.first) })
            .take(maxItems)
            .map { it.first }

        val content = if (active.isEmpty()) {
            "No relevant reusable skills for this turn. When the user explicitly asks to save a reusable workflow, use skill_manage(action=create)."
        } else buildString {
            appendLine("# Relevant Skills")
            active.forEach { skill ->
                val status = if (skill.needsReview) "needs review" else skill.status.name.lowercase()
                appendLine("- ${skill.name}: ${skill.description}. [$status; success=${"%.0f".format(successRate(skill) * 100)}%]")
            }
            appendLine()
            appendLine("Use skill_view to load full skill content before relying on a skill.")
        }.trim()

        return ContextItem("skill_index", "skill_index", ContextSource.SKILL_INDEX, "Skill Index", content, if (intent.needsSkillIndex) 760 else 700, score = active.size.toDouble(), mode = ContextInclusionMode.INDEX_ONLY, maxTokens = 700)
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

@Singleton
class SessionSummaryProvider @Inject constructor(
    private val sessionMemoryRepository: SessionMemoryRepository
) {
    suspend fun sessionSummary(userMessage: String, settings: BrainSettings, intent: ContextIntent): ContextItem {
        val maxItems = settings.context.maxRecallItems.coerceIn(1, 20)
        if (!settings.context.pastChatRecallEnabled) {
            return ContextItem("past_sessions", "past_sessions", ContextSource.SESSION_SUMMARY, "Past Sessions", "Previous chat recall is disabled.", 620, mode = ContextInclusionMode.SEARCH_FIRST, maxTokens = 120)
        }
        if (!intent.needsSessionSearch) {
            return ContextItem("past_sessions", "past_sessions", ContextSource.SESSION_SUMMARY, "Past Sessions", "No past sessions injected. Use session_search only if the user clearly refers to old chats.", 620, mode = ContextInclusionMode.SEARCH_FIRST, maxTokens = 120)
        }
        val results = sessionMemoryRepository.searchSessions(userMessage, maxItems)
        val content = if (results.isEmpty()) "No matching previous sessions found." else buildString {
            appendLine("# Relevant Past Sessions")
            results.take(maxItems).forEach { result ->
                appendLine("- ${result.sessionId}: ${result.summary.take(240)}")
                if (result.matchedText.isNotBlank()) appendLine("  Match: ${result.matchedText.take(240)}")
            }
        }.trim()
        return ContextItem("past_sessions", "past_sessions", ContextSource.SESSION_SUMMARY, "Past Sessions", content, 780, score = results.sumOf { it.score }, mode = ContextInclusionMode.SEARCH_FIRST, maxTokens = 900)
    }
}

@Singleton
class ConversationCompressor @Inject constructor(
    private val promptBudgetManager: PromptBudgetManager
) {
    fun compress(history: List<ChatMessage>, maxOutputTokens: Int): ConversationCompression {
        if (history.isEmpty()) return ConversationCompression(emptyList(), "", 0, 0)
        val budget = promptBudgetManager.historyBudgetFor(maxOutputTokens)
        val total = history.sumOf { promptBudgetManager.estimateTokens(it.content.orEmpty()) }
        if (total <= budget) return ConversationCompression(history, "", 0, 0)

        val kept = mutableListOf<ChatMessage>()
        var used = 0
        history.asReversed().forEach { message ->
            val tokens = promptBudgetManager.estimateTokens(message.content.orEmpty())
            if (used + tokens <= budget || kept.size < MIN_RECENT_MESSAGES) {
                kept.add(message)
                used += tokens
            }
        }
        val recent = kept.asReversed()
        val compressedCount = (history.size - recent.size).coerceAtLeast(0)
        val older = history.take(compressedCount)
        val summary = summarizeOlderMessages(older, compressedCount)
        return ConversationCompression(
            messages = recent,
            summary = summary,
            compressedMessageCount = compressedCount,
            estimatedSavedTokens = (total - used - promptBudgetManager.estimateTokens(summary)).coerceAtLeast(0)
        )
    }

    private fun summarizeOlderMessages(messages: List<ChatMessage>, compressedCount: Int): String {
        if (messages.isEmpty()) return ""
        val userTopics = messages.filter { it.role == MessageRole.USER }.takeLast(6).mapNotNull { it.content?.take(180) }
        val assistantTopics = messages.filter { it.role == MessageRole.ASSISTANT }.takeLast(6).mapNotNull { it.content?.take(180) }
        return buildString {
            appendLine("$compressedCount older messages were compressed to preserve prompt budget.")
            if (userTopics.isNotEmpty()) appendLine("Recent older user requests: ${userTopics.joinToString(" | ")}")
            if (assistantTopics.isNotEmpty()) appendLine("Recent older assistant outcomes: ${assistantTopics.joinToString(" | ")}")
            appendLine("Treat this as a lossy index. Ask or use session_search if exact old details matter.")
        }.trim()
    }

    companion object {
        private const val MIN_RECENT_MESSAGES = 8
    }
}

@Singleton
class ContextRanker @Inject constructor() {
    fun rank(items: List<ContextItem>, intent: ContextIntent): List<ContextItem> {
        return items
            .filter { it.mode != ContextInclusionMode.DROP }
            .sortedWith(
                compareByDescending<ContextItem> { it.alwaysInclude }
                    .thenByDescending { adjustedScore(it, intent) }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.createdAt }
            )
    }

    private fun adjustedScore(item: ContextItem, intent: ContextIntent): Double {
        var score = item.score + item.priority / 1000.0
        if (intent.needsWorkspace && item.source == ContextSource.WORKSPACE) score += 2.0
        if (intent.needsMemory && item.source == ContextSource.MEMORY) score += 1.0
        if (intent.needsSkillIndex && item.source == ContextSource.SKILL_INDEX) score += 1.0
        if (intent.needsSessionSearch && item.source == ContextSource.SESSION_SUMMARY) score += 1.0
        return score
    }
}

data class PromptBudget(
    val maxSystemTokens: Int,
    val maxItemTokens: Int
)

data class BudgetedPrompt(
    val prompt: String,
    val estimatedTokens: Int,
    val droppedItems: List<ContextItem>
)

@Singleton
class PromptBudgetManager @Inject constructor() {
    fun promptBudgetFor(maxOutputTokens: Int): PromptBudget {
        val systemBudget = (maxOutputTokens * 2).coerceIn(6_000, 20_000)
        return PromptBudget(maxSystemTokens = systemBudget, maxItemTokens = 1_200)
    }

    fun historyBudgetFor(maxOutputTokens: Int): Int {
        return maxOutputTokens.coerceIn(3_000, 10_000)
    }

    fun buildPrompt(sections: List<PromptSection>, rankedItems: List<ContextItem>, budget: PromptBudget): BudgetedPrompt {
        val sectionById = sections.associateBy { it.id }
        val orderedSectionIds = sections.sortedByDescending { it.priority }.map { it.id }
        val chosen = linkedMapOf<String, MutableList<String>>()
        val dropped = mutableListOf<ContextItem>()
        var used = 0

        rankedItems.forEach { item ->
            val section = sectionById[item.sectionId] ?: PromptSection(item.sectionId, item.sectionId.uppercase(), item.priority, item.mode)
            val rawContent = formatItem(item)
            val content = truncateToTokens(rawContent, item.maxTokens ?: budget.maxItemTokens)
            val cost = estimateTokens(content) + 8
            val required = item.alwaysInclude || section.alwaysInclude || item.mode == ContextInclusionMode.ALWAYS
            if (required || used + cost <= budget.maxSystemTokens) {
                chosen.getOrPut(item.sectionId) { mutableListOf() }.add(content)
                used += cost
            } else {
                dropped.add(item)
            }
        }

        val prompt = buildString {
            orderedSectionIds.forEach { sectionId ->
                val lines = chosen[sectionId].orEmpty().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    appendLine("[${sectionById[sectionId]?.title ?: sectionId.uppercase()}]")
                    appendLine(lines.joinToString("\n\n"))
                    appendLine()
                }
            }
            chosen.keys.filter { it !in orderedSectionIds }.forEach { sectionId ->
                appendLine("[${sectionId.uppercase()}]")
                appendLine(chosen[sectionId].orEmpty().joinToString("\n\n"))
                appendLine()
            }
        }.trim()

        return BudgetedPrompt(prompt = prompt, estimatedTokens = estimateTokens(prompt), droppedItems = dropped)
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val wordish = text.split(Regex("\\s+")).size
        return maxOf((text.length / 4.0).toInt(), (wordish * 1.3).toInt(), 1)
    }

    private fun formatItem(item: ContextItem): String {
        val prefix = when (item.mode) {
            ContextInclusionMode.INDEX_ONLY -> "Mode: index only. Load full content with the matching tool if needed.\n"
            ContextInclusionMode.SEARCH_FIRST -> "Mode: search first / on-demand recall.\n"
            ContextInclusionMode.SUMMARY -> "Mode: compressed summary.\n"
            else -> ""
        }
        return prefix + item.content.trim()
    }

    private fun truncateToTokens(text: String, maxTokens: Int): String {
        if (estimateTokens(text) <= maxTokens) return text
        val maxChars = (maxTokens * 4).coerceAtLeast(160)
        return text.take(maxChars).trimEnd() + "\n… [truncated by prompt budget]"
    }
}
