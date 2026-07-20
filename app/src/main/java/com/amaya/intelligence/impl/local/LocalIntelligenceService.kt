package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
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
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context

/**
 * Local implementation of IntelligenceService.
 * Wraps AiRepository and handles persistence via ConversationDao.
 */
@Singleton
class LocalIntelligenceService @Inject constructor(
    private val aiRepository: AiRepository,
    private val conversationDao: ConversationDao,
    private val agentDao: AgentDao,
    private val projectDao: ProjectDao,
    private val settingsManager: AiSettingsManager,
    private val browserSessionManager: BrowserSessionManager,
    @ApplicationContext private val appContext: Context,
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
    override val allLocalConversations: StateFlow<List<ConversationEntity>> = conversationDao.observeAllConversations()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _runningSessions = MutableStateFlow<List<RunningSession>>(emptyList())
    override val runningSessions: StateFlow<List<RunningSession>> = _runningSessions.asStateFlow()
    private val _completedSessions = kotlinx.coroutines.flow.MutableSharedFlow<RunningSession>(extraBufferCapacity = 16)
    override val completedSessions: kotlinx.coroutines.flow.SharedFlow<RunningSession> = _completedSessions

    private val _workspaces = MutableStateFlow<List<RemoteWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<RemoteWorkspace>> = _workspaces.asStateFlow()

    private var chatJob: Job? = null
    @Volatile private var currentConversationId: Long? = null
    private var currentAssistantMessageId: String? = null
    private val assistantTextBuffer = StringBuilder()
    private val assistantThinkingBuffer = StringBuilder()
    private var lastAssistantTextUiEmitAt = 0L
    private var assistantFlushJob: Job? = null
    private var thinkingFlushJob: Job? = null
    private var browserConversationKey: String? = null
    private val conversationSaveMutex = Mutex()
    private val conversationStartMutex = Mutex()
    private val pendingToolConfirmations = ToolConfirmationRegistry()
    private val pendingConfirmationUi = ConcurrentHashMap<String, ConfirmationRequest>()
    private val pendingApprovalIds = ConcurrentHashMap<String, String>()
    private val titleJobs = ConcurrentHashMap<Long, Job>()
    private val activeTurnId = AtomicLong(0L)
    private val targetEpoch = AtomicLong(0L)
    private val activeTurns = ConcurrentHashMap<Long, LocalTurn>()
    private val startingConversations = ConcurrentHashMap.newKeySet<Long>()
    private val turnsById = ConcurrentHashMap<Long, LocalTurn>()
    private data class LocalTurn(
        val turnId: Long,
        val conversationId: Long,
        val prompt: String,
        val isNewConversation: Boolean,
        var state: ChatUiState,
        val notificationTitle: String,
        val notificationSender: String,
        val notificationThreadKey: String,
        var assistantMessageId: String? = null,
        var job: Job? = null,
        var delegationActive: Boolean = false,
        var lastStatus: String = "Streaming",
        var lastDetail: String = "Waiting for response",
        var lastNotificationAt: Long = 0L,
        var delegateTotal: Int = 0,
        var delegateCompleted: Int = 0,
        var activeDelegateName: String? = null
    )

    init {
        scope.launch { recoverInterruptedTurns() }

        // Keep the drawer scoped to Chat/Project. An Agent owns exactly one conversation.
        scope.launch {
            _uiState
                .map { Triple(it.assistantMode, it.ownerId, it.agentId) }
                .distinctUntilChanged()
                .collectLatest { (mode, ownerId, agentId) ->
                    val source = if (mode == AssistantMode.AGENT && agentId != null) {
                        conversationDao.observeAgentConversation(agentId)
                    } else {
                        conversationDao.observeOwnedConversations(mode.name, ownerId)
                    }
                    source.collect { list -> _conversations.value = list }
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

    override suspend fun sendMessageToConversation(conversationId: Long, content: String): Boolean {
        val text = content.trim()
        if (text.isBlank() || activeTurns.containsKey(conversationId)) return false
        val entity = conversationDao.getConversationById(conversationId) ?: return false
        val messages = parseMessagesFromJson(entity.messagesJson).getOrNull() ?: return false
        val settings = settingsManager.getSettings()
        val preferredModelKey = entity.agentId?.let { id -> agentDao.getById(id)?.defaultModelKeysJson }
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { array -> (0 until array.length()).map { array.optString(it) }.firstOrNull(String::isNotBlank) }
            ?: settings.activeSelection?.key.orEmpty()
        val modelParts = preferredModelKey.split('|', limit = 3)
        val selectedModel = modelParts.getOrNull(2).orEmpty().ifBlank { settings.activeSelection?.modelId.orEmpty() }
        val effort = if (modelParts.size == 3) settingsManager.getThinkingEffort(modelParts[1], modelParts[2]) else _uiState.value.effort
        val state = ChatUiState(
            messages = messages,
            selectedModel = selectedModel,
            workspacePath = entity.workspacePath,
            assistantMode = runCatching { AssistantMode.valueOf(entity.assistantMode) }.getOrDefault(AssistantMode.CHAT),
            ownerId = entity.ownerId,
            agentId = entity.agentId,
            modelOptions = _uiState.value.modelOptions,
            activeModelKey = preferredModelKey,
            conversationId = entity.id.toString(),
            effort = effort,
            sessionMode = IntelligenceSessionManager.SessionMode.LOCAL
        )
        return startTurn(text, emptyList(), state, projectVisible = false)
    }

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
        val currentState = _uiState.value
        scope.launch {
            if (!startTurn(content, images, currentState, projectVisible = true)) {
                _uiState.update { it.copy(error = "Could not start this conversation.", isLoading = false, isStreaming = false) }
            }
        }
    }

    private suspend fun startTurn(
        content: String,
        images: List<com.amaya.intelligence.data.remote.api.ChatImage>,
        initialState: ChatUiState,
        projectVisible: Boolean
    ): Boolean {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank() && images.isEmpty()) return false
        val selectedEpoch = targetEpoch.get()
        val activeConversation = initialState.conversationId?.toLongOrNull()
        if (activeConversation != null &&
            (activeTurns.containsKey(activeConversation) || !startingConversations.add(activeConversation))
        ) {
            if (projectVisible) _uiState.update { it.copy(error = "This session is already streaming") }
            return false
        }
        val turnId = activeTurnId.incrementAndGet()
        val userMsg = UiMessage(
            role = MessageRole.USER,
            content = trimmedContent,
            attachments = images.map { MessageAttachment(it.mediaType, it.base64, it.fileName) }
        )
        if (projectVisible) ensureBrowserConversationSession(userMsg.id)
        val turnState = initialState.copy(messages = initialState.messages + userMsg, isLoading = true, isStreaming = true, error = null)
        if (projectVisible) _uiState.value = turnState
        val isNewConversation = activeConversation == null
        val conversationId = conversationStartMutex.withLock {
            if (projectVisible && targetEpoch.get() != selectedEpoch) return@withLock null
            persistConversationStart(turnState, activeConversation)
        }
        if (conversationId == null) {
            activeConversation?.let(startingConversations::remove)
            return false
        }
        val runtimeState = turnState.copy(conversationId = conversationId.toString())
        if (projectVisible && targetEpoch.get() == selectedEpoch) {
            currentConversationId = conversationId
            _uiState.value = runtimeState
        }
        val notificationIdentity = notificationIdentity(runtimeState)
        val turn = LocalTurn(
            turnId,
            conversationId,
            trimmedContent,
            isNewConversation,
            runtimeState,
            notificationIdentity.title,
            notificationIdentity.sender,
            notificationIdentity.threadKey
        )
        activeTurns[conversationId] = turn
        activeConversation?.let(startingConversations::remove)
        turnsById[turnId] = turn
        com.amaya.intelligence.service.AiSessionNotificationService.start(appContext)
        publishTurn(turn, "Streaming", "Waiting for response")
        turn.job = scope.launch {
                var completed = false
                var failed = false
                try {
                    aiRepository.chat(
                        message = trimmedContent,
                        userImages = images,
                        conversationHistory = runtimeState.messages.dropLast(1).flatMap { it.toChatMessages() },
                        workspacePath = runtimeState.workspacePath,
                        assistantMode = runtimeState.assistantMode,
                        ownerId = runtimeState.ownerId,
                        agentId = runtimeState.agentId,
                        connectionId = runtimeState.activeModelKey.takeIf { it.startsWith("model|") }?.split('|', limit = 3)?.getOrNull(1),
                        conversationId = conversationId,
                        selectedModel = runtimeState.selectedModel,
                        effort = runtimeState.effort,
                        onConfirmation = { request -> awaitInlineToolConfirmation(request, turnId) }
                    ).collect { event ->
                        completed = completed || event is AgentEvent.Done
                        failed = failed || event is AgentEvent.Error || event is AgentEvent.Incomplete
                        handleTurnEvent(turn, event)
                    }
                    if (isNewConversation && completed) launchTitleGeneration(trimmedContent, conversationId)
                    if (!completed && !failed) {
                        failed = true
                        handleTurnEvent(turn, AgentEvent.Incomplete("Provider stream ended unexpectedly", retryable = true))
                    }
                } catch (cancelled: CancellationException) {
                    failed = true
                    handleTurnEvent(turn, AgentEvent.Incomplete("Stopped", retryable = true))
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    handleTurnEvent(turn, AgentEvent.Error(error.message.orEmpty().ifBlank { "AI session failed" }, retryable = true))
                } finally {
                    turn.state = turn.state.copy(isLoading = false, isStreaming = false)
                    persistTurn(turn)
                    activeTurns.remove(conversationId, turn)
                    turnsById.remove(turn.turnId, turn)
                    removeRunningSession(conversationId)
                    if (currentConversationId == conversationId) {
                        browserSessionManager.onAssistantStreamingChanged(false)
                        _uiState.update { it.copy(isLoading = false, isStreaming = false) }
                    }
                }
            }
        return true
    }

    private fun publishTurn(turn: LocalTurn, status: String, detail: String, urgent: Boolean = false) {
        turn.lastStatus = status
        turn.lastDetail = detail
        val now = System.currentTimeMillis()
        if (!urgent && now - turn.lastNotificationAt < 900L && status in setOf("Streaming", "Thinking")) {
            if (currentConversationId == turn.conversationId) _uiState.value = turn.state
            return
        }
        turn.lastNotificationAt = now
        val activeTool = turn.state.messages.asReversed().asSequence()
            .flatMap { it.toolExecutions.asReversed().asSequence() }
            .firstOrNull { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING }
        val item = RunningSession(
            conversationId = turn.conversationId,
            title = turn.notificationTitle,
            mode = turn.state.assistantMode,
            ownerId = turn.state.ownerId,
            agentId = turn.state.agentId,
            status = status,
            detail = detail,
            updatedAt = System.currentTimeMillis(),
            isDelegating = turn.delegationActive,
            approvalId = activeTool?.metadata?.get("approvalId")?.takeIf { activeTool.metadata["approvalState"] == "pending" },
            approvalLabel = activeTool?.takeIf { it.metadata["approvalState"] == "pending" }
                ?.let { LocalToolMapper.displayLabel(it.name, it.arguments) },
            approvalRisk = activeTool?.metadata?.get("riskLevel"),
            phase = sessionPhase(status, turn.delegationActive),
            latestAssistantMessage = turn.state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty(),
            completedDelegates = turn.delegateCompleted,
            totalDelegates = turn.delegateTotal,
            activeDelegateName = turn.activeDelegateName,
            notificationTitle = turn.notificationTitle,
            notificationSender = turn.notificationSender,
            notificationThreadKey = turn.notificationThreadKey
        )
        if (item.phase in setOf(SessionPhase.COMPLETED, SessionPhase.FAILED, SessionPhase.STOPPED)) {
            _runningSessions.update { sessions -> sessions.filterNot { it.conversationId == turn.conversationId } }
            _completedSessions.tryEmit(item)
        } else {
            _runningSessions.update { sessions ->
                (sessions.filterNot { it.conversationId == turn.conversationId } + item).sortedByDescending(RunningSession::updatedAt)
            }
        }
        if (currentConversationId == turn.conversationId) {
            _uiState.value = turn.state
        }
    }

    private suspend fun recoverInterruptedTurns() {
        conversationDao.getConversationsByScope("local").forEach { summary ->
            val entity = conversationDao.getConversationById(summary.id) ?: return@forEach
            val messages = parseMessagesFromJson(entity.messagesJson).getOrNull() ?: return@forEach
            val index = messages.indexOfLast { message ->
                message.role == MessageRole.ASSISTANT &&
                    message.metadata["turnStatus"].isNullOrBlank() &&
                    (message.isThinking || message.toolExecutions.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING })
            }
            if (index < 0) return@forEach
            val interrupted = messages[index].let { message ->
                fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
                    tool.copy(
                        status = ToolStatus.ERROR,
                        result = tool.result ?: "Interrupted when the app process stopped",
                        metadata = tool.metadata + mapOf("approvalRequired" to "false", "approvalState" to "cancelled")
                    )
                } else tool
                message.copy(
                    isThinking = false,
                    toolExecutions = message.toolExecutions.map(::stop),
                    steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = stop(it.execution)) else it },
                    metadata = message.metadata + mapOf(
                        "turnStatus" to "interrupted",
                        "completedAt" to System.currentTimeMillis().toString(),
                        "retryable" to "true"
                    )
                )
            }
            val recovered = messages.toMutableList().also { it[index] = interrupted }
            conversationDao.updateConversation(entity.copy(messagesJson = serializeMessagesToJson(recovered), updatedAt = System.currentTimeMillis()))
        }
    }

    private fun sessionPhase(status: String, delegating: Boolean): SessionPhase = when {
        status == "Approval required" -> SessionPhase.WAITING_APPROVAL
        status == "Completed" -> SessionPhase.COMPLETED
        status in setOf("Failed", "Incomplete") -> SessionPhase.FAILED
        status == "Stopped" -> SessionPhase.STOPPED
        delegating -> SessionPhase.DELEGATING
        status == "Thinking" -> SessionPhase.THINKING
        status.startsWith("Tools:") || status.startsWith("Tool ") -> SessionPhase.TOOL
        status == "Streaming" -> SessionPhase.STREAMING
        else -> SessionPhase.STARTING
    }

    private data class NotificationIdentity(val threadKey: String, val title: String, val sender: String)

    private suspend fun notificationIdentity(state: ChatUiState): NotificationIdentity = when (state.assistantMode) {
        AssistantMode.AGENT -> {
            val groupId = state.ownerId?.toLongOrNull()
            val agentName = state.agentId?.let { agentDao.getById(it)?.name }.orEmpty().ifBlank { "Agent" }
            val groupName = groupId?.let { agentDao.getGroupById(it)?.name }.orEmpty().ifBlank { "Agent group" }
            val convId = state.conversationId?.toLongOrNull()
            val sessionTitle = allLocalConversations.value.firstOrNull { it.id == convId }?.title
                ?: state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(48)
                ?: "Agent session"
            NotificationIdentity("agent-group:${groupId ?: state.conversationId.orEmpty()}", "$sessionTitle • $groupName", agentName)
        }
        AssistantMode.PROJECT -> {
            val projectId = state.ownerId?.toLongOrNull()
            val projectName = projectId?.let { projectDao.getById(it)?.name }.orEmpty().ifBlank { "Project" }
            val convId = state.conversationId?.toLongOrNull()
            val sessionTitle = allLocalConversations.value.firstOrNull { it.id == convId }?.title
                ?: state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(48)
                ?: "Project session"
            NotificationIdentity("project:${projectId ?: state.conversationId.orEmpty()}", "$sessionTitle • $projectName", "AI")
        }
        AssistantMode.CHAT -> NotificationIdentity("chat:${state.conversationId.orEmpty()}", sessionTitle(state), "AI")
    }

    private fun sessionTitle(state: ChatUiState): String = when (state.assistantMode) {
        AssistantMode.CHAT -> state.conversationId?.toLongOrNull()?.let { id ->
            allLocalConversations.value.firstOrNull { it.id == id }?.title
        } ?: "Chat"
        AssistantMode.PROJECT -> state.ownerId?.toLongOrNull()?.let { id ->
            allLocalConversations.value.firstOrNull { it.ownerId == id.toString() && it.assistantMode == AssistantMode.PROJECT.name }?.title
        } ?: "Project"
        AssistantMode.AGENT -> state.agentId?.let { id ->
            allLocalConversations.value.firstOrNull { it.agentId == id }?.title
        } ?: "Agent"
    }

    private fun updateTurnMessage(turn: LocalTurn, transform: (UiMessage) -> UiMessage) {
        val messages = turn.state.messages.toMutableList()
        var index = turn.assistantMessageId?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
        if (index < 0) {
            val message = UiMessage(role = MessageRole.ASSISTANT, content = "", metadata = mapOf("source" to "local"))
            turn.assistantMessageId = message.id
            messages += message
            index = messages.lastIndex
        }
        messages[index] = transform(messages[index])
        turn.state = turn.state.copy(messages = messages)
    }

    private fun finalizeTurnThinking(turn: LocalTurn, status: String) {
        val now = System.currentTimeMillis()
        updateTurnMessage(turn) { message ->
            val duration = message.thinkingStartedAt?.let { (now - it).coerceAtLeast(0L) }
            message.copy(
                isThinking = false,
                thinkingDurationMs = message.thinkingDurationMs ?: duration,
                metadata = message.metadata + mapOf("turnStatus" to status, "completedAt" to now.toString())
            )
        }
    }

    private fun handleTurnEvent(turn: LocalTurn, event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                updateTurnMessage(turn) { message ->
                    val steps = if (message.steps.lastOrNull() is MessageStep.Text) {
                        message.steps.dropLast(1) + (message.steps.last() as MessageStep.Text).let { it.copy(content = it.content + event.text) }
                    } else message.steps + MessageStep.Text(content = event.text)
                    message.copy(content = message.content + event.text, steps = steps)
                }
                publishTurn(turn, "Streaming", event.text.takeLast(120))
            }
            is AgentEvent.ThinkingDelta -> {
                updateTurnMessage(turn) { it.copy(thinking = it.thinking.orEmpty() + event.text, isThinking = true, thinkingStartedAt = it.thinkingStartedAt ?: System.currentTimeMillis()) }
                publishTurn(turn, "Thinking", event.text.takeLast(120))
            }
            is AgentEvent.ToolCallStart -> {
                val displayName = LocalToolMapper.mapDisplayToolName(event.name, event.arguments)
                if (displayName == "delegate_agent") turn.delegationActive = true
                val execution = ToolExecution(event.toolCallId, displayName, LocalToolMapper.mapToolArgs(event.name, event.arguments), status = ToolStatus.RUNNING, metadata = event.metadata + ("source" to "local"), uiMetadata = LocalToolMapper.getUiMetadata(event.name, event.arguments))
                updateTurnMessage(turn) { it.copy(toolExecutions = it.toolExecutions + execution, steps = it.steps + MessageStep.ToolCall(execution = execution)) }
                publishTurn(turn, "Tools: ${LocalToolMapper.displayLabel(event.name, event.arguments)}", toolEventDetail(event.name, event.arguments), urgent = true)
            }
            is AgentEvent.ToolCallResult -> {
                if (LocalToolMapper.mapToolName(event.toolName) == "delegate_agent") turn.delegationActive = false
                fun complete(tool: ToolExecution) = if (tool.toolCallId == event.toolCallId) tool.copy(result = event.result, status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS) else tool
                updateTurnMessage(turn) { message -> message.copy(
                    toolExecutions = message.toolExecutions.map(::complete),
                    steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = complete(it.execution)) else it }
                ) }
                publishTurn(turn, if (event.isError) "Tool failed" else "Tool completed", event.result.takeLast(120), urgent = true)
            }
            is AgentEvent.ResponseItem -> updateTurnMessage(turn) { if (event.json in it.responseItems) it else it.copy(responseItems = it.responseItems + event.json) }
            is AgentEvent.Usage -> turn.state = turn.state.copy(totalInputTokens = turn.state.totalInputTokens + event.inputTokens, totalOutputTokens = turn.state.totalOutputTokens + event.outputTokens)
            is AgentEvent.Incomplete -> {
                finalizeTurnThinking(turn, "incomplete")
                turn.state = turn.state.copy(error = event.reason, isLoading = false, isStreaming = false)
                publishTurn(turn, "Incomplete", event.reason, urgent = true)
            }
            is AgentEvent.Error -> {
                finalizeTurnThinking(turn, "failed")
                turn.state = turn.state.copy(error = event.message, isLoading = false, isStreaming = false)
                publishTurn(turn, "Failed", event.message, urgent = true)
            }
            is AgentEvent.Done -> {
                finalizeTurnThinking(turn, "completed")
                turn.state = turn.state.copy(isLoading = false, isStreaming = false)
                publishTurn(turn, "Completed", turn.state.messages.lastOrNull()?.content.orEmpty().takeLast(120), urgent = true)
            }
            is AgentEvent.SubagentUpdate -> {
                turn.delegateTotal = maxOf(turn.delegateTotal, event.index + 1)
                turn.activeDelegateName = if (event.isComplete) null else event.taskName
                if (event.isComplete) turn.delegateCompleted = minOf(turn.delegateTotal, turn.delegateCompleted + 1)
                fun child(tool: ToolExecution): ToolExecution {
                    if (tool.toolCallId != event.parentToolCallId) return tool
                    val execution = SubagentExecution(event.index, event.taskName, event.prompt, event.result, if (!event.isComplete) ToolStatus.RUNNING else if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS)
                    return tool.copy(children = (tool.children.filterNot { it.index == event.index } + execution).sortedBy { it.index })
                }
                updateTurnMessage(turn) { message -> message.copy(toolExecutions = message.toolExecutions.map(::child), steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = child(it.execution)) else it }) }
                publishTurn(turn, "Delegating", "${turn.delegateCompleted}/${turn.delegateTotal} · ${event.taskName}", urgent = true)
            }
            is AgentEvent.NewIteration -> publishTurn(turn, "Continuing", "Processing tool results")
        }
    }

    private suspend fun persistConversationStart(state: ChatUiState, existingId: Long?): Long? = conversationSaveMutex.withLock {
        val messagesJson = serializeMessagesToJson(state.messages)
        val now = System.currentTimeMillis()
        if (existingId != null) {
            val existing = conversationDao.getConversationById(existingId) ?: return@withLock null
            conversationDao.updateConversation(existing.copy(
                workspacePath = state.workspacePath,
                assistantMode = state.assistantMode.name,
                ownerId = state.ownerId,
                agentId = state.agentId,
                messagesJson = messagesJson,
                updatedAt = now
            ))
            return@withLock existingId
        }
        val title = state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.split("\\s+".toRegex())?.take(5)?.joinToString(" ")?.take(50).orEmpty().ifBlank { "New Conversation" }
        conversationDao.insertConversation(ConversationEntity(
            title = title,
            workspacePath = state.workspacePath,
            assistantMode = state.assistantMode.name,
            ownerId = state.ownerId,
            agentId = state.agentId,
            messagesJson = messagesJson,
            createdAt = now,
            updatedAt = now
        ))
    }

    private suspend fun persistTurn(turn: LocalTurn) = conversationSaveMutex.withLock {
        val existing = conversationDao.getConversationById(turn.conversationId) ?: return@withLock
        conversationDao.updateConversation(existing.copy(messagesJson = serializeMessagesToJson(turn.state.messages), updatedAt = System.currentTimeMillis()))
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                currentConversationId?.let { updateRunningSession(it, "Streaming", event.text.takeLast(120)) }
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                browserSessionManager.onAssistantTextDelta(event.text)
                bufferAssistantTextDelta(event.text)
            }
            is AgentEvent.ThinkingDelta -> {
                currentConversationId?.let { updateRunningSession(it, "Thinking", event.text.takeLast(120)) }
                bufferAssistantThinkingDelta(event.text)
            }
            is AgentEvent.ToolCallStart -> {
                currentConversationId?.let { updateRunningSession(it, "Using ${LocalToolMapper.mapDisplayToolName(event.name, event.arguments)}", toolEventDetail(event.name, event.arguments)) }
                flushAssistantThinkingBuffer()
                finalizeThinkingIfActive()
                flushAssistantTextBuffer()
                val normalizedName = LocalToolMapper.mapDisplayToolName(event.name, event.arguments)
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
        if (turnsById[turnId] == null) return false
        val approvalId = "$turnId:$toolCallId"
        pendingConfirmationUi[toolCallId] = request
        pendingApprovalIds[toolCallId] = approvalId
        return try {
            pendingToolConfirmations.await(approvalId, turnId) {
                if (turnsById[turnId] == null) return@await
                updateTurnToolExecution(turnsById[turnId]!!, toolCallId) { tool ->
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

    private fun updateRunningSession(conversationId: Long, status: String, detail: String) {
        val state = _uiState.value
        val title = _conversations.value.firstOrNull { it.id == conversationId }?.title
            ?: state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(48)
            ?: "AI session"
        val item = RunningSession(conversationId, title, state.assistantMode, state.ownerId, state.agentId, status, detail)
        _runningSessions.update { current -> current.filterNot { it.conversationId == conversationId } + item }
    }

    private fun removeRunningSession(conversationId: Long) {
        _runningSessions.update { current -> current.filterNot { it.conversationId == conversationId } }
    }

    private fun toolEventDetail(name: String, arguments: Map<String, Any?>): String =
        listOf("path", "query", "command", "task", "url").firstNotNullOfOrNull { key ->
            arguments[key]?.toString()?.takeIf(String::isNotBlank)?.let { "$key: ${it.take(100)}" }
        } ?: name

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
        currentConversationId?.let { conversationId ->
            activeTurns[conversationId]?.let { turn ->
                pendingToolConfirmations.cancel(turn.turnId)
                turn.job?.cancel()
            }
            return
        }
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
        targetEpoch.incrementAndGet()
        resetVisibleConversation()
    }

    private fun resetVisibleConversation() {
        chatJob = null
        assistantFlushJob?.cancel()
        assistantFlushJob = null
        thinkingFlushJob?.cancel()
        thinkingFlushJob = null
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
            isLoadingHistory = false,
            isStreaming = false,
            totalInputTokens = 0,
            totalOutputTokens = 0
        )}
    }

    override fun loadConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        val epoch = targetEpoch.incrementAndGet()
        currentConversationId = longId
        activeTurns[longId]?.let { running ->
            _uiState.value = running.state.copy(isLoading = true, isStreaming = true)
            return
        }
        _uiState.update { state ->
            state.copy(
                conversationId = longId.toString(),
                messages = emptyList(),
                isLoading = false,
                isLoadingHistory = true,
                isStreaming = false,
                error = null,
                totalInputTokens = 0,
                totalOutputTokens = 0
            )
        }
        scope.launch {
            val entity = conversationDao.getConversationById(longId)
            if (targetEpoch.get() != epoch || currentConversationId != longId) return@launch
            activeTurns[longId]?.let { running ->
                _uiState.value = running.state.copy(isLoading = true, isStreaming = true)
                return@launch
            }
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
                        assistantMode = runCatching { AssistantMode.valueOf(conv.assistantMode) }.getOrDefault(AssistantMode.forWorkspace(conv.workspacePath)),
                    ownerId = conv.ownerId,
                    agentId = conv.agentId,
                    messages = messages,
                    totalInputTokens = 0,
                    totalOutputTokens = 0,
                    error = null,
                    isLoading = false,
                    isLoadingHistory = false,
                    isStreaming = false
                )}
            } ?: _uiState.update { it.copy(isLoading = false, isLoadingHistory = false, isStreaming = false, error = "Conversation not found") }
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

    private fun applyModelToUi(modelKey: String) {
        val parts = modelKey.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != "model") return
        val settings = settingsManager.getSettings()
        val model = settings.connections.firstOrNull { it.id == parts[1] }?.visibleModels?.firstOrNull { it.id == parts[2] } ?: return
        _uiState.update {
            it.copy(
                activeModelKey = modelKey,
                selectedModel = model.id,
                effort = settingsManager.getThinkingEffort(parts[1], parts[2])
            )
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
        // Workspace browsing must never change Chat/Project/Agent ownership.
        _uiState.update { state ->
            state.copy(workspacePath = path?.takeIf(String::isNotBlank) ?: state.workspacePath)
        }
    }

    override fun setAssistantOwner(mode: AssistantMode, ownerId: String?, workspacePath: String?, agentId: Long?) {
        val epoch = targetEpoch.incrementAndGet()
        resetVisibleConversation()
        _uiState.update {
            it.copy(
                assistantMode = mode,
                ownerId = ownerId,
                agentId = if (mode == AssistantMode.AGENT) agentId else null,
                workspacePath = if (mode == AssistantMode.CHAT) null else workspacePath
            )
        }
        if (mode == AssistantMode.AGENT && agentId != null) {
            val targetOwnerId = ownerId
            scope.launch {
                val active = agentDao.getById(agentId)?.defaultModelKeysJson.orEmpty()
                    .let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                    ?.let { array -> (0 until array.length()).map { array.optString(it) }.firstOrNull() }
                val selected = active ?: settingsManager.getSettings().activeSelection?.key
                val conversation = conversationDao.getAgentConversation(agentId)
                val state = _uiState.value
                if (targetEpoch.get() != epoch || state.assistantMode != AssistantMode.AGENT || state.agentId != agentId || state.ownerId != targetOwnerId) return@launch
                if (selected != null) applyModelToUi(selected)
                conversation?.let { loadConversation(it.id.toString()) }
            }
        }
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
        val turnId = executionId.substringBefore(':', "").toLongOrNull() ?: return
        val toolCallId = executionId.substringAfter(':', executionId)
        pendingToolConfirmations.resolve(executionId, turnId, confirmed) {
            pendingConfirmationUi.remove(toolCallId)
            pendingApprovalIds.remove(toolCallId, executionId)
            turnsById[turnId]?.let { turn ->
                updateTurnToolExecution(turn, toolCallId) { tool ->
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
    }

    private fun updateTurnToolExecution(turn: LocalTurn, toolCallId: String, transform: (ToolExecution) -> ToolExecution) {
        updateTurnMessage(turn) { message ->
            message.copy(
                toolExecutions = message.toolExecutions.map { if (it.toolCallId == toolCallId) transform(it) else it },
                steps = message.steps.map { if (it is MessageStep.ToolCall && it.execution.toolCallId == toolCallId) it.copy(execution = transform(it.execution)) else it }
            )
        }
        val execution = turn.state.messages.asReversed().asSequence()
            .flatMap { it.toolExecutions.asReversed().asSequence() }
            .firstOrNull { it.toolCallId == toolCallId }
        val pending = execution?.metadata?.get("approvalState") == "pending"
        publishTurn(
            turn,
            if (pending) "Approval required" else turn.lastStatus,
            if (pending) execution?.let { "Tools: ${LocalToolMapper.displayLabel(it.name, it.arguments)}" } ?: "Waiting for your decision" else turn.lastDetail,
            urgent = true
        )
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
                        formattedContent = obj.optString("formattedContent").takeIf { it.isNotBlank() },
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
                            workspacePath = _uiState.value.workspacePath,
                            assistantMode = _uiState.value.assistantMode.name,
                            ownerId = _uiState.value.ownerId,
                            agentId = _uiState.value.agentId,
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
        val agentId = _uiState.value.agentId.takeIf { _uiState.value.assistantMode == AssistantMode.AGENT }
        if (agentId != null) {
            conversationDao.getAgentConversation(agentId)?.let { existing ->
                conversationDao.updateConversation(
                    existing.copy(
                        title = title,
                        workspacePath = _uiState.value.workspacePath,
                        ownerId = _uiState.value.ownerId,
                        messagesJson = messagesJson,
                        updatedAt = now
                    )
                )
                currentConversationId = existing.id
                _uiState.update { it.copy(conversationId = existing.id.toString()) }
                return existing.id
            }
        }
        val newId = conversationDao.insertConversation(
            ConversationEntity(
                id = 0,
                title = title,
                workspacePath = _uiState.value.workspacePath,
                assistantMode = _uiState.value.assistantMode.name,
                ownerId = _uiState.value.ownerId,
                agentId = _uiState.value.agentId,
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
                msg.formattedContent?.let { put("formattedContent", it) }
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
