package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.repository.AiRepository
import com.amaya.intelligence.data.repository.AgentEvent

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.utils.LocalStreamPerfLog
import com.amaya.intelligence.tools.ConfirmationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local implementation of IntelligenceService.
 * Wraps AiRepository and handles persistence via ConversationDao.
 */
@Singleton
class LocalIntelligenceService @Inject constructor(
    private val aiRepository: AiRepository,
    private val conversationDao: ConversationDao,
    private val settingsManager: AiSettingsManager,
    private val browserSessionManager: BrowserSessionManager,
    @ApplicationScope appScope: CoroutineScope
) : IntelligenceService {

    // Confine mutable chat/UI state to the main thread while retaining process lifetime.
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(ChatUiState(
        sessionMode = IntelligenceSessionManager.SessionMode.LOCAL
    ))
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    private val _workspaces = MutableStateFlow<List<RemoteWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<RemoteWorkspace>> = _workspaces.asStateFlow()

    private var chatJob: Job? = null
    private var currentConversationId: Long? = null
    private var currentAssistantMessageId: String? = null
    private val assistantTextBuffer = StringBuilder()
    private val assistantThinkingBuffer = StringBuilder()
    private var lastAssistantTextUiEmitAt = 0L
    private var assistantFlushJob: Job? = null
    private var thinkingFlushJob: Job? = null
    private var browserConversationKey: String? = null
    private val conversationSaveMutex = Mutex()
    private val pendingToolConfirmations = ToolConfirmationRegistry()
    private val pendingConfirmationUi = ConcurrentHashMap<String, ConfirmationRequest>()
    private val pendingApprovalIds = ConcurrentHashMap<String, String>()
    private val titleJobs = ConcurrentHashMap<Long, Job>()
    private val activeTurnId = AtomicLong(0L)

    init {


        // Observe conversations from DB
        scope.launch {
            conversationDao.getAllConversations().collect { list ->
                _conversations.value = list
            }
        }
        scope.launch {
            settingsManager.settingsFlow.collect { settings ->
                val options = settings.connections.flatMap { connection ->
                    connection.visibleModels.map { model ->
                        ModelUiMapper.mapConnectionModel(connection, model)
                    }
                }
            // Load persisted effort when active model changes.
            val persistedEffort = settings.activeSelection?.let { sel ->
                settingsManager.getThinkingEffort(sel.connectionId, sel.modelId)
            } ?: com.amaya.intelligence.data.remote.api.ThinkingEffort.MEDIUM
            _uiState.update {
                it.copy(
                    modelOptions = options,
                    activeModelKey = settings.activeSelection?.key.orEmpty(),
                    selectedModel = settings.activeSelection?.modelId.orEmpty(),
                    effort = persistedEffort
                )
            }
            }
        }
    }

    override fun sendMessage(content: String) = sendMessageInternal(content, emptyList())

    override fun sendMessageWithImage(content: String, imageBase64: String, mimeType: String, fileName: String) {
        if (!mimeType.startsWith("image/") || imageBase64.isBlank() || imageBase64.length > 1_000_000) {
            _uiState.update { it.copy(error = "Invalid or oversized image attachment") }
            return
        }
        sendMessageInternal(
            content,
            listOf(com.amaya.intelligence.data.remote.api.ChatImage(imageBase64, mimeType, fileName))
        )
    }

    private fun sendMessageInternal(
        content: String,
        images: List<com.amaya.intelligence.data.remote.api.ChatImage>
    ) {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank() && images.isEmpty()) return
        val turnId = activeTurnId.incrementAndGet()
        val interruptedTurn = chatJob?.isActive == true
        chatJob?.cancel()
        chatJob = null
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
        pendingToolConfirmations.cancelAll()
        pendingConfirmationUi.clear()
        pendingApprovalIds.clear()
        if (interruptedTurn) {
            flushAssistantThinkingBuffer()
            flushAssistantTextBuffer()
            markActiveToolsStopped()
            markCurrentAssistantTerminal("cancelled")
            saveCurrentConversation()
        }
        currentAssistantMessageId = null
        assistantTextBuffer.clear()
        assistantThinkingBuffer.clear()
        lastAssistantTextUiEmitAt = 0L

        val currentState = _uiState.value
        LocalStreamPerfLog.startTurn(
            messageChars = trimmedContent.length,
            historyMessages = currentState.messages.size,
            model = currentState.selectedModel.ifBlank { currentState.activeModelKey }
        )
        val userMsg = UiMessage(
            role = MessageRole.USER,
            content = trimmedContent,
            attachments = images.map { MessageAttachment(it.mediaType, it.base64, it.fileName) }
        )
        ensureBrowserConversationSession(userMsg.id)
        browserSessionManager.onAssistantStreamingChanged(true)

        // Optimistic update
        _uiState.update { it.copy(
            messages = it.messages + userMsg,
            isLoading = true,
            isStreaming = true
        )}

        chatJob = scope.launch {
            try {
                // Check before persistence: the first turn alone receives an AI title.
                val isNewConversation = currentConversationId == null
                val conversationIdForTurn = persistCurrentConversation()
                    ?: throw IllegalStateException("Could not save this conversation. Message was not sent.")

                val history = _uiState.value.messages.flatMap { it.toChatMessages() }
                var turnCompleted = false

                aiRepository.chat(
                    message = trimmedContent,
                    userImages = images,
                    conversationHistory = history.dropLast(1), // Exclude the one we just added
                    workspacePath = currentState.workspacePath,
                    connectionId = currentState.activeModelKey
                        .takeIf { it.startsWith("model|") }
                        ?.split('|', limit = 3)
                        ?.getOrNull(1),
                    conversationId = conversationIdForTurn,
                    selectedModel = currentState.selectedModel,
                    effort = currentState.effort,
                    onConfirmation = { request -> awaitInlineToolConfirmation(request, turnId) }
                ).collect { event ->
                    if (activeTurnId.get() == turnId) {
                        if (event is AgentEvent.Done) turnCompleted = true
                        handleAgentEvent(event)
                    }
                }
                // Local model servers commonly allow one generation at a time. Start the
                // auxiliary title request only after the visible answer has released it.
                if (isNewConversation && turnCompleted && activeTurnId.get() == turnId) {
                    launchTitleGeneration(trimmedContent, conversationIdForTurn)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeTurnId.get() != turnId) return@launch
                chatJob = null
                flushAssistantTextBuffer()
                browserSessionManager.onAssistantStreamingChanged(false)
                _uiState.update { it.copy(error = e.message, isLoading = false, isStreaming = false) }
                LocalStreamPerfLog.endTurn("exception:${e.message.orEmpty().take(80)}", _uiState.value.messages.size, currentAssistantTextLength())
            }
        }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                browserSessionManager.onAssistantTextDelta(event.text)
                bufferAssistantTextDelta(event.text)
            }
            is AgentEvent.ThinkingDelta -> bufferAssistantThinkingDelta(event.text)
            is AgentEvent.ToolCallStart -> {
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                flushAssistantTextBuffer()
                val normalizedName = LocalToolMapper.mapToolName(event.name)
                val normalizedArgs = LocalToolMapper.mapToolArgs(event.name, event.arguments)
                val pendingApproval = pendingConfirmationUi[event.toolCallId]
                val approvalId = pendingApprovalIds[event.toolCallId]
                val canonicalCall = JSONObject()
                    .put("kind", "assistant_tool_call")
                    .put("id", event.toolCallId)
                    .put("name", event.name)
                    .put("arguments", JSONObject(event.arguments))
                    .put("metadata", JSONObject(event.metadata))
                    .toString()
                val toolExec = ToolExecution(
                    toolCallId = event.toolCallId,
                    name = normalizedName,
                    arguments = normalizedArgs,
                    status = if (pendingApproval == null) ToolStatus.RUNNING else ToolStatus.PENDING,
                    metadata = buildMap {
                        put("source", "local")
                        put("animateOnMount", "true")
                        putAll(event.metadata)
                        pendingApproval?.let { putAll(approvalMetadata(it, approvalId.orEmpty())) }
                    },
                    uiMetadata = LocalToolMapper.getUiMetadata(event.name, event.arguments)
                )
                ensureAssistantMessage()
                updateCurrentAssistantMessage { msg ->
                    msg.copy(
                        toolExecutions = msg.toolExecutions + toolExec,
                        steps = msg.steps + MessageStep.ToolCall(execution = toolExec),
                        canonicalHistory = msg.canonicalHistory + canonicalCall
                    )
                }
            }
            is AgentEvent.ToolCallResult -> {
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                flushAssistantTextBuffer()
                val canonicalResult = JSONObject()
                    .put("kind", "tool_result")
                    .put("id", event.toolCallId)
                    .put("name", event.toolName)
                    .put("result", event.result)
                    .put("isError", event.isError)
                    .toString()
                updateCurrentAssistantMessage { msg ->
                    val updatedTools = msg.toolExecutions.map {
                        if (it.toolCallId == event.toolCallId) {
                            it.copy(
                                result = event.result,
                                status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS
                            )
                        } else it
                    }
                    val updatedSteps = msg.steps.map { step ->
                        if (step is MessageStep.ToolCall && step.execution.toolCallId == event.toolCallId) {
                            step.copy(
                                execution = step.execution.copy(
                                    result = event.result,
                                    status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS
                                )
                            )
                        } else step
                    }
                    msg.copy(
                        toolExecutions = updatedTools,
                        steps = updatedSteps,
                        canonicalHistory = msg.canonicalHistory + canonicalResult
                    )
                }
            }
            is AgentEvent.ResponseItem -> {
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                ensureAssistantMessage()
                updateCurrentAssistantMessage { message ->
                    if (event.json in message.responseItems) message
                    else message.copy(
                        responseItems = message.responseItems + event.json,
                        canonicalHistory = message.canonicalHistory + JSONObject()
                            .put("kind", "response_item")
                            .put("item", JSONObject(event.json))
                            .toString()
                    )
                }
            }
            is AgentEvent.Usage -> {
                _uiState.update {
                    it.copy(
                        totalInputTokens = it.totalInputTokens + event.inputTokens,
                        totalOutputTokens = it.totalOutputTokens + event.outputTokens
                    )
                }
            }
            is AgentEvent.Incomplete -> {
                chatJob = null
                flushAssistantThinkingBuffer()
                flushAssistantTextBuffer()
                markActiveToolsStopped()
                markCurrentAssistantTerminal("incomplete")
                browserSessionManager.onAssistantStreamingChanged(false)
                _uiState.update { it.copy(error = event.reason, isLoading = false, isStreaming = false) }
                LocalStreamPerfLog.endTurn("incomplete:${event.reason.take(80)}", _uiState.value.messages.size, currentAssistantTextLength())
                saveCurrentConversation()
            }
            is AgentEvent.Error -> {
                chatJob = null
                flushAssistantThinkingBuffer()
                flushAssistantTextBuffer()
                markActiveToolsStopped()
                markCurrentAssistantTerminal("failed")
                browserSessionManager.onAssistantStreamingChanged(false)
                _uiState.update { it.copy(error = event.message, isLoading = false, isStreaming = false) }
                LocalStreamPerfLog.endTurn("error:${event.message.take(80)}", _uiState.value.messages.size, currentAssistantTextLength())
                saveCurrentConversation()
            }
            is AgentEvent.Done -> {
                chatJob = null
                flushAssistantThinkingBuffer()
                flushAssistantTextBuffer()
                markCurrentAssistantCompleted()
                browserSessionManager.onAssistantStreamingChanged(false)
                _uiState.update { it.copy(isLoading = false, isStreaming = false) }
                LocalStreamPerfLog.endTurn("done", _uiState.value.messages.size, currentAssistantTextLength())
                saveCurrentConversation()
            }
            is AgentEvent.SubagentUpdate -> {
                updateCurrentAssistantMessage { msg ->
                    fun updateExecution(tool: ToolExecution): ToolExecution {
                        if (tool.toolCallId != event.parentToolCallId) return tool
                        val child = SubagentExecution(
                            index = event.index,
                            taskName = event.taskName,
                            prompt = event.prompt,
                            result = event.result,
                            status = when {
                                !event.isComplete -> ToolStatus.RUNNING
                                event.isError -> ToolStatus.ERROR
                                else -> ToolStatus.SUCCESS
                            }
                        )
                        return tool.copy(children = (tool.children.filterNot { it.index == event.index } + child).sortedBy { it.index })
                    }
                    msg.copy(
                        toolExecutions = msg.toolExecutions.map(::updateExecution),
                        steps = msg.steps.map { step ->
                            if (step is MessageStep.ToolCall) step.copy(execution = updateExecution(step.execution)) else step
                        }
                    )
                }
            }
            is AgentEvent.NewIteration -> Unit
        }
    }

    private suspend fun awaitInlineToolConfirmation(request: ConfirmationRequest, turnId: Long): Boolean {
        val toolCallId = request.toolCallId ?: return false
        if (activeTurnId.get() != turnId) return false
        val approvalId = "$turnId:$toolCallId"
        pendingConfirmationUi[toolCallId] = request
        pendingApprovalIds[toolCallId] = approvalId
        return try {
            pendingToolConfirmations.await(approvalId, turnId) {
                if (activeTurnId.get() != turnId) return@await
                updateToolExecution(toolCallId) { tool ->
                    tool.copy(
                        status = ToolStatus.PENDING,
                        metadata = tool.metadata + approvalMetadata(request, approvalId)
                    )
                }
            }
        } finally {
            pendingConfirmationUi.remove(toolCallId, request)
            pendingApprovalIds.remove(toolCallId, approvalId)
        }
    }

    private fun approvalMetadata(request: ConfirmationRequest, approvalId: String): Map<String, String> = mapOf(
        "approvalRequired" to "true",
        "approvalState" to "pending",
        "approvalReason" to request.reason,
        "approvalDetails" to request.details,
        "riskLevel" to request.riskLevel.name.lowercase(),
        "approvalId" to approvalId
    )

    private fun updateToolExecution(toolCallId: String, transform: (ToolExecution) -> ToolExecution) {
        updateCurrentAssistantMessage { msg ->
            val updatedTools = msg.toolExecutions.map { tool ->
                if (tool.toolCallId == toolCallId) transform(tool) else tool
            }
            val updatedSteps = msg.steps.map { step ->
                if (step is MessageStep.ToolCall && step.execution.toolCallId == toolCallId) {
                    step.copy(execution = transform(step.execution))
                } else step
            }
            msg.copy(toolExecutions = updatedTools, steps = updatedSteps)
        }
    }

    private fun bufferAssistantTextDelta(delta: String) {
        if (delta.isEmpty()) return
        assistantTextBuffer.append(delta)
        LocalStreamPerfLog.onInboundDelta(delta.length, assistantTextBuffer.length)
        if (assistantTextBuffer.length >= 256) {
            assistantFlushJob?.cancel()
            assistantFlushJob = null
            flushAssistantTextBuffer()
        } else if (assistantFlushJob?.isActive != true) {
            assistantFlushJob = scope.launch {
                delay(24)
                flushAssistantTextBuffer()
                assistantFlushJob = null
            }
        }
    }

    private fun bufferAssistantThinkingDelta(delta: String) {
        if (delta.isEmpty()) return
        assistantThinkingBuffer.append(delta)
        if (assistantThinkingBuffer.length >= 256) {
            thinkingFlushJob?.cancel()
            thinkingFlushJob = null
            flushAssistantThinkingBuffer()
        } else if (thinkingFlushJob?.isActive != true) {
            thinkingFlushJob = scope.launch {
                delay(24)
                flushAssistantThinkingBuffer()
                thinkingFlushJob = null
            }
        }
    }

    private fun flushAssistantThinkingBuffer() {
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
        if (assistantThinkingBuffer.isEmpty()) return
        val chunk = assistantThinkingBuffer.toString()
        assistantThinkingBuffer.clear()
        ensureAssistantMessage()
        updateCurrentAssistantMessage { msg ->
            msg.copy(
                thinking = msg.thinking.orEmpty() + chunk,
                isThinking = true,
                thinkingStartedAt = msg.thinkingStartedAt ?: System.currentTimeMillis()
            )
        }
    }

    private fun flushAssistantTextBuffer(now: Long = System.currentTimeMillis()) {
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        if (assistantTextBuffer.isEmpty()) return
        val chunk = assistantTextBuffer.toString()
        assistantTextBuffer.clear()
        lastAssistantTextUiEmitAt = now
        val startNs = System.nanoTime()
        var totalAssistantChars = 0
        var stepCount = 0
        ensureAssistantMessage()
        updateCurrentAssistantMessage { msg ->
            val newContent = msg.content + chunk
            val lastStep = msg.steps.lastOrNull()
            val newSteps = if (lastStep is MessageStep.Text) {
                msg.steps.dropLast(1) + lastStep.copy(content = lastStep.content + chunk)
            } else {
                msg.steps + MessageStep.Text(content = chunk)
            }
            totalAssistantChars = newContent.length
            stepCount = newSteps.size
            msg.copy(
                content = newContent,
                steps = newSteps,
                canonicalHistory = msg.canonicalHistory + JSONObject()
                    .put("kind", "assistant_text")
                    .put("text", chunk)
                    .toString()
            )
        }
        LocalStreamPerfLog.onUiFlush(
            chunkChars = chunk.length,
            totalAssistantChars = totalAssistantChars,
            messages = _uiState.value.messages.size,
            steps = stepCount,
            updateMs = (System.nanoTime() - startNs) / 1_000_000
        )
    }

    private fun ensureAssistantMessage() {
        val assistantMetadata = currentAssistantMetadata()
        val assistantId = currentAssistantMessageId
        val state = _uiState.value
        val msgs = state.messages.toMutableList()
        val currentIdx = assistantId?.let { id -> msgs.indexOfLast { it.id == id } } ?: -1

        if (currentIdx == -1) {
            val assistantMsg = UiMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                metadata = assistantMetadata
            )
            currentAssistantMessageId = assistantMsg.id
            _uiState.value = state.copy(messages = msgs + assistantMsg)
            return
        }

        val existing = msgs[currentIdx]
        if (existing.metadata.isEmpty() && assistantMetadata.isNotEmpty()) {
            msgs[currentIdx] = existing.copy(metadata = assistantMetadata)
        }
        _uiState.value = state.copy(messages = msgs)
    }
    private fun ensureBrowserConversationSession(seedMessageId: String) {
        if (browserConversationKey != null) return
        val key = currentConversationId?.let { "conversation:$it" } ?: "draft:$seedMessageId"
        browserConversationKey = key
        browserSessionManager.resetForConversation(key)
    }

    private fun currentAssistantMetadata(): Map<String, String> {
        val state = _uiState.value
        val model = state.modelOptions.firstOrNull { it.id == state.activeModelKey }

        return buildMap {
            put("source", "local")
            model?.name?.takeIf { it.isNotBlank() }?.let { put("agent_name", it) }
            state.selectedModel.takeIf { it.isNotBlank() }?.let { put("model_id", it) }
                ?: model?.modelId?.takeIf { it.isNotBlank() }?.let { put("model_id", it) }
            if (!containsKey("agent_name")) {
                model?.id?.takeIf { it.isNotBlank() }?.let { put("agent_name", it) }
            }
        }
    }

    private fun currentAssistantTextLength(): Int {
        val assistantId = currentAssistantMessageId ?: return 0
        return _uiState.value.messages.lastOrNull { it.id == assistantId }?.content?.length ?: 0
    }

    private fun markCurrentAssistantCompleted() = markCurrentAssistantTerminal("completed")

    /**
     * Reasoning is finalized the moment any *non-thinking* event follows a
     * [AgentEvent.ThinkingDelta] (text delta, tool call start, tool result,
     * stream end, error). Without this, the ThinkingCard would stay in
     * "pending" until [markCurrentAssistantTerminal] runs at the end of the
     * agent turn, which is exactly the race condition that hides reasoning
     * behind every other timeline event.
     *
     * Captures duration if not already set, and is a no-op when reasoning
     * was already finalised or never started.
     */
    private fun finalizeThinkingIfActive() {
        updateCurrentAssistantMessage { msg ->
            if (!msg.isThinking && msg.thinkingDurationMs != null) return@updateCurrentAssistantMessage msg
            val nowMs = System.currentTimeMillis()
            val durationMs = msg.thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
            msg.copy(
                isThinking = false,
                thinkingDurationMs = msg.thinkingDurationMs ?: durationMs
            )
        }
    }

    private fun markCurrentAssistantTerminal(status: String) {
        val nowMs = System.currentTimeMillis()
        val now = nowMs.toString()
        updateCurrentAssistantMessage { msg ->
            val durationMs = msg.thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
            msg.copy(
                metadata = msg.metadata + mapOf("completedAt" to now, "turnStatus" to status),
                isThinking = false,
                thinkingDurationMs = msg.thinkingDurationMs ?: durationMs
            )
        }
    }

    private fun markActiveToolsStopped() {
        updateCurrentAssistantMessage { msg ->
            val active = msg.toolExecutions.filter {
                it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING
            }
            fun stopped(tool: ToolExecution): ToolExecution = if (tool in active) tool.copy(
                status = ToolStatus.ERROR,
                result = tool.result ?: "Stopped by user",
                metadata = tool.metadata + mapOf(
                    "approvalRequired" to "false",
                    "approvalState" to "cancelled"
                )
            ) else tool
            val terminalItems = active.map { tool ->
                JSONObject()
                    .put("kind", "tool_result")
                    .put("id", tool.toolCallId)
                    .put("name", tool.name)
                    .put("result", tool.result ?: "Stopped by user")
                    .put("isError", true)
                    .toString()
            }
            msg.copy(
                toolExecutions = msg.toolExecutions.map(::stopped),
                steps = msg.steps.map { step ->
                    if (step is MessageStep.ToolCall) step.copy(execution = stopped(step.execution)) else step
                },
                canonicalHistory = msg.canonicalHistory + terminalItems
            )
        }
    }

    private fun updateCurrentAssistantMessage(update: (UiMessage) -> UiMessage) {
        val assistantId = currentAssistantMessageId
        if (assistantId == null) return

        val state = _uiState.value
        val msgs = state.messages.toMutableList()
        val assistantIdx = msgs.indexOfLast { it.id == assistantId }
        if (assistantIdx == -1) return

        msgs[assistantIdx] = update(msgs[assistantIdx])
        _uiState.value = state.copy(messages = msgs)
    }

    override fun stopGeneration() {
        activeTurnId.incrementAndGet()
        chatJob?.cancel()
        chatJob = null
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
        pendingToolConfirmations.cancelAll()
        pendingConfirmationUi.clear()
        pendingApprovalIds.clear()
        flushAssistantThinkingBuffer()
        flushAssistantTextBuffer()
        markActiveToolsStopped()
        markCurrentAssistantTerminal("cancelled")
        browserSessionManager.cancelFromUser()
        browserSessionManager.onAssistantStreamingChanged(false)
        _uiState.update { it.copy(isLoading = false, isStreaming = false) }
        saveCurrentConversation()
    }

    override fun clearConversation() {
        activeTurnId.incrementAndGet()
        chatJob?.cancel()
        chatJob = null
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
        pendingToolConfirmations.cancelAll()
        pendingConfirmationUi.clear()
        pendingApprovalIds.clear()
        currentConversationId = null
        currentAssistantMessageId = null
        assistantTextBuffer.clear()
        assistantThinkingBuffer.clear()
        lastAssistantTextUiEmitAt = 0L
        browserConversationKey = null
        browserSessionManager.resetEphemeral()
        _uiState.update { it.copy(
            conversationId = null,
            messages = emptyList(),
            error = null,
            isLoading = false,
            isStreaming = false,
            totalInputTokens = 0,
            totalOutputTokens = 0
        )}
    }

    override fun loadConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        val turnId = activeTurnId.incrementAndGet()
        chatJob?.cancel()
        chatJob = null
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
        pendingToolConfirmations.cancelAll()
        pendingConfirmationUi.clear()
        pendingApprovalIds.clear()
        scope.launch {
            val entity = conversationDao.getConversationById(longId)
            if (activeTurnId.get() != turnId) return@launch
            entity?.let { conv ->
                val parsed = parseMessagesFromJson(conv.messagesJson)
                val messages = parsed.getOrElse {
                    _uiState.update { state -> state.copy(error = "Conversation data is corrupted and could not be loaded") }
                    return@launch
                }
                currentConversationId = conv.id
                currentAssistantMessageId = null
                assistantTextBuffer.clear()
                assistantThinkingBuffer.clear()
                lastAssistantTextUiEmitAt = 0L
                browserConversationKey = "conversation:${conv.id}"
                browserSessionManager.resetForConversation(browserConversationKey!!)
                _uiState.update { it.copy(
                    conversationId = conv.id.toString(),
                    workspacePath = conv.workspacePath,
                    messages = messages,
                    totalInputTokens = 0,
                    totalOutputTokens = 0,
                    error = null
                )}
            }
        }
    }

    override fun deleteConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        titleJobs.remove(longId)?.cancel()
        if (currentConversationId == longId) clearConversation()
        scope.launch { conversationDao.deleteConversationById(longId) }
    }

    override fun selectModel(modelKey: String) {
        if (_uiState.value.isStreaming) stopGeneration()
        scope.launch {
            val parts = modelKey.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != "model") return@launch
            settingsManager.setActiveModel(
                com.amaya.intelligence.data.remote.api.ActiveModelSelection(
                    connectionId = parts[1],
                    modelId = parts[2]
                )
            )
            // Optimistically load persisted effort for the newly selected model.
            val loaded = settingsManager.getThinkingEffort(parts[1], parts[2])
            _uiState.update { it.copy(effort = loaded) }
        }
    }

    override fun setEffort(effort: com.amaya.intelligence.data.remote.api.ThinkingEffort) {
        _uiState.update { it.copy(effort = effort) }
        // Persist per-model so it survives restart and model switches.
        val selection = settingsManager.getSettings().activeSelection ?: return
        scope.launch {
            settingsManager.setThinkingEffort(selection.connectionId, selection.modelId, effort)
        }
    }

    override fun setWorkspace(path: String?) {
        _uiState.update { it.copy(workspacePath = path) }
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun loadMoreConversations() {
        // No-op for local for now
    }

    override fun hasMoreConversations(): Boolean {
        return false
    }

    override fun getProjectFiles(path: String) {
        // Local project files logic if needed
    }

    override fun respondToToolInteraction(executionId: String, confirmed: Boolean) {
        val toolCallId = executionId.substringAfter(':', executionId)
        pendingToolConfirmations.resolve(executionId, activeTurnId.get(), confirmed) {
            pendingConfirmationUi.remove(toolCallId)
            pendingApprovalIds.remove(toolCallId, executionId)
            updateToolExecution(toolCallId) { tool ->
                tool.copy(
                    status = if (confirmed) ToolStatus.RUNNING else ToolStatus.ERROR,
                    result = if (confirmed) tool.result else "User declined: ${tool.metadata["approvalReason"].orEmpty()}",
                    metadata = tool.metadata + mapOf(
                        "approvalRequired" to "false",
                        "approvalState" to if (confirmed) "accepted" else "declined"
                    )
                )
            }
        }
    }

    private fun parseMessagesFromJson(json: String): Result<List<UiMessage>> {
        if (json.isBlank()) return Result.success(emptyList())
        return try {
            val messages = mutableListOf<UiMessage>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val role = when (obj.optString("role")) {
                    "USER" -> MessageRole.USER
                    "ASSISTANT" -> MessageRole.ASSISTANT
                    "SYSTEM" -> MessageRole.SYSTEM
                    else -> MessageRole.USER
                }

                val toolExecutions = mutableListOf<ToolExecution>()
                if (obj.has("toolExecutions")) {
                    val execArr = obj.getJSONArray("toolExecutions")
                    for (j in 0 until execArr.length()) {
                        val e = execArr.getJSONObject(j)
                        toolExecutions.add(parseToolExecutionFromJson(e))
                    }
                }

                val steps = mutableListOf<MessageStep>()
                if (obj.has("steps")) {
                    val stepsArr = obj.getJSONArray("steps")
                    for (j in 0 until stepsArr.length()) {
                        val s = stepsArr.getJSONObject(j)
                        val stepId = s.optString("id", UUID.randomUUID().toString())
                        when (s.optString("type")) {
                            "text" -> {
                                steps.add(MessageStep.Text(
                                    id = stepId,
                                    content = s.getString("content"),
                                    formattedContent = s.optString("formattedContent").takeIf { it.isNotBlank() }
                                ))
                            }
                            "toolCall" -> {
                                val eObj = s.getJSONObject("execution")
                                steps.add(MessageStep.ToolCall(
                                    id = stepId,
                                    execution = parseToolExecutionFromJson(eObj)
                                ))
                            }
                        }
                    }
                }

                val todoItems = mutableListOf<com.amaya.intelligence.tools.TodoItem>()
                if (obj.has("todoItems")) {
                    val todoArr = obj.getJSONArray("todoItems")
                    for (j in 0 until todoArr.length()) {
                        val t = todoArr.getJSONObject(j)
                        todoItems.add(
                            com.amaya.intelligence.tools.TodoItem(
                                id = t.getInt("id"),
                                content = t.optString("content").takeIf { it.isNotBlank() },
                                activeForm = t.optString("activeForm").takeIf { it.isNotBlank() },
                                status = runCatching {
                                    com.amaya.intelligence.tools.TodoStatus.valueOf(t.getString("status"))
                                }.getOrDefault(com.amaya.intelligence.tools.TodoStatus.PENDING)
                            )
                        )
                    }
                }

                val metadata = mutableMapOf<String, String>()
                if (obj.has("metadata")) {
                    val metaObj = obj.getJSONObject("metadata")
                    metaObj.keys().forEach { key ->
                        metadata[key] = metaObj.optString(key, "")
                    }
                }

                val responseItems = obj.optJSONArray("responseItems")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                }.orEmpty()
                val canonicalHistory = obj.optJSONArray("canonicalHistory")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                }.orEmpty()
                val attachments = obj.optJSONArray("attachments")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val attachment = array.optJSONObject(index) ?: return@mapNotNull null
                        val mime = attachment.optString("mimeType")
                        val data = attachment.optString("dataBase64")
                        if (mime.isBlank() || data.isBlank()) null else MessageAttachment(
                            mimeType = mime,
                            dataBase64 = data,
                            fileName = attachment.optString("fileName")
                        )
                    }
                }.orEmpty()
                messages.add(
                    UiMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        role = role,
                        content = obj.optString("content"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        thinking = obj.optString("thinking").takeIf { it.isNotBlank() },
                        thinkingStartedAt = obj.optLong("thinkingStartedAt", 0L).takeIf { it > 0 },
                        thinkingDurationMs = obj.optLong("thinkingDurationMs", 0L).takeIf { it > 0 },
                        metadata = metadata,
                        toolExecutions = toolExecutions,
                        steps = steps,
                        todoItems = todoItems,
                        attachments = attachments,
                        responseItems = responseItems,
                        canonicalHistory = canonicalHistory
                    )
                )
            }
            Result.success(messages)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun saveCurrentConversation() {
        val conversationId = currentConversationId ?: return
        val messages = _uiState.value.messages
        val messagesJson = serializeMessagesToJson(messages)
        scope.launch {
            conversationSaveMutex.withLock {
                try {
                    if (currentConversationId == conversationId &&
                        serializeMessagesToJson(_uiState.value.messages) != messagesJson
                    ) return@withLock
                    val existing = conversationDao.getConversationById(conversationId) ?: return@withLock
                    conversationDao.updateConversation(
                        existing.copy(messagesJson = messagesJson, updatedAt = System.currentTimeMillis())
                    )
                } catch (error: Exception) {
                    if (currentConversationId == conversationId) {
                        _uiState.update { it.copy(error = "Could not save this conversation: ${error.message.orEmpty()}") }
                    }
                }
            }
        }
    }

    private fun launchTitleGeneration(userMessage: String, conversationId: Long?) {
        if (conversationId == null) return
        val settings = settingsManager.getSettings()
        val selection = settings.activeSelection ?: return
        val connection = settings.connections.firstOrNull { it.id == selection.connectionId } ?: return
        val selectedModel = _uiState.value.selectedModel.ifBlank { selection.modelId }
        titleJobs.remove(conversationId)?.cancel()
        titleJobs[conversationId] = scope.launch {
            try {
                val title = aiRepository.generateTitle(userMessage, connection, selectedModel)
                val conversation = conversationDao.getConversationById(conversationId) ?: return@launch
                conversationDao.updateTitle(conversationId, title)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Title generation failure is non-fatal.
            } finally {
                titleJobs.remove(conversationId, coroutineContext[Job])
            }
        }
    }


    private suspend fun persistCurrentConversation(): Long? = conversationSaveMutex.withLock {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return@withLock currentConversationId
        val hasContent = messages.any { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() } ||
            messages.any { it.role == MessageRole.USER && it.content.isNotBlank() }
        if (!hasContent) return@withLock currentConversationId

        return@withLock try {
            val firstUserMsg = messages.firstOrNull { it.role == MessageRole.USER }?.content ?: "New Conversation"
            val title = firstUserMsg.split("\\s+".toRegex()).take(5).joinToString(" ").take(50)
            val now = System.currentTimeMillis()
            val messagesJson = serializeMessagesToJson(messages)

            val existingId = currentConversationId
            if (existingId != null) {
                val existing = conversationDao.getConversationById(existingId)
                if (existing != null) {
                    conversationDao.updateConversation(
                        existing.copy(
                            messagesJson = messagesJson,
                            updatedAt = now
                        )
                    )
                    existingId
                } else {
                    currentConversationId = null
                    insertConversationLocked(title, messagesJson, now)
                }
            } else {
                insertConversationLocked(title, messagesJson, now)
            }
        } catch (error: Exception) {
            _uiState.update { it.copy(error = "Could not save this conversation: ${error.message.orEmpty()}") }
            null
        }
    }

    private suspend fun insertConversationLocked(title: String, messagesJson: String, now: Long): Long {
        val newId = conversationDao.insertConversation(
            ConversationEntity(
                id = 0,
                title = title,
                workspacePath = _uiState.value.workspacePath,
                messagesJson = messagesJson,
                createdAt = now,
                updatedAt = now
            )
        )
        currentConversationId = newId
        _uiState.update { it.copy(conversationId = newId.toString()) }
        return newId
    }

    private fun serializeMessagesToJson(messages: List<UiMessage>): String {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            val obj = JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                if (!msg.thinking.isNullOrBlank()) put("thinking", msg.thinking)
                msg.thinkingStartedAt?.let { put("thinkingStartedAt", it) }
                msg.thinkingDurationMs?.let { put("thinkingDurationMs", it) }
                if (msg.responseItems.isNotEmpty()) put("responseItems", JSONArray(msg.responseItems))
                if (msg.canonicalHistory.isNotEmpty()) put("canonicalHistory", JSONArray(msg.canonicalHistory))
                if (msg.attachments.isNotEmpty()) {
                    put("attachments", JSONArray().apply {
                        msg.attachments.forEach { attachment ->
                            put(JSONObject()
                                .put("mimeType", attachment.mimeType)
                                .put("dataBase64", attachment.dataBase64)
                                .put("fileName", attachment.fileName))
                        }
                    })
                }
            }

            if (msg.toolExecutions.isNotEmpty()) {
                val execArr = JSONArray()
                msg.toolExecutions.forEach { exec ->
                    execArr.put(serializeToolExecutionToJson(exec))
                }
                obj.put("toolExecutions", execArr)
            }

            if (msg.steps.isNotEmpty()) {
                val stepsArr = JSONArray()
                msg.steps.forEach { step ->
                    val stepObj = JSONObject().apply {
                        put("id", step.id)
                        when (step) {
                            is MessageStep.Text -> {
                                put("type", "text")
                                put("content", step.content)
                                step.formattedContent?.let { put("formattedContent", it) }
                            }
                            is MessageStep.ToolCall -> {
                                put("type", "toolCall")
                                put("execution", serializeToolExecutionToJson(step.execution))
                            }
                        }
                    }
                    stepsArr.put(stepObj)
                }
                obj.put("steps", stepsArr)
            }

            if (msg.metadata.isNotEmpty()) {
                val metaObj = JSONObject()
                msg.metadata.forEach { (key, value) -> metaObj.put(key, value) }
                obj.put("metadata", metaObj)
            }

            if (msg.todoItems.isNotEmpty()) {
                val todoArr = JSONArray()
                msg.todoItems.forEach { todo ->
                    todoArr.put(
                        JSONObject().apply {
                            put("id", todo.id)
                            todo.content?.let { put("content", it) }
                            todo.activeForm?.let { put("activeForm", it) }
                            put("status", todo.status.name)
                        }
                    )
                }
                obj.put("todoItems", todoArr)
            }

            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
    private fun parseToolExecutionFromJson(e: JSONObject): ToolExecution {
        val argsMap = mutableMapOf<String, Any?>()
        if (e.has("arguments")) {
            val argsObj = e.getJSONObject("arguments")
            argsObj.keys().forEach { key -> argsMap[key] = argsObj.get(key) }
        }
        val children = mutableListOf<SubagentExecution>()
        if (e.has("children")) {
            val childArr = e.getJSONArray("children")
            for (k in 0 until childArr.length()) {
                val c = childArr.getJSONObject(k)
                children.add(
                    SubagentExecution(
                        index = c.getInt("index"),
                        taskName = c.getString("taskName"),
                        prompt = c.getString("prompt"),
                        result = c.optString("result").takeIf { it.isNotBlank() },
                        status = runCatching { ToolStatus.valueOf(c.getString("status")) }
                            .getOrDefault(ToolStatus.SUCCESS)
                            .let { status -> if (status == ToolStatus.PENDING || status == ToolStatus.RUNNING) ToolStatus.ERROR else status }
                    )
                )
            }
        }
        val metaMap = mutableMapOf<String, String>()
        if (e.has("metadata")) {
            val mObj = e.getJSONObject("metadata")
            mObj.keys().forEach { key -> metaMap[key] = mObj.getString(key) }
        } else {
            metaMap["source"] = "local"
        }
        val persistedStatus = runCatching { ToolStatus.valueOf(e.getString("status")) }
            .getOrDefault(ToolStatus.SUCCESS)
        val interrupted = persistedStatus == ToolStatus.PENDING || persistedStatus == ToolStatus.RUNNING
        return ToolExecution(
            toolCallId = e.getString("toolCallId"),
            name = e.getString("name"),
            arguments = argsMap,
            result = e.optString("result").takeIf { it.isNotBlank() }
                ?: "Stopped before completion".takeIf { interrupted },
            status = if (interrupted) ToolStatus.ERROR else persistedStatus,
            children = children,
            metadata = if (metaMap["approvalState"] == "pending") metaMap + mapOf(
                "approvalRequired" to "false",
                "approvalState" to "cancelled"
            ) else metaMap,
            uiMetadata = LocalToolMapper.getUiMetadata(
                toolName = e.getString("name"),
                args = argsMap
            )
        )
    }

    private fun serializeToolExecutionToJson(exec: ToolExecution): JSONObject {
        return JSONObject().apply {
            put("toolCallId", exec.toolCallId)
            put("name", exec.name)
            put("status", exec.status.name)
            exec.result?.let { put("result", it) }
            put("arguments", JSONObject().apply {
                exec.arguments.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            })
            if (exec.children.isNotEmpty()) {
                val childArr = JSONArray()
                exec.children.forEach { child ->
                    childArr.put(
                        JSONObject().apply {
                            put("index", child.index)
                            put("taskName", child.taskName)
                            put("prompt", child.prompt)
                            child.result?.let { put("result", it) }
                            put("status", child.status.name)
                        }
                    )
                }
                put("children", childArr)
            }
            if (exec.metadata.isNotEmpty()) {
                val mObj = JSONObject()
                exec.metadata.forEach { (k, v) -> mObj.put(k, v) }
                put("metadata", mObj)
            }
        }
    }

}

