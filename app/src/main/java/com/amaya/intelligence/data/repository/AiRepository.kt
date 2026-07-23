package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.files.FileWorkspaceMemoryStore
import com.amaya.intelligence.data.remote.api.*
import com.amaya.intelligence.data.remote.mcp.McpClientManager
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import com.amaya.intelligence.tools.toAiToolDefinition
import com.amaya.intelligence.util.debugLog
import com.amaya.intelligence.util.errorLog

import com.amaya.intelligence.di.ApplicationScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentRuntimeTarget {
    LOCAL,
    WINDOWS_BRIDGE
}

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
private val INTEGER_TEXT = Regex("[+-]?\\d+")
private const val MAX_STREAM_CONTINUATIONS = 5
internal const val STREAM_CONTINUATION_PROMPT = "Continue the previous response exactly where it stopped. Do not repeat any text. Use tools if needed to complete the request."
private const val TOOL_RESULT_TRUNCATION_MARKER = "\n… [tool result truncated by context budget]"
private const val AUTO_COMPACTION_MARKER = "[AUTO-COMPACTED ACTIVE CONTEXT]"

internal fun truncateToolResultForContext(content: String, maxChars: Int): String = when {
    content.length <= maxChars -> content
    maxChars <= TOOL_RESULT_TRUNCATION_MARKER.length -> TOOL_RESULT_TRUNCATION_MARKER.take(maxChars)
    else -> content.take(maxChars - TOOL_RESULT_TRUNCATION_MARKER.length).trimEnd() + TOOL_RESULT_TRUNCATION_MARKER
}

internal fun autoCompactionTranscript(messages: List<ChatMessage>, maxChars: Int): String = buildString {
    messages.forEach { message ->
        appendLine("${message.role}:")
        appendLine(message.content.orEmpty())
        message.toolCalls.orEmpty().forEach { call -> appendLine("tool_call ${call.name}: ${call.arguments}") }
        message.toolResult?.let { result -> appendLine("tool_result ${result.toolCallId}: ${result.content}") }
    }
}.trim().take(maxChars)

internal fun messagesOmittedByContextFit(messages: List<ChatMessage>, fitted: List<ChatMessage>): List<ChatMessage> {
    if (fitted.isEmpty() || fitted.size == messages.size) return emptyList()
    val latestUserIndex = messages.indexOfLast { message ->
        message.role == MessageRole.USER && (!message.content.isNullOrBlank() || message.images.isNotEmpty())
    }
    if (latestUserIndex < 0) return emptyList()
    val retainedTailStart = messages.size - (fitted.size - 1).coerceAtLeast(0)
    return messages.take(latestUserIndex) + messages.subList(latestUserIndex + 1, retainedTailStart)
}

internal fun canContinueStream(response: ChatResponse, hasToolCalls: Boolean): Boolean =
    !hasToolCalls && when (response) {
        is ChatResponse.Incomplete -> response.retryable
        is ChatResponse.Error -> response.retryable
        else -> false
    }

internal fun repeatedBrowserFailureWarning(signature: String, count: Int): String? =
    if (count < 2) null else "Warning: the same browser tool error has occurred $count times ($signature). Do not repeat the same call unchanged. Inspect the current page state, choose a different action, or ask the user for clarification."

internal fun shouldExecuteReceivedToolCalls(response: ChatResponse, hasToolCalls: Boolean): Boolean =
    hasToolCalls && when (response) {
        is ChatResponse.Incomplete -> response.retryable
        is ChatResponse.Error -> response.retryable
        else -> false
    }

internal fun hasProviderUserQuery(messages: List<ChatMessage>): Boolean = messages.any { message ->
    message.role == MessageRole.USER && (!message.content.isNullOrBlank() || message.images.isNotEmpty())
}

fun normalizeIntegerArgument(value: Any?): Any? = when (value) {
    is String -> value.trim().takeIf(INTEGER_TEXT::matches)?.toLongOrNull() ?: value
    is Number -> value.toDouble().takeIf(Double::isFinite)?.takeIf { it % 1.0 == 0.0 }?.let { number ->
        when {
            number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() -> number.toInt()
            number in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() -> number.toLong()
            else -> value
        }
    } ?: value
    else -> value
}

internal fun fallbackConversationTitle(userMessage: String): String =
    userMessage
        .replace(TITLE_WHITESPACE, " ")
        .trim()
        .ifBlank { TITLE_FALLBACK }

internal fun extractConversationTitle(raw: String): String? {
    val cleaned = raw.replace(THINK_BLOCK, " ").trim()
    val candidates = buildList {
        TITLE_TAG.find(cleaned)?.groupValues?.getOrNull(1)?.let(::add)
        TITLE_QUOTED.findAll(cleaned).forEach { add(it.groupValues[1]) }
        cleaned.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.split(TITLE_SEPARATOR, limit = 2)
            ?.firstOrNull()
            ?.let(::add)
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate
            .replace(TITLE_EDGE_MARKUP, "")
            .replace(TITLE_PREFIX, "")
            .replace(TITLE_EDGE_MARKUP, "")
            .replace(TITLE_TRAILING_PUNCTUATION, "")
            .replace(TITLE_WHITESPACE, " ")
            .trim()
            .takeIf { title ->
                !TITLE_META.containsMatchIn(title) &&
                    title.length <= 60 &&
                    title.split(TITLE_WHITESPACE).size in 2..5
            }
    }
}

internal fun sanitizeConversationTitle(raw: String, fallback: String): String =
    extractConversationTitle(raw) ?: fallback

/**
 * Repository for AI interactions.
 *
 * Coordinates between:
 * - Multiple AI providers (Anthropic, OpenAI, Gemini)
 * - Tool execution engine
 * - Project context (file index)
 *
 * Implements the agentic loop:
 * 1. Send user message + context to AI
 * 2. AI responds with text and/or tool calls
 * 3. Execute tools and send results back
 * 4. Repeat until AI is done
 */