// Extension to map domain to repository model
private fun UiMessage.toChatMessages(): List<ChatMessage> {
    if (role == MessageRole.ASSISTANT && canonicalHistory.isNotEmpty()) {
        canonicalHistoryToChatMessages(canonicalHistory).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val calls = toolExecutions.map { execution ->
        com.amaya.intelligence.data.remote.api.ToolCallMessage(
            id = execution.toolCallId,
            name = execution.name,
            arguments = execution.arguments,
            metadata = execution.metadata.filterKeys { it == "thoughtSignature" }
        )
    }
    val message = ChatMessage(
        role = role,
        content = content,
        images = attachments.filter { it.mimeType.startsWith("image/") }.map {
            com.amaya.intelligence.data.remote.api.ChatImage(it.dataBase64, it.mimeType, it.fileName)
        },
        toolCalls = calls.takeIf { it.isNotEmpty() },
        responseItems = responseItems
    )
    if (role != MessageRole.ASSISTANT || calls.isEmpty()) return listOf(message)
    return buildList {
        add(message)
        toolExecutions.forEach { execution ->
            execution.result?.let { result ->
                add(ChatMessage(
                    role = MessageRole.TOOL,
                    toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                        toolCallId = execution.toolCallId,
                        content = result,
                        isError = execution.status == ToolStatus.ERROR,
                        metadata = execution.metadata.filterKeys { it == "thoughtSignature" } +
                            ("toolName" to execution.name)
                    )
                ))
            }
        }
    }
}