@Singleton
class AiRepository @Inject constructor(
    private val anthropicProvider: AnthropicProvider,
    private val openAiProvider: OpenAiProvider,
    private val geminiProvider: GeminiProvider,
    private val settingsManager: AiSettingsManager,
    private val toolExecutor: ToolExecutor,
    private val agentDao: AgentDao,
    private val projectDao: ProjectDao,
    private val mcpToolExecutor: com.amaya.intelligence.data.remote.mcp.McpToolExecutor,
    private val windowsBridgeToolProvider: com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider,
    private val fileIndexRepository: FileIndexRepository,
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val workspaceMemoryStore: FileWorkspaceMemoryStore,
    private val skillRepository: SkillRepository,
    private val selfImprovementPipeline: SelfImprovementPipeline,
    private val contextManager: ContextManager,
    private val referenceDocumentRepository: ReferenceDocumentRepository,
    private val agentMemoryRepository: AgentMemoryRepository,
    private val mcpClientManager: McpClientManager,
    // FIX 5.11: Inject application-scoped coroutine scope — no more manual SupervisorJob leak
    @ApplicationScope private val repoScope: CoroutineScope
) {
    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        const val CONTEXT_SAFETY_RESERVE_TOKENS = 1_024
        const val AUTO_COMPACTION_OUTPUT_TOKENS = 2_048
        const val AUTO_COMPACTION_SAFETY_TOKENS = 1_024
    }

    // FIX 5.11: Removed manual repoJob/repoScope and close() — lifecycle managed by Hilt ApplicationScope

    /** Hermes-style compaction: model summary of the active session, optionally focused. */
    suspend fun compressConversation(
        conversationHistory: List<ChatMessage>,
        selectedModel: String,
        connectionId: String?,
        focus: String
    ): Result<String> = runCatching {
        require(conversationHistory.isNotEmpty()) { "Nothing to compress" }
        val settings = settingsManager.getSettings()
        val resolvedConnectionId = connectionId ?: settings.activeSelection?.connectionId
        val connection = settings.connections.firstOrNull { it.id == resolvedConnectionId }
            ?: error("No model connection selected")
        val model = selectedModel.ifBlank { settings.activeSelection?.modelId.orEmpty() }
        require(model.isNotBlank()) { "No model selected" }
        val summary = StringBuilder()
        var failure: String? = null
        resolveProvider(connection).chat(
            ChatRequest(
                model = model,
                messages = conversationHistory,
                systemPrompt = compressionPrompt(focus),
                maxTokens = 2_048,
                stream = true,
                connectionId = connection.id,
                providerId = connection.providerId,
                effort = ThinkingEffort.NONE
            )
        ).collect { response ->
            when (response) {
                is ChatResponse.TextDelta -> summary.append(response.text)
                is ChatResponse.Error -> failure = response.message
                is ChatResponse.Incomplete -> failure = response.reason
                else -> Unit
            }
        }
        check(failure == null) { failure.orEmpty() }
        summary.toString().trim().takeIf(String::isNotBlank) ?: error("Compression returned no summary")
    }

    private suspend fun summarizeAutoCompaction(
        provider: AiProvider,
        connection: ProviderConnection,
        model: String,
        previousSummary: String?,
        messages: List<ChatMessage>,
        maxInputTokens: Int
    ): String? {
        val inputChars = ((maxInputTokens - AUTO_COMPACTION_OUTPUT_TOKENS - AUTO_COMPACTION_SAFETY_TOKENS)
            .coerceAtLeast(256) * 4)
        val transcript = buildString {
            previousSummary?.takeIf(String::isNotBlank)?.let {
                appendLine("Previous active-context summary:")
                appendLine(it)
                appendLine()
            }
            append(autoCompactionTranscript(messages, inputChars))
        }.take(inputChars)
        if (transcript.isBlank()) return null
        val summary = StringBuilder()
        var failed = false
        provider.chat(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(MessageRole.USER, transcript)),
                systemPrompt = compressionPrompt("Keep the active task executable across further tool iterations."),
                maxTokens = AUTO_COMPACTION_OUTPUT_TOKENS,
                stream = true,
                connectionId = connection.id,
                providerId = connection.providerId,
                effort = ThinkingEffort.NONE
            )
        ).collect { response ->
            when (response) {
                is ChatResponse.TextDelta -> summary.append(response.text)
                is ChatResponse.Error, is ChatResponse.Incomplete -> failed = true
                else -> Unit
            }
        }
        return summary.toString().trim().takeIf { !failed && it.isNotBlank() }
    }

    private fun String.withAutoCompactedContext(summary: String): String =
        substringBefore("\n\n$AUTO_COMPACTION_MARKER") + "\n\n$AUTO_COMPACTION_MARKER\n$summary"

    private fun compressionPrompt(focus: String): String = """
        Compress this active coding-agent session for continuation in a fresh context window.
        Preserve concrete state, not conversational prose:
        - user goal, constraints, decisions, unresolved questions;
        - files inspected or changed, exact paths, symbols, APIs, commands, errors, test/build results;
        - current implementation state, next steps, risks, approvals, and tool outcomes;
        - facts needed to continue safely without repeating work.
        Omit greetings, repetition, and abandoned speculation. Never invent facts.
        This summary replaces prior turns. Write concise Markdown.
        ${focus.takeIf(String::isNotBlank)?.let { "Prioritize: $it" }.orEmpty()}
    """.trimIndent()

    init {
        // Watch for MCP config changes and refresh tools automatically
        repoScope.launch {
            var lastMcpJson = ""
            settingsManager.settingsFlow.collect { settings ->
                if (settings.mcpConfigJson != lastMcpJson) {
                    lastMcpJson = settings.mcpConfigJson
                    debugLog("AiRepository") { "MCP config changed, refreshing tools..." }
                    try {
                        val tools = mcpClientManager.refreshTools()
                        debugLog("AiRepository") { "MCP tools refreshed: ${tools.size} tools" }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        errorLog("AiRepository", "Failed to refresh MCP tools", e)
                    }
                }
            }
        }
    }


    private fun resolveProvider(connection: ProviderConnection): AiProvider =
        when (AmayaProviderRegistry.require(connection.providerId).adapter) {
            ProviderAdapter.ANTHROPIC -> anthropicProvider
            ProviderAdapter.GEMINI -> geminiProvider
            ProviderAdapter.OPENAI_RESPONSES, ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.CODEX -> openAiProvider
        }

    /**
     * Send a message and receive streaming responses.
     *
     * This handles the full agentic loop:
     * - Sends message to AI with project context and tools
     * - Executes tool calls as needed
     * - Returns final response
     *
     * @param message User's message
     * @param conversationHistory Previous messages in conversation
     * @param projectId Active project for context
     * @param onConfirmation Callback for tool confirmation
     * @return Flow of agent events (text, tool calls, results)
     */
    // FIX: Use channelFlow instead of flow to support concurrent emissions from subagent
    // async{} coroutines. flow{} is NOT thread-safe — concurrent emit() from different
    // coroutines (e.g. SubagentUpdate events from parallel async{}) causes:
    // "Flow invariant is violated: Emission from another coroutine is detected"
    // → IllegalStateException → FATAL EXCEPTION → app force close.
    // channelFlow uses a Channel internally which IS thread-safe for concurrent senders.
    fun chat(
        message: String,
        userImages: List<ChatImage> = emptyList(),
        conversationHistory: List<ChatMessage> = emptyList(),
        projectId: Long? = null,
        workspacePath: String? = null,
        assistantMode: AssistantMode = AssistantMode.forWorkspace(workspacePath),
        ownerId: String? = null,
        agentId: Long? = null,
        conversationId: Long? = null,
        connectionId: String? = null,
        selectedModel: String? = null,
        effort: ThinkingEffort? = null,
        runtimeTarget: AgentRuntimeTarget = AgentRuntimeTarget.LOCAL,
        onConfirmation: suspend (ConfirmationRequest) -> Boolean = { false }
    ): Flow<AgentEvent> = channelFlow {

        val settings = settingsManager.getSettings()

        val activeSelection = settings.activeSelection
        val resolvedConnectionId = connectionId ?: activeSelection?.connectionId
        val connection = settings.connections.firstOrNull { it.id == resolvedConnectionId }

        if (connection == null) {
            send(AgentEvent.Error("No model selected. Open Settings → Manage Models and select a model.", retryable = false))
            return@channelFlow
        }

        val model = selectedModel?.takeIf { it.isNotBlank() }
            ?: activeSelection?.takeIf { it.connectionId == connection.id }?.modelId
            ?: ""
        if (model.isBlank() || connection.visibleModels.none { it.id == model && it.enabled }) {
            send(AgentEvent.Error("The selected model is unavailable. Open Settings → Manage Models and select another model.", retryable = false))
            return@channelFlow
        }
        val provider = resolveProvider(connection)
        val modelConfig = connection.visibleModels.first { it.id == model }
        val maxOutputTokens = modelConfig.maxOutputTokens?.coerceIn(256, 32_768) ?: DEFAULT_MAX_OUTPUT_TOKENS
        val providerContextWindowTokens = modelConfig.contextWindowTokens?.coerceAtLeast(maxOutputTokens + 1) ?: 32_768
        val maxInputTokens = modelConfig.maxInputTokens
            ?.coerceIn(1, providerContextWindowTokens - maxOutputTokens)
            ?: providerContextWindowTokens - maxOutputTokens
        val contextWindowTokens = minOf(providerContextWindowTokens, maxInputTokens + maxOutputTokens)
        if (userImages.isNotEmpty() && !modelConfig.supportsImages) {
            send(AgentEvent.Error("The selected model does not support image input.", retryable = false))
            return@channelFlow
        }
        debugLog("AiRepository") { "chat() resolved connection=${connection.id}, model=$model" }

        val sessionId = conversationId?.toString() ?: "session_${UUID.randomUUID()}"
        val workspaceId = workspacePath?.takeIf(String::isNotBlank)?.let { workspaceMemoryStore.resolve(it)?.id }
        val completedUserMessages = mutableListOf(message)
        val completedAssistantMessages = mutableListOf<String>()
        val completedToolCalls = mutableListOf<String>()
        val completedToolResults = mutableListOf<String>()
        val viewedSkills = linkedSetOf<String>()
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            runCatching {
                sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "user", content = message, workspacePath = workspacePath, workspaceId = workspaceId, assistantMode = assistantMode.name, ownerId = ownerId))
            }.onFailure { errorLog("AiRepository", "Failed to save user session message", it) }
        }

        // Build final prompt with runtime-specific context policy.
        // Windows Bridge deliberately excludes local memory, skills, tool rules,
        // workspace hints, browser rules, and self-improvement instructions from the
        // system instruction. The model receives only the bridge-safe operating prompt
        // plus recent conversation history and bridge tool schemas.
        val agentGroup = if (assistantMode == AssistantMode.AGENT) {
            ownerId?.toLongOrNull()?.let { agentDao.getGroupById(it) }
        } else null
        if (assistantMode == AssistantMode.AGENT && agentGroup == null) {
            send(AgentEvent.Error("The active agent group no longer exists. Open AI Agents and select a group.", retryable = false))
            return@channelFlow
        }
        val activeAgent = if (assistantMode == AssistantMode.AGENT) {
            agentId?.let { agentDao.getById(it) }?.takeIf { it.groupId == agentGroup?.id }
        } else null
        if (assistantMode == AssistantMode.AGENT && activeAgent == null) {
            send(AgentEvent.Error("The selected agent is missing or does not belong to the active group. Select the agent again.", retryable = false))
            return@channelFlow
        }
        val agentCapabilityProfile = activeAgent?.capabilityProfile?.let(com.amaya.intelligence.domain.models.AgentCapabilityProfile::decode)
        val agentGroupMembers = agentGroup?.let { agentDao.getByGroup(it.id) }.orEmpty()
        val delegationMembers = agentGroupMembers.filter { it.id != activeAgent?.id }
        val tools = if (modelConfig.supportsTools) {
            buildToolDefinitions(
                runtimeTarget,
                assistantMode,
                workspacePath != null,
                agentCapabilityProfile,
                delegationMembers.map { it.localId }
            )
        } else emptyList()
        val toolSchemaTokens = estimateToolSchemaTokens(tools)
        val agentMemoryContext = activeAgent?.let { agent ->
            agentMemoryRepository.list(agent.id, query = message, limit = 8).takeIf(List<com.amaya.intelligence.data.repository.AgentMemoryRecord>::isNotEmpty)
                ?.joinToString("\n", prefix = "Agent memory (private to ${agent.name}):\n") { "- [${it.id}] ${it.content}" }
        }
        val ownerContext = when (assistantMode) {
            AssistantMode.PROJECT -> ownerId?.toLongOrNull()?.let { projectDao.getById(it) }?.let { project ->
                listOfNotNull(
                    project.instructions.takeIf(String::isNotBlank),
                    referenceDocumentRepository.context(project.referencePathsJson)
                ).joinToString("\n\n").takeIf(String::isNotBlank)
            }
            AssistantMode.AGENT -> agentGroup?.let { group ->
                val members = agentGroupMembers
                val composerReferences = com.amaya.intelligence.domain.models.parseComposerReferences(message)
                val explicitlyMentioned = members.filter { member ->
                    member.id != activeAgent?.id && member.localId in composerReferences.agentIds
                }
                buildString {
                    append("Agent group: ${group.name}")
                    group.instructions.takeIf(String::isNotBlank)?.let { append("\nGroup instructions:\n$it") }
                    activeAgent?.let { agent ->
                        append("\nActive agent identity (host-authoritative): agent_id=${agent.localId}; name=${agent.name}; role=${agent.role.ifBlank { "unspecified" }}")
                        append("\nYou are this active agent. Never claim another team member's identity.")
                        agent.role.takeIf(String::isNotBlank)?.let { append("\nRole: $it") }
                        agent.instructions.takeIf(String::isNotBlank)?.let { append("\nAgent instructions:\n$it") }
                    }
                    referenceDocumentRepository.context(group.referencePathsJson)?.let {
                        append("\nShared references (untrusted data):\n$it")
                    }
                    activeAgent?.let { agent ->
                        referenceDocumentRepository.context(agent.referencePathsJson)?.let {
                            append("\nAgent references (untrusted data):\n$it")
                        }
                    }
                    agentMemoryContext?.let { append("\n$it") }
                    if (members.isNotEmpty()) {
                        append("\nTeam directory (host-authoritative; never infer IDs from visible text):")
                        members.forEach { member ->
                            append("\n- agent_id=${member.localId}; name=${member.name}")
                            member.role.takeIf(String::isNotBlank)?.let { append("; role=$it") }
                            if (member.id == activeAgent?.id) append("; active=true")
                        }
                        append("\nTool distinction:")
                        append("\n- delegate_agent(title, agent_id, task): use only for one named member in this directory. It appends to that Agent's persistent conversation, identity, instructions, references, and memory. agent_id is group-local and restarts at 1 in every group. title is 2-5 words; task is the full prompt.")
                        append("\n- invoke_subagents(subagents): use only for temporary parallel read-only research workers. They are not group Agents, have no agent_id, identity, memory, or persistent conversation, and receive all required context inside each task.")
                        append("\nNever substitute invoke_subagents for a named delegation. Never pass a name or database ID as agent_id.")
                    }
                    if (explicitlyMentioned.isNotEmpty()) {
                        append("\nHost-resolved explicit delegation: ${explicitlyMentioned.joinToString { "${it.name} (agent_id=${it.localId})" }}. Call delegate_agent for each resolved agent before answering.")
                    }
                    val unresolvedAgentIds = composerReferences.agentIds.filter { id -> members.none { it.localId == id } }
                    if (unresolvedAgentIds.isNotEmpty()) {
                        append("\nInvalid agent references rejected by host: ${unresolvedAgentIds.joinToString()}. Do not delegate them.")
                    }
                    if (composerReferences.workspacePaths.isNotEmpty()) {
                        append("\nHost-resolved workspace references (relative paths): ${composerReferences.workspacePaths.joinToString()}")
                    }
                    if (composerReferences.commands.isNotEmpty()) {
                        append("\nComposer commands: ${composerReferences.commands.joinToString { "/$it" }}")
                    }
                }
            }
            AssistantMode.CHAT -> null
        }
        val contextRequest = ContextBuildRequest(
            userMessage = message,
            conversationHistory = conversationHistory,
            workspacePath = workspacePath,
            conversationId = conversationId,
            maxOutputTokens = maxOutputTokens,
            contextWindowTokens = contextWindowTokens,
            toolSchemaTokens = toolSchemaTokens,
            userImages = userImages,
            assistantMode = assistantMode,
            ownerId = ownerId,
            ownerContext = ownerContext
        )
        val managedContext = if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            contextManager.buildWindowsBridgeContext(contextRequest)
        } else {
            contextManager.buildContext(contextRequest)
        }
        val systemPrompt = managedContext.systemPrompt
        val allowedToolNames = tools.map { it.name }.toSet()

        // Start conversation loop
        var messages = managedContext.messages
        var autoCompactedSummary: String? = null
        var systemPromptWithAutoContext = systemPrompt

        var continueLoop = true
        var iterations = 0
        var browserTaskStarted = false
        var lastBrowserErrorSignature: String? = null
        var repeatedBrowserErrors = 0
        var terminalError = false
        var streamContinuations = 0
        val seenToolCallIds = mutableSetOf<String>()
        val invalidToolArgumentErrors = mutableMapOf<String, Throwable>()

        while (continueLoop) {
            iterations++

            // Emit NewIteration for tool results and provider-stream continuations.
            if (iterations > 1) send(AgentEvent.NewIteration)

            val requestInputBudget = minOf(maxInputTokens, contextWindowTokens - maxOutputTokens)
            var systemPromptTokens = estimatePromptTokens(systemPromptWithAutoContext)
            var historyBudget = requestInputBudget - toolSchemaTokens - CONTEXT_SAFETY_RESERVE_TOKENS - systemPromptTokens
            if (historyBudget <= 0) {
                send(AgentEvent.Error("Selected model context window is too small for its instructions and tools", retryable = false))
                terminalError = true
                break
            }
            var fittedMessages = fitMessagesToBudget(messages, historyBudget)
            val dropped = messagesOmittedByContextFit(messages, fittedMessages)
            if (dropped.isNotEmpty()) {
                val summary = summarizeAutoCompaction(
                    provider = provider,
                    connection = connection,
                    model = model,
                    previousSummary = autoCompactedSummary,
                    messages = dropped,
                    maxInputTokens = maxInputTokens
                )
                if (summary != null) {
                    autoCompactedSummary = summary
                    if (runtimeTarget == AgentRuntimeTarget.LOCAL) runCatching {
                        sessionMemoryRepository.saveSummary(
                            SessionSummary(
                                sessionId = sessionId,
                                summary = summary,
                                tags = listOf("auto_compacted"),
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                workspacePath = workspacePath,
                                workspaceId = workspaceId,
                                assistantMode = assistantMode.name,
                                ownerId = ownerId
                            )
                        )
                    }.onFailure { errorLog("AiRepository", "Failed to persist auto-compacted context", it) }
                    systemPromptWithAutoContext = systemPrompt.withAutoCompactedContext(summary)
                    systemPromptTokens = estimatePromptTokens(systemPromptWithAutoContext)
                    historyBudget = requestInputBudget - toolSchemaTokens - CONTEXT_SAFETY_RESERVE_TOKENS - systemPromptTokens
                    messages = fittedMessages
                    fittedMessages = fitMessagesToBudget(messages, historyBudget)
                }
            }
            if (messages.isNotEmpty() && fittedMessages.isEmpty()) {
                send(AgentEvent.Error("The latest user input or required tool-call metadata exceeds the selected model context window", retryable = false))
                terminalError = true
                break
            }
            debugLog("AiRepository") {
                "context iteration=$iterations window=$contextWindowTokens input=$requestInputBudget system=$systemPromptTokens tools=$toolSchemaTokens history=${estimateMessageTokens(fittedMessages)} output=$maxOutputTokens auto_compacted=${autoCompactedSummary != null}"
            }
            messages = fittedMessages
            if (!hasProviderUserQuery(messages)) {
                send(AgentEvent.Error("No user query remains in the request context", retryable = false))
                terminalError = true
                break
            }
            val request = ChatRequest(
                model        = model,
                messages     = messages,
                systemPrompt = systemPromptWithAutoContext,
                tools        = tools,
                maxTokens    = maxOutputTokens,
                stream       = true,
                connectionId = connection.id,
                sessionId    = sessionId,
                providerId   = connection.providerId,
                effort       = effort
            )

            var textBuffer = StringBuilder()
            val toolCalls = mutableListOf<ToolCallMessage>()
            val responseItems = mutableListOf<String>()
            var hasToolCalls = false
            var providerTerminal = false
            var retryableFailure: String? = null

            provider.chat(request).collect { response ->
                if (providerTerminal) {
                    send(AgentEvent.Error("Provider emitted an event after its terminal event", retryable = false))
                    terminalError = true
                    continueLoop = false
                    return@collect
                }
                when (response) {
                    is ChatResponse.TextDelta -> {
                        textBuffer.append(response.text)
                        send(AgentEvent.TextDelta(response.text))
                    }

                    is ChatResponse.ThinkingDelta -> {
                        send(AgentEvent.ThinkingDelta(response.text))
                    }

                    is ChatResponse.ToolCall -> {
                        if (response.id.isBlank() || response.name !in allowedToolNames || !seenToolCallIds.add(response.id)) {
                            send(AgentEvent.Error("Invalid, duplicate, or unadvertised tool call: ${response.name}", retryable = false))
                            terminalError = true
                            continueLoop = false
                            return@collect
                        }
                        val validation = validateToolArguments(response.name, response.arguments, tools)
                        val toolArguments = validation.getOrElse { error ->
                            invalidToolArgumentErrors[response.id] = error
                            response.arguments
                        }
                        hasToolCalls = true
                        send(AgentEvent.ToolCallStart(response.id, response.name, toolArguments, response.metadata))

                        toolCalls.add(ToolCallMessage(
                            id = response.id,
                            name = response.name,
                            arguments = toolArguments,
                            metadata = response.metadata
                        ))
                    }

                    is ChatResponse.ResponseItem -> {
                        responseItems.add(response.json)
                        send(AgentEvent.ResponseItem(response.json))
                    }

                    is ChatResponse.Done -> {
                        providerTerminal = true
                        response.usage?.let { usage ->
                            send(AgentEvent.Usage(usage.inputTokens, usage.outputTokens))
                        }
                    }

                    is ChatResponse.Incomplete -> {
                        providerTerminal = true
                        retryableFailure = response.reason.takeIf { canContinueStream(response, hasToolCalls) }
                        if (retryableFailure == null && !shouldExecuteReceivedToolCalls(response, hasToolCalls)) {
                            send(AgentEvent.Incomplete(response.reason, response.retryable))
                            terminalError = true
                            continueLoop = false
                        }
                    }

                    is ChatResponse.Error -> {
                        providerTerminal = true
                        retryableFailure = response.message.takeIf { canContinueStream(response, hasToolCalls) }
                        if (retryableFailure == null && !shouldExecuteReceivedToolCalls(response, hasToolCalls)) {
                            send(AgentEvent.Error(response.message, response.retryable))
                            terminalError = true
                            continueLoop = false
                        }
                    }
                }
            }

            if (terminalError) break
            if (!providerTerminal && !hasToolCalls) retryableFailure = "Provider stream ended without a terminal event"

            if (retryableFailure != null) {
                if (streamContinuations == MAX_STREAM_CONTINUATIONS) {
                    send(AgentEvent.Incomplete(retryableFailure!!, retryable = true))
                    terminalError = true
                    break
                }
                streamContinuations++
                if (textBuffer.isNotBlank()) {
                    messages = messages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = textBuffer.toString()
                    ) + ChatMessage(role = MessageRole.USER, content = STREAM_CONTINUATION_PROMPT)
                }
                delay(1_000L shl (streamContinuations - 1))
                continue
            }

            if (textBuffer.isNotBlank()) {
                val assistantText = textBuffer.toString()
                completedAssistantMessages.add(assistantText)
                if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                    runCatching {
                        sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "assistant", content = assistantText, workspacePath = workspacePath, workspaceId = workspaceId, assistantMode = assistantMode.name, ownerId = ownerId))
                    }.onFailure { errorLog("AiRepository", "Failed to save assistant session message", it) }
                }
            }

            if (!hasToolCalls) {
                // No tool calls, we're done
                continueLoop = false
            } else {
                // Add assistant message with tool calls
                messages = messages + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = textBuffer.toString().takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls,
                    responseItems = responseItems
                )

                // Execute each tool call
                for (toolCall in toolCalls) {
                    val channel = this
                    val executionArguments = if (toolCall.name == "browser") {
                        val shouldResetBrowserTask = !browserTaskStarted
                        browserTaskStarted = true
                        if (shouldResetBrowserTask && toolCall.arguments["reset_task"] == null) {
                            toolCall.arguments + ("reset_task" to true)
                        } else toolCall.arguments
                    } else {
                        toolCall.arguments
                    }

                    completedToolCalls.add("${toolCall.name}: ${toolCall.arguments}")
                    val result = invalidToolArgumentErrors.remove(toolCall.id)?.let { error ->
                        ToolResult.Error(
                            message = "Invalid arguments for ${toolCall.name}: ${error.message.orEmpty()}",
                            errorType = com.amaya.intelligence.tools.ErrorType.VALIDATION_ERROR
                        )
                    } ?: if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE && toolCall.name !in allowedToolNames) {
                        ToolResult.Error(
                            message = "Tool '${toolCall.name}' is not available in Windows Bridge chat.",
                            errorType = com.amaya.intelligence.tools.ErrorType.PERMISSION_ERROR,
                            recoverable = false
                        )
                    } else {
                        mcpToolExecutor.execute(
                            toolName = toolCall.name,
                            arguments = executionArguments,
                            workspacePath = workspacePath,
                            toolCallId = toolCall.id,
                            // FIX: Use channel.send() — channelFlow's ProducerScope is thread-safe,
                            // unlike flow{}'s emit() which panics on concurrent coroutine access.
                            onEvent = { event -> if (event is AgentEvent) channel.send(event) },
                            onConfirmationRequired = onConfirmation,
                            providerConnection = connection,
                            selectedModelId = model,
                            conversationId = sessionId,
                            ownerId = ownerId,
                            agentId = activeAgent?.id,
                            assistantMode = assistantMode,
                            agentCapabilityProfile = agentCapabilityProfile
                        )
                    }

                    val rawResultContent = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                        is ToolResult.RequiresConfirmation -> "Error: Approval could not be completed: ${result.reason}"
                    }
                    var resultContent = rawResultContent
                    if (toolCall.name == "browser") {
                        val signature = browserErrorSignature(resultContent)
                        if (signature != null) {
                            repeatedBrowserErrors = if (signature == lastBrowserErrorSignature) repeatedBrowserErrors + 1 else 1
                            lastBrowserErrorSignature = signature
                            repeatedBrowserFailureWarning(signature, repeatedBrowserErrors)?.let { warning ->
                                resultContent += "\n\n$warning"
                            }
                        } else {
                            lastBrowserErrorSignature = null
                            repeatedBrowserErrors = 0
                        }
                    }

                    completedToolResults.add("${toolCall.name}: $resultContent")
                    if (result is ToolResult.Success && toolCall.name == "skill" && toolCall.arguments["operation"] == "view") {
                        ((toolCall.arguments["skill_id"] ?: toolCall.arguments["name"]) as? String)
                            ?.takeIf(String::isNotBlank)
                            ?.let(viewedSkills::add)
                    }
                    if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                        runCatching {
                            sessionMemoryRepository.saveToolCall(
                                SessionToolCall(
                                    sessionId = sessionId,
                                    toolCallId = toolCall.id,
                                    toolName = toolCall.name,
                                    argumentsJson = JSONObject(toolCall.arguments).toString(),
                                    resultJson = resultContent,
                                    workspacePath = workspacePath,
                                    workspaceId = workspaceId,
                                    assistantMode = assistantMode.name,
                                    ownerId = ownerId
                                )
                            )
                        }.onFailure { errorLog("AiRepository", "Failed to save session tool call", it) }
                    }

                    send(AgentEvent.ToolCallResult(
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        result = resultContent,
                        isError = result !is ToolResult.Success
                    ))

                    // Add tool result to conversation
                    // Store both ID (for OpenAI) and name (for Gemini) in metadata
                    val resultMetadata = toolCall.metadata.toMutableMap()
                    resultMetadata["toolName"] = toolCall.name  // Gemini needs the function name
                    // Propagate image metadata from ToolResult.Success so providers can
                    // attach the screenshot as a vision content block instead of embedding
                    // the raw base64 string inside the tool-result text.
                    if (result is ToolResult.Success) {
                        (result.metadata["bridge_image_base64"] as? String)?.let { resultMetadata["bridge_image_base64"] = it }
                        (result.metadata["bridge_image_format"] as? String)?.let { resultMetadata["bridge_image_format"] = it }
                    }

                    messages = messages + ChatMessage(
                        role = MessageRole.TOOL,
                        toolResult = ToolResultMessage(
                            toolCallId = toolCall.id, // OpenAI requires original tool_call_id
                            content = resultContent,
                            isError = result !is ToolResult.Success,
                            metadata = resultMetadata
                        )
                    )

                }
            }
        }

        val reflectionContext = CompletedInteractionContext(
            sessionId = sessionId,
            userMessages = completedUserMessages.toList(),
            assistantMessages = completedAssistantMessages.toList(),
            toolCalls = completedToolCalls.toList(),
            toolResults = completedToolResults.toList(),
            timestamp = System.currentTimeMillis(),
            workspacePath = workspacePath,
            workspaceId = workspaceId,
            successful = !terminalError
        )

        if (viewedSkills.isNotEmpty()) {
            viewedSkills.forEach { skillName ->
                runCatching { skillRepository.recordSkillUsage(skillName, success = !terminalError) }
                    .onFailure { errorLog("AiRepository", "Failed to record skill outcome", it) }
            }
        }
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            repoScope.launch {
                runCatching { selfImprovementPipeline.analyzeAndImprove(reflectionContext) }
                    .onFailure { errorLog("AiRepository", "Post-chat reflection failed", it) }
            }
        }
        if (terminalError) return@channelFlow

        send(AgentEvent.Done)
    }

    private fun validateToolArguments(
        name: String,
        arguments: Map<String, Any?>,
        tools: List<AiToolDefinition>
    ): Result<Map<String, Any?>> = runCatching {
        val definition = tools.firstOrNull { it.name == name } ?: error("Tool was not advertised")
        definition.rawParametersJson?.let { schema ->
            @Suppress("UNCHECKED_CAST")
            val normalized = normalizeJsonSchemaValue(JSONObject(schema), arguments) as Map<String, Any?>
            validateJsonSchema(JSONObject(schema), normalized, "arguments")
            return@runCatching normalized
        }
        val normalized = arguments.toMutableMap()
        definition.parameters.properties.forEach { (key, property) ->
            if (property.type.equals("integer", ignoreCase = true) && key in normalized) {
                normalized[key] = normalizeIntegerArgument(normalized[key])
            }
        }
        val missing = definition.parameters.required.filter { it !in normalized || normalized[it] == null }
        require(missing.isEmpty()) { "Missing required properties: ${missing.joinToString()}" }
        if (!definition.parameters.additionalProperties) {
            val unknown = normalized.keys - definition.parameters.properties.keys
            require(unknown.isEmpty()) { "Unknown properties: ${unknown.joinToString()}" }
        }
        definition.parameters.properties.forEach { (key, property) ->
            val value = normalized[key] ?: return@forEach
            val validType = when (property.type.lowercase()) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "array" -> value is List<*>
                "object" -> value is Map<*, *>
                else -> true
            }
            require(validType) { "$key must be ${property.type}" }
            property.enum?.let { allowed -> require(value.toString() in allowed) { "$key is not an allowed value" } }
        }
        normalized
    }

    private fun normalizeJsonSchemaValue(schema: JSONObject, value: Any?): Any? {
        val types = buildList {
            schema.optString("type").takeIf { it.isNotBlank() }?.let(::add)
            schema.optJSONArray("type")?.let { array ->
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }
        val scalar = if (types.any { it.equals("integer", true) } && types.none { it.equals("string", true) }) {
            normalizeIntegerArgument(value)
        } else value
        return when (scalar) {
            is Map<*, *> -> {
                val properties = schema.optJSONObject("properties") ?: return scalar
                scalar.entries.associate { (key, child) ->
                    val name = key.toString()
                    name to (properties.optJSONObject(name)?.let { normalizeJsonSchemaValue(it, child) } ?: child)
                }
            }
            is List<*> -> schema.optJSONObject("items")?.let { itemSchema ->
                scalar.map { normalizeJsonSchemaValue(itemSchema, it) }
            } ?: scalar
            else -> scalar
        }
    }

    private fun validateJsonSchema(schema: JSONObject, value: Any?, path: String) {
        val types = buildList {
            schema.optString("type").takeIf { it.isNotBlank() }?.let(::add)
            schema.optJSONArray("type")?.let { array ->
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }
        if (value == null) {
            require("null" in types || schema.optBoolean("nullable")) { "$path cannot be null" }
            return
        }
        val matches = types.isEmpty() || types.any { type ->
            when (type.lowercase()) {
                "object" -> value is Map<*, *>
                "array" -> value is List<*>
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "null" -> false
                else -> true
            }
        }
        require(matches) { "$path must be ${types.joinToString(" or ")}" }
        schema.optJSONArray("enum")?.let { allowed ->
            require((0 until allowed.length()).any { allowed.opt(it) == value }) { "$path is not an allowed value" }
        }
        when (value) {
            is Map<*, *> -> {
                val properties = schema.optJSONObject("properties") ?: JSONObject()
                val required = schema.optJSONArray("required")
                if (required != null) for (index in 0 until required.length()) {
                    val key = required.optString(index)
                    require(value.containsKey(key) && value[key] != null) { "Missing required property: $path.$key" }
                }
                if (schema.has("additionalProperties") && schema.opt("additionalProperties") == false) {
                    require(value.keys.all { properties.has(it.toString()) }) { "Unknown properties in $path" }
                }
                value.forEach { (key, child) ->
                    properties.optJSONObject(key.toString())?.let { validateJsonSchema(it, child, "$path.$key") }
                }
            }
            is List<*> -> schema.optJSONObject("items")?.let { itemSchema ->
                value.forEachIndexed { index, child -> validateJsonSchema(itemSchema, child, "$path[$index]") }
            }
        }
    }

    private fun estimateToolSchemaTokens(tools: List<AiToolDefinition>): Int =
        tools.sumOf { tool ->
            val schema = tool.rawParametersJson ?: JSONObject()
                .put("type", tool.parameters.type)
                .put("properties", JSONObject(tool.parameters.properties))
                .put("required", org.json.JSONArray(tool.parameters.required))
                .toString()
            (tool.name.length + tool.description.length + schema.length + 3) / 4
        }

    private fun estimatePromptTokens(text: String): Int {
        if (text.isBlank()) return 0
        return maxOf((text.length / 4.0).toInt(), (text.split(Regex("\\s+")).size * 1.3).toInt(), 1)
    }

    private fun estimateMessageTokens(messages: List<ChatMessage>): Int = messages.sumOf(::messageTokenCost)

    private fun messageTokenCost(message: ChatMessage): Int = (
        message.content.orEmpty().length +
            message.toolResult?.content.orEmpty().length +
            message.toolCalls.orEmpty().sumOf { it.arguments.toString().length } +
            message.responseItems.sumOf(String::length)
        ) / 4 + 8

    internal fun fitMessagesToBudget(messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        if (maxTokens <= 0) return emptyList()
        val boundedMessages = compactToolResultsToBudget(messages, maxTokens)
        val latestUserIndex = boundedMessages.indexOfLast { message ->
            message.role == MessageRole.USER && (!message.content.isNullOrBlank() || message.images.isNotEmpty())
        }
        if (latestUserIndex < 0) return emptyList()
        val latestUser = boundedMessages[latestUserIndex]
        val userCost = messageTokenCost(latestUser)
        if (userCost > maxTokens) return emptyList()
        val trailing = boundedMessages.drop(latestUserIndex + 1)
        var used = 0
        var cut = trailing.size
        while (cut > 0) {
            val spanStart = if (trailing[cut - 1].role == MessageRole.TOOL) {
                (cut - 2 downTo 0).firstOrNull { trailing[it].role == MessageRole.ASSISTANT } ?: cut - 1
            } else cut - 1
            val spanCost = trailing.subList(spanStart, cut).sumOf(::messageTokenCost)
            if (userCost + used + spanCost > maxTokens) {
                if (cut == trailing.size) return emptyList()
                break
            }
            used += spanCost
            cut = spanStart
        }
        return listOf(latestUser) + trailing.drop(cut)
    }

    private fun compactToolResultsToBudget(messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        val latestUserIndex = messages.indexOfLast { message ->
            message.role == MessageRole.USER && (!message.content.isNullOrBlank() || message.images.isNotEmpty())
        }
        if (latestUserIndex < 0) return messages
        val activeTurn = messages.subList(latestUserIndex, messages.size)
        if (estimateMessageTokens(activeTurn) <= maxTokens) return messages
        val toolIndexes = (latestUserIndex until messages.size).filter {
            messages[it].role == MessageRole.TOOL && messages[it].toolResult != null
        }
        if (toolIndexes.isEmpty()) return messages
        val fixedCost = activeTurn.filter { it.role != MessageRole.TOOL }.sumOf(::messageTokenCost) + toolIndexes.size * 8
        val perResultChars = ((maxTokens - fixedCost).coerceAtLeast(0) * 4 / toolIndexes.size)
        if (perResultChars <= 0) return messages
        return messages.mapIndexed { index, message ->
            if (index !in toolIndexes) message else message.copy(toolResult = message.toolResult?.let { result ->
                if (result.content.length <= perResultChars) result else result.copy(
                    content = truncateToolResultForContext(result.content, perResultChars)
                )
            })
        }
    }

    private fun browserErrorSignature(resultContent: String): String? {
        val root = runCatching { JSONObject(resultContent) }.getOrNull() ?: return null
        val status = root.optString("status")
        if (status != "error" && status != "cancelled" && status != "timeout") return null
        val error = root.optJSONObject("agent")?.optJSONObject("error") ?: root.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: status
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: root.optJSONObject("agent")?.optString("latest_summary").orEmpty()
        return "$code:${message.take(120)}"
    }



    /**
     * Build tool definitions for AI.
     * Uses cached MCP tools — refresh happens automatically via settingsFlow watcher in init.
     */
    private fun buildToolDefinitions(
        runtimeTarget: AgentRuntimeTarget = AgentRuntimeTarget.LOCAL,
        assistantMode: AssistantMode = AssistantMode.CHAT,
        hasWorkspace: Boolean = false,
        agentCapabilityProfile: com.amaya.intelligence.domain.models.AgentCapabilityProfile? = null,
        delegationAgentIds: List<Long> = emptyList()
    ): List<AiToolDefinition> {
        val bridgeTools = windowsBridgeToolProvider.getAvailableBridgeTools()
            .map { it.toAiToolDefinition(truncateDesc = true) }
        if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            if (bridgeTools.isNotEmpty()) {
                debugLog("AiRepository") { "Building Windows Bridge tool defs: bridge=${bridgeTools.size}" }
            }
            return bridgeTools
        }

        // FIX 4.3: Use shared toAiToolDefinition() extension (ToolExecutor.kt) — removes duplicate mapping
        val localTools = toolExecutor.getToolDefinitions(assistantMode, agentCapabilityProfile, delegationAgentIds)
            .filterNot { !hasWorkspace && it.name in setOf("workspace_search", "workspace_change", "read_file", "run_shell", "invoke_subagents") }
            .map { it.toAiToolDefinition(truncateDesc = true) }
        // MCP tools come from external servers — truncate their descriptions too
        val mcpTools = mcpClientManager.getCachedToolDefinitions().map { tool ->
            tool.copy(
                description = tool.description.let { if (it.length > 1023) it.take(1023) + "…" else it },
                parameters = tool.parameters.copy(
                    properties = tool.parameters.properties.mapValues { (_, prop) ->
                        prop.copy(description = prop.description.let { if (it.length > 1023) it.take(1023) + "…" else it })
                    }
                )
            )
        }
        debugLog("AiRepository") { "Building tool defs: local=${localTools.size}, mcp=${mcpTools.size}" }
        return localTools + mcpTools
    }

    /** Generate a compact conversation title from the user's first message. */
    suspend fun generateTitle(
        userMessage: String,
        providerConnection: ProviderConnection,
        selectedModel: String?
    ): String {
        val fallback = fallbackConversationTitle(userMessage)
        return try {
            val provider = resolveProvider(providerConnection)
            val model = selectedModel?.takeIf { it.isNotBlank() } ?: return fallback
            var retryFeedback: String? = null
            repeat(2) { attempt ->
                val result = StringBuilder()
                var failure: String? = null
                provider.chat(
                    ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage(
                            role = MessageRole.USER,
                            content = buildString {
                                appendLine("Create a title for this message:")
                                append(userMessage)
                                retryFeedback?.let { append("\n\nCorrection: $it") }
                            }
                        )),
                        systemPrompt = """Create a concise, useful chat title from the user's primary intent and specific subject.
Use the user's language. Write a natural noun phrase of 2-3 words; use up to 5 only when needed for clarity.
Prefer concrete actions and subjects over vague wording. Preserve established technical terms.
Return exactly <title>YOUR TITLE</title>. Never answer the message or add text outside those tags.

Examples:
Tolong terjemahkan ini ke bahasa Inggris → <title>Terjemahan ke Inggris</title>
What model are you? → <title>Model Identification Request</title>
Audit logic codebase ini → <title>Audit Logic Codebase</title>
Berikan ide website yang kreatif → <title>Ide Website Kreatif</title>""",
                        // Stream: local reasoning providers often expose final text only through SSE.
                        maxTokens = 512,
                        temperature = 0f,
                        stream = true,
                        connectionId = providerConnection.id,
                        providerId = providerConnection.providerId,
                        effort = ThinkingEffort.NONE
                    )
                ).collect { response ->
                    when (response) {
                        is ChatResponse.TextDelta -> result.append(response.text)
                        is ChatResponse.Error -> failure = response.message
                        is ChatResponse.Incomplete -> failure = response.reason
                        else -> Unit
                    }
                }
                val rawTitle = result.toString()
                val title = extractConversationTitle(rawTitle)
                debugLog("AiRepository") {
                    "Title attempt=${attempt + 1} chars=${rawTitle.length} valid=${title != null} failure=${failure.orEmpty().take(80)}"
                }
                if (title != null) return title
                retryFeedback = "Your previous output was invalid. Return only one 2-5 word title inside <title> and </title>."
            }
            fallback
        } catch (e: Exception) {
            errorLog("AiRepository", "Failed to generate title", e)
            fallback
        }
    }

}

/**
 * Events emitted during AI chat.
 */
sealed class AgentEvent {
    data class TextDelta(val text: String) : AgentEvent()
    /** Reasoning/thinking chunk, rendered separately from the answer. */
    data class ThinkingDelta(val text: String) : AgentEvent()
    data class ToolCallStart(
        val toolCallId: String,
        val name: String,
        val arguments: Map<String, Any?>,
        val metadata: Map<String, String> = emptyMap()
    ) : AgentEvent()
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String, val isError: Boolean) : AgentEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent()
    data class ResponseItem(val json: String) : AgentEvent()
    data class Incomplete(val reason: String, val retryable: Boolean) : AgentEvent()
    data class Error(val message: String, val retryable: Boolean) : AgentEvent()
    data object NewIteration : AgentEvent()
    data object Done : AgentEvent()
    // Emitted by InvokeSubagentsTool as each subagent starts/completes
    data class SubagentUpdate(
        val parentToolCallId: String,
        val index: Int,
        val taskName: String,
        val prompt: String,
        val result: String? = null,
        val isComplete: Boolean = false,
        val isError: Boolean = false
    ) : AgentEvent()
}

// FIX 1.4: ProviderInfo fully removed — was only used by deleted getProviders() function.