internal fun canonicalHistoryToChatMessages(history: List<String>): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    val text = StringBuilder()
    val calls = mutableListOf<com.amaya.intelligence.data.remote.api.ToolCallMessage>()
    val responseItems = mutableListOf<String>()

    fun flushAssistant() {
        if (text.isEmpty() && calls.isEmpty() && responseItems.isEmpty()) return
        messages += ChatMessage(
            role = MessageRole.ASSISTANT,
            content = text.toString().takeIf { it.isNotBlank() },
            toolCalls = calls.toList().takeIf { it.isNotEmpty() },
            responseItems = responseItems.toList()
        )
        text.clear()
        calls.clear()
        responseItems.clear()
    }

    history.forEach { raw ->
        val item = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
        when (item.optString("kind")) {
            "assistant_text" -> text.append(item.optString("text"))
            "response_item" -> item.optJSONObject("item")?.let { responseItem ->
                responseItems += responseItem.toString()
            }
            "assistant_tool_call" -> calls += com.amaya.intelligence.data.remote.api.ToolCallMessage(
                id = item.optString("id"),
                name = item.optString("name"),
                arguments = item.optJSONObject("arguments")?.toAnyMap().orEmpty(),
                metadata = item.optJSONObject("metadata")?.toStringMap().orEmpty()
            )
            "tool_result" -> {
                flushAssistant()
                messages += ChatMessage(
                    role = MessageRole.TOOL,
                    toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                        toolCallId = item.optString("id"),
                        content = item.optString("result"),
                        isError = item.optBoolean("isError"),
                        metadata = mapOf("toolName" to item.optString("name"))
                    )
                )
            }
        }
    }
    flushAssistant()
    return messages
}

private fun JSONObject.toAnyMap(): Map<String, Any?> = buildMap {
    keys().forEach { key -> put(key, opt(key).takeUnless { it == JSONObject.NULL }) }
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, optString(key, "")) }
}
