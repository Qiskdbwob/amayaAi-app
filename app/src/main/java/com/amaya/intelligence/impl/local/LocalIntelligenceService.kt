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
import com.amaya.intelligence.data.repository.SessionMemoryRepository

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
import com.amaya.intelligence.tools.SubagentResult
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
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val ledgerStore: com.amaya.intelligence.data.repository.ActiveContextLedgerStore,
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
    private var compactJob: Job? = null
    @Volatile private var currentConversationId: Long? = null
    private var currentAssistantMessageId: String? = null
    private val assistantTextBuffer = StringBuilder()
    private val assistantThinkingBuffer = StringBuilder()
    private var lastAssistantTextUiEmitAt = 0L
    private var assistantFlushJob: Job? = null
    private var thinkingFlushJob: Job? = null
    private val conversationSaveMutex = Mutex()
    private val conversationStartMutex = Mutex()
    private val pendingToolConfirmations = ToolConfirmationRegistry()
    private val pendingConfirmationUi = ConcurrentHashMap<String, ConfirmationRequest>()
    private val pendingApprovalIds = ConcurrentHashMap<String, String>()
    private val titleJobs = ConcurrentHashMap<Long, Job>()
    private val nextTurnId = AtomicLong(0L)
    private val targetEpoch = AtomicLong(0L)
    private val activeTurns = ConcurrentHashMap<Long, LocalTurn>()
    private val startingConversations = ConcurrentHashMap<Long, Long>()
    private val startingNewTurnId = AtomicLong(0L)
    private val stoppingConversations = ConcurrentHashMap.newKeySet<Long>()
    private val turnsById = ConcurrentHashMap<Long, LocalTurn>()
    private data class PendingMessage(
        val content: String,
        val images: List<com.amaya.intelligence.data.remote.api.ChatImage>
    )
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
        var activeDelegateName: String? = null,
        var pendingMessage: PendingMessage? = null,
        /**
         * Streamed assistant text not yet folded into canonicalHistory. Buffered so the hot path
         * costs an append instead of re-parsing and re-serializing the whole accumulated response
         * on every delta, on the main thread.
         */
        val pendingCanonicalText: StringBuilder = StringBuilder()
    )

    /** Take the buffered assistant text, if any, so it can be committed before the next entry. */
    private fun LocalTurn.drainCanonicalText(): String? {
        if (pendingCanonicalText.isEmpty()) return null
        val text = pendingCanonicalText.toString()
        pendingCanonicalText.setLength(0)
        return text
    }

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
                    connection.visibleModels.mapNotNull { model ->
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
        val contextMessages = parseMessagesFromJson(entity.contextMessagesJson.ifBlank { entity.messagesJson }).getOrNull() ?: return false
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
            contextMessages = contextMessages,
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

    suspend fun runDelegatedAgentTurn(conversationId: Long, request: String): SubagentResult {
        val entity = conversationDao.getConversationById(conversationId) ?: error("Delegated conversation not found")
        val messages = parseMessagesFromJson(entity.messagesJson).getOrThrow()
        val contextMessages = parseMessagesFromJson(entity.contextMessagesJson.ifBlank { entity.messagesJson }).getOrThrow()
        require(contextMessages.lastOrNull()?.role == MessageRole.USER) { "Delegated request is missing" }
        val settings = settingsManager.getSettings()
        val modelKey = entity.agentId?.let { agentDao.getById(it)?.defaultModelKeysJson }
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { values -> (0 until values.length()).map(values::optString).firstOrNull(String::isNotBlank) }
            ?: settings.activeSelection?.key.orEmpty()
        val modelParts = modelKey.split('|', limit = 3)
        val state = ChatUiState(
            messages = messages,
            contextMessages = contextMessages,
            selectedModel = modelParts.getOrNull(2).orEmpty().ifBlank { settings.activeSelection?.modelId.orEmpty() },
            workspacePath = entity.workspacePath,
            assistantMode = AssistantMode.AGENT,
            ownerId = entity.ownerId,
            agentId = entity.agentId,
            modelOptions = _uiState.value.modelOptions,
            activeModelKey = modelKey,
            conversationId = entity.id.toString(),
            effort = if (modelParts.size == 3) settingsManager.getThinkingEffort(modelParts[1], modelParts[2]) else _uiState.value.effort,
            sessionMode = IntelligenceSessionManager.SessionMode.LOCAL
        )
        check(startTurn(request, emptyList(), state, projectVisible = false, preexistingUserMessage = true)) {
            "Delegated session is already streaming"
        }
        val turn = activeTurns[conversationId] ?: error("Delegated session did not start")
        turn.job?.join()
        val persisted = conversationDao.getConversationById(conversationId)
            ?.let { parseMessagesFromJson(it.messagesJson).getOrNull() }
            .orEmpty()
        val turnMessages = persisted.drop(messages.size).flatMap { it.toChatMessages() }
        val completed = persisted.lastOrNull { message -> message.role == MessageRole.ASSISTANT }
        val summary = completed?.content?.takeIf(String::isNotBlank)
            ?: turn.state.error?.let { "[ERROR] $it" }
            ?: "[INCOMPLETE] No final response."
        return SubagentResult(
            taskName = entity.title,
            summary = summary,
            turnMessages = turnMessages,
            startedAt = completed?.timestamp ?: System.currentTimeMillis(),
            completedAt = System.currentTimeMillis()
        )
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
            startTurn(content, images, currentState, projectVisible = true)
        }
    }

    private suspend fun startTurn(
        content: String,
        images: List<com.amaya.intelligence.data.remote.api.ChatImage>,
        initialState: ChatUiState,
        projectVisible: Boolean,
        preexistingUserMessage: Boolean = false
    ): Boolean {
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank() && images.isEmpty()) return false
        val selectedEpoch = targetEpoch.get()
        val activeConversation = initialState.conversationId?.toLongOrNull()
        val turnId = nextTurnId.incrementAndGet()
        if (activeConversation != null) {
            activeTurns[activeConversation]?.let { running ->
                if (projectVisible) {
                    running.pendingMessage = PendingMessage(content, images)
                    stoppingConversations.add(activeConversation)
                    pendingToolConfirmations.cancel(running.turnId)
                    running.job?.cancel()
                    _uiState.update { it.copy(error = null) }
                }
                return false
            }
            if (startingConversations.putIfAbsent(activeConversation, turnId) != null) {
                if (projectVisible) _uiState.update { it.copy(error = "This session is already streaming") }
                return false
            }
        } else if (!startingNewTurnId.compareAndSet(0L, turnId)) {
            if (projectVisible) _uiState.update { it.copy(error = "This session is already streaming") }
            return false
        }
        val userMsg = UiMessage(
            role = MessageRole.USER,
            content = trimmedContent,
            attachments = images.map { MessageAttachment(it.mediaType, it.base64, it.fileName) }
        )
        val turnState = initialState.copy(
            messages = if (preexistingUserMessage) initialState.messages else initialState.messages + userMsg,
            contextMessages = if (preexistingUserMessage) initialState.contextMessages else initialState.contextMessages + userMsg,
            isLoading = true,
            isStreaming = true,
            error = null
        )
        if (projectVisible) _uiState.value = turnState
        val isNewConversation = activeConversation == null
        val conversationId = try {
            conversationStartMutex.withLock {
                // A submitted prompt owns its captured target even if the user navigates before Room returns.
                persistConversationStart(turnState, activeConversation)
            }
        } catch (error: Exception) {
            if (projectVisible && targetEpoch.get() == selectedEpoch) {
                _uiState.update { it.copy(error = "Could not save this conversation: ${error.message.orEmpty()}", isLoading = false, isStreaming = false) }
            }
            null
        }
        val ownsStart = activeConversation?.let { startingConversations[it] == turnId }
            ?: (startingNewTurnId.get() == turnId)
        if (conversationId == null || !ownsStart) {
            activeConversation?.let { startingConversations.remove(it, turnId) }
                ?: startingNewTurnId.compareAndSet(turnId, 0L)
            return false
        }
        val runtimeState = turnState.copy(conversationId = conversationId.toString())
        if (projectVisible && targetEpoch.get() == selectedEpoch) {
            currentConversationId = conversationId
            if (runtimeState.assistantMode == AssistantMode.AGENT && runtimeState.agentId != null) {
                browserSessionManager.selectConversation("conversation:$conversationId", runtimeState.agentId)
                browserSessionManager.onAssistantStreamingChanged(true)
            }
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
        LocalStreamPerfLog.startTurn(
            messageChars = trimmedContent.length,
            historyMessages = runtimeState.contextMessages.size,
            model = runtimeState.selectedModel
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
                var completed = false
                var failed = false
                try {
                    aiRepository.chat(
                        message = trimmedContent,
                        userImages = images,
                        conversationHistory = runtimeState.contextMessages.dropLast(1).flatMap { it.toChatMessages() },
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
                    handleTurnEvent(turn, AgentEvent.Incomplete("Stopped", retryable = true))
                    // Stop is an intentional terminal state, not a retryable provider failure.
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    handleTurnEvent(turn, AgentEvent.Error(error.message.orEmpty().ifBlank { "AI session failed" }, retryable = true))
                } finally {
                    withContext(NonCancellable) {
                        turn.state = turn.state.copy(isLoading = false, isStreaming = false, isAutoCompacting = false)
                        try {
                            persistTurn(turn)
                        } finally {
                            activeTurns.remove(conversationId, turn)
                            turnsById.remove(turn.turnId, turn)
                            removeRunningSession(conversationId)
                            val pendingMessage = if (stoppingConversations.remove(conversationId)) turn.pendingMessage else null
                            val isVisible = currentConversationId == conversationId
                            if (isVisible) {
                                browserSessionManager.onAssistantStreamingChanged(false)
                                // Carry the trimmed model context back into the UI state: the next
                                // turn seeds itself from here, and without this it would write the
                                // untrimmed list straight back over what persistTurn just stored.
                                _uiState.update {
                                    it.copy(
                                        contextMessages = turn.state.contextMessages,
                                        isLoading = false,
                                        isStreaming = false,
                                        isAutoCompacting = false
                                    )
                                }
                            }
                            pendingMessage?.let { pending ->
                                scope.launch {
                                    startTurn(
                                        content = pending.content,
                                        images = pending.images,
                                        initialState = turn.state,
                                        projectVisible = isVisible
                                    )
                                }
                            }
                        }
                    }
                }
            }
        turn.job = job
        activeTurns[conversationId] = turn
        activeConversation?.let { startingConversations.remove(it, turnId) }
            ?: startingNewTurnId.compareAndSet(turnId, 0L)
        turnsById[turnId] = turn
        com.amaya.intelligence.service.AiSessionNotificationService.start(appContext)
        publishTurn(turn, "Streaming", "Waiting for response")
        job.start()
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
        conversationDao.getAllConversations().first().forEach { summary ->
            val entity = conversationDao.getConversationById(summary.id) ?: return@forEach
            val messages = parseMessagesFromJson(entity.messagesJson).getOrNull() ?: return@forEach
            val recovered = markInterruptedTurn(messages) ?: return@forEach
            // The two columns are recovered independently. Rebuilding the model context from the
            // visible transcript used to silently undo manual compaction and a cleared history.
            val storedContext = parseMessagesFromJson(entity.contextMessagesJson).getOrNull()
            val recoveredContext = storedContext?.let { markInterruptedTurn(it) ?: it } ?: recovered
            conversationDao.updateConversation(entity.copy(
                messagesJson = serializeMessagesToJson(recovered),
                contextMessagesJson = serializeMessagesToJson(recoveredContext),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    private fun sessionPhase(status: String, delegating: Boolean): SessionPhase = when {
        status == "Approval required" -> SessionPhase.WAITING_APPROVAL
        status == "Compacting" -> SessionPhase.COMPACTING
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
        val context = turn.state.contextMessages.toMutableList()
        val visible = turn.state.messages.toMutableList()
        var index = turn.assistantMessageId?.let { id -> context.indexOfLast { it.id == id } } ?: -1
        if (index < 0) {
            val message = UiMessage(role = MessageRole.ASSISTANT, content = "", metadata = mapOf("source" to "local"))
            turn.assistantMessageId = message.id
            context += message
            if (visible.isNotEmpty()) visible += message
            index = context.lastIndex
        }
        val updated = transform(context[index])
        context[index] = updated
        turn.assistantMessageId?.let { id ->
            visible.indexOfLast { it.id == id }.takeIf { it >= 0 }?.let { visibleIndex ->
                visible[visibleIndex] = updated
            }
        }
        turn.state = turn.state.copy(messages = visible, contextMessages = context)
    }

    private fun markActiveTurnToolsStopped(turn: LocalTurn, reason: String = "Stopped by user") {
        updateTurnMessage(turn) { message ->
            fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
                tool.copy(status = ToolStatus.ERROR, result = tool.result ?: reason)
            } else tool
            message.copy(
                toolExecutions = message.toolExecutions.map(::stop),
                steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = stop(it.execution)) else it }
            )
        }
    }

    private fun finalizeTurnThinking(turn: LocalTurn, status: String) {
        val now = System.currentTimeMillis()
        // Commit any assistant prose still buffered, so the persisted model context ends with the
        // text the user actually saw.
        val pendingText = turn.drainCanonicalText()
        LocalStreamPerfLog.endTurn(
            reason = status,
            totalMessages = turn.state.messages.size,
            assistantChars = turn.state.messages.lastOrNull()?.content?.length ?: 0
        )
        updateTurnMessage(turn) { message ->
            val steps = message.steps.finishThinking(now)
            message.copy(
                canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText),
                isThinking = false,
                thinkingDurationMs = message.thinkingDurationMs
                    ?: (steps.lastOrNull { it is MessageStep.Thinking } as? MessageStep.Thinking)?.durationMs,
                steps = steps,
                metadata = message.metadata + mapOf("turnStatus" to status, "completedAt" to now.toString())
            )
        }
    }

    private fun handleTurnEvent(turn: LocalTurn, event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                LocalStreamPerfLog.onFirstToken()
                if (currentConversationId == turn.conversationId && turn.state.assistantMode == AssistantMode.AGENT) {
                    browserSessionManager.onAssistantTextDelta(event.text)
                }
                turn.pendingCanonicalText.append(event.text)
                updateTurnMessage(turn) { message ->
                    val steps = message.steps.finishThinking().appendText(event.text)
                    message.copy(
                        content = message.content + event.text,
                        isThinking = false,
                        steps = steps
                    )
                }
                publishTurn(turn, "Streaming", event.text.takeLast(120))
            }
            is AgentEvent.ThinkingDelta -> {
                updateTurnMessage(turn) { message -> message.appendThinkingStep(event.text) }
                publishTurn(turn, "Thinking", event.text.takeLast(120))
            }
            is AgentEvent.ToolCallStart -> {
                val displayName = LocalToolMapper.mapDisplayToolName(event.name, event.arguments)
                if (displayName == "delegate_agent") turn.delegationActive = true
                val execution = ToolExecution(event.toolCallId, displayName, LocalToolMapper.mapToolArgs(event.name, event.arguments), status = ToolStatus.RUNNING, metadata = event.metadata + ("source" to "local"), uiMetadata = LocalToolMapper.getUiMetadata(event.name, event.arguments))
                // Record the provider's own tool name and arguments, not the display-mapped ones:
                // this list is replayed as model context on later turns.
                val canonicalCall = JSONObject()
                    .put("kind", "assistant_tool_call")
                    .put("id", event.toolCallId)
                    .put("name", event.name)
                    .put("arguments", JSONObject(event.arguments))
                    .put("metadata", JSONObject(event.metadata))
                    .toString()
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) { message ->
                    val steps = message.steps.finishThinking()
                    message.finishThinking().copy(
                        toolExecutions = message.toolExecutions + execution,
                        steps = steps + MessageStep.ToolCall(execution = execution),
                        canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalCall
                    )
                }
                publishTurn(turn, "Tools: ${LocalToolMapper.displayLabel(event.name, event.arguments)}", toolEventDetail(event.name, event.arguments), urgent = true)
            }
            is AgentEvent.ToolCallResult -> {
                if (LocalToolMapper.mapToolName(event.toolName) == "delegate_agent") turn.delegationActive = false
                fun complete(tool: ToolExecution) = if (tool.toolCallId == event.toolCallId) tool.copy(result = event.result, status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS) else tool
                val canonicalResult = JSONObject()
                    .put("kind", "tool_result")
                    .put("id", event.toolCallId)
                    .put("name", event.toolName)
                    .put("result", event.result)
                    .put("isError", event.isError)
                    .toString()
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) { message -> message.finishThinking().copy(
                    toolExecutions = message.toolExecutions.map(::complete),
                    steps = message.steps.finishThinking().map { if (it is MessageStep.ToolCall) it.copy(execution = complete(it.execution)) else it },
                    canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalResult
                ) }
                publishTurn(turn, if (event.isError) "Tool failed" else "Tool completed", event.result.takeLast(120), urgent = true)
            }
            is AgentEvent.ResponseItem -> {
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) {
                    // The buffered prose is committed even when the response item is a duplicate —
                    // draining it and then returning the message unchanged would lose that run.
                    if (event.json in it.responseItems) {
                        it.copy(canonicalHistory = it.canonicalHistory.appendCanonicalText(pendingText))
                    } else it.copy(
                        responseItems = it.responseItems + event.json,
                        canonicalHistory = it.canonicalHistory.appendCanonicalText(pendingText) + JSONObject()
                            .put("kind", "response_item")
                            .put("item", JSONObject(event.json))
                            .toString()
                    )
                }
            }
            is AgentEvent.Usage -> turn.state = turn.state.copy(totalInputTokens = turn.state.totalInputTokens + event.inputTokens, totalOutputTokens = turn.state.totalOutputTokens + event.outputTokens)
            is AgentEvent.Incomplete -> {
                val stopped = event.reason == "Stopped"
                // Repaired on every terminal path, not just an explicit stop: a tool left RUNNING
                // replays as a tool_call with no result and makes the next request invalid.
                markActiveTurnToolsStopped(turn, if (stopped) "Stopped by user" else "Interrupted: ${event.reason}")
                finalizeTurnThinking(turn, if (stopped) "cancelled" else "incomplete")
                turn.state = turn.state.copy(error = if (stopped) null else event.reason, isLoading = false, isStreaming = false, isAutoCompacting = false)
                publishTurn(turn, if (stopped) "Stopped" else "Incomplete", event.reason, urgent = true)
            }
            is AgentEvent.Error -> {
                markActiveTurnToolsStopped(turn, "Interrupted: ${event.message}")
                finalizeTurnThinking(turn, "failed")
                turn.state = turn.state.copy(error = event.message, isLoading = false, isStreaming = false, isAutoCompacting = false)
                publishTurn(turn, "Failed", event.message, urgent = true)
            }
            is AgentEvent.Done -> {
                finalizeTurnThinking(turn, "completed")
                turn.state = turn.state.copy(isLoading = false, isStreaming = false, isAutoCompacting = false)
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
            is AgentEvent.Compacting -> {
                LocalStreamPerfLog.onCompactionStart(event.evictedMessages, event.evictedTokens)
                turn.state = turn.state.copy(isAutoCompacting = true)
                publishTurn(turn, "Compacting", "Compressing ${event.evictedMessages} older messages", urgent = true)
            }
            is AgentEvent.Compacted -> {
                LocalStreamPerfLog.onCompactionEnd(event.usedFallback, event.reclaimedTokens)
                // Fold the ledger into the model context so the next turn inherits it instead of
                // re-deriving it. Only the previous automatic record is replaced — a summary the
                // user asked for with /compact survives. The visible transcript is left untouched.
                // Match on the content prefix as well as the tag, so conversations stored before the
                // two sources were told apart cannot accumulate one ledger per turn.
                // The legacy content match is restricted to actual compaction records. Matching on
                // content alone would delete any real message whose text happens to start with the
                // marker — including a user request quoting one.
                val retained = turn.state.contextMessages.filterNot {
                    it.metadata["compactionSource"] == "auto" ||
                        (it.role == MessageRole.SYSTEM &&
                            it.metadata["compressed"] == "true" &&
                            it.content.startsWith(AUTO_COMPACTED_CONTEXT_PREFIX))
                }
                turn.state = turn.state.copy(
                    isAutoCompacting = false,
                    contextMessages = compressedSessionContext(event.ledger, auto = true) + retained
                )
                publishTurn(
                    turn,
                    "Streaming",
                    if (event.usedFallback) "Context compacted locally" else "Context compacted",
                    urgent = true
                )
            }
        }
    }

    private suspend fun persistConversationStart(state: ChatUiState, existingId: Long?): Long? = conversationSaveMutex.withLock {
        val messagesJson = serializeMessagesToJson(state.messages)
        val contextMessagesJson = serializeMessagesToJson(state.contextMessages)
        val now = System.currentTimeMillis()
        if (existingId != null) {
            val existing = conversationDao.getConversationById(existingId) ?: return@withLock null
            conversationDao.updateConversation(existing.copy(
                workspacePath = state.workspacePath,
                assistantMode = state.assistantMode.name,
                ownerId = state.ownerId,
                agentId = state.agentId,
                messagesJson = messagesJson,
                contextMessagesJson = contextMessagesJson,
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
            contextMessagesJson = contextMessagesJson,
            createdAt = now,
            updatedAt = now
        ))
    }

    private suspend fun persistTurn(turn: LocalTurn) = conversationSaveMutex.withLock {
        val existing = conversationDao.getConversationById(turn.conversationId) ?: return@withLock
        // The transcript keeps everything; the model-context column sheds tool payload it will never
        // replay, so a long tool-heavy conversation stops growing without bound on disk.
        val storedContext = digestOldToolPayloads(turn.state.contextMessages)
        turn.state = turn.state.copy(contextMessages = storedContext)
        conversationDao.updateConversation(existing.copy(
            messagesJson = serializeMessagesToJson(turn.state.messages),
            contextMessagesJson = serializeMessagesToJson(storedContext),
            updatedAt = System.currentTimeMillis()
        ))
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
        updateCurrentAssistantMessage { it.appendThinkingStep(chunk) }
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
            val newSteps = msg.steps.finishThinking().appendText(chunk)
            totalAssistantChars = newContent.length
            stepCount = newSteps.size
            msg.finishThinking().copy(
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
        updateCurrentAssistantMessage { it.finishThinking() }
    }

    private fun UiMessage.appendThinkingStep(delta: String): UiMessage {
        val updatedSteps = steps.appendThinking(delta)
        val current = updatedSteps.last() as MessageStep.Thinking
        return copy(
            thinking = current.text,
            isThinking = true,
            thinkingStartedAt = current.startedAt,
            steps = updatedSteps
        )
    }

    private fun UiMessage.finishThinking(nowMs: Long = System.currentTimeMillis()): UiMessage {
        val finishedSteps = steps.finishThinking(nowMs)
        if (!isThinking && thinkingDurationMs != null && finishedSteps === steps) return this
        val durationMs = thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
        return copy(isThinking = false, thinkingDurationMs = thinkingDurationMs ?: durationMs, steps = finishedSteps)
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
                stoppingConversations.add(conversationId)
                pendingToolConfirmations.cancel(turn.turnId)
                turn.job?.cancel()
                return
            }
            startingConversations.remove(conversationId)
        } ?: startingNewTurnId.set(0L)
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
        _uiState.update { it.copy(isLoading = false, isStreaming = false, isAutoCompacting = false) }
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
        browserSessionManager.clearSessionState()
        _uiState.update { it.copy(
            conversationId = null,
            messages = emptyList(),
            contextMessages = emptyList(),
            error = null,
            isLoading = false,
            isLoadingHistory = false,
            isStreaming = false,
            isAutoCompacting = false,
            isCompressing = false,
            totalInputTokens = 0,
            totalOutputTokens = 0
        )}
    }

    override fun loadConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        val epoch = targetEpoch.incrementAndGet()
        currentConversationId = longId
        activeTurns[longId]?.let { running ->
            if (running.state.assistantMode == AssistantMode.AGENT) {
                browserSessionManager.selectConversation("conversation:$longId", running.state.agentId)
                browserSessionManager.onAssistantStreamingChanged(true)
            }
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
                isAutoCompacting = false,
                error = null,
                totalInputTokens = 0,
                totalOutputTokens = 0
            )
        }
        scope.launch {
            val entity = conversationDao.getConversationById(longId)
            if (targetEpoch.get() != epoch || currentConversationId != longId) return@launch
            activeTurns[longId]?.let { running ->
                if (running.state.assistantMode == AssistantMode.AGENT) {
                    browserSessionManager.selectConversation("conversation:$longId", running.state.agentId)
                    browserSessionManager.onAssistantStreamingChanged(true)
                }
                _uiState.value = running.state.copy(isLoading = true, isStreaming = true)
                return@launch
            }
            entity?.let { conv ->
                val parsed = parseMessagesFromJson(conv.messagesJson)
                val messages = parsed.getOrElse {
                    _uiState.update { state -> state.copy(error = "Conversation data is corrupted and could not be loaded") }
                    return@launch
                }
                val contextMessages = parseMessagesFromJson(conv.contextMessagesJson.ifBlank { conv.messagesJson })
                    .getOrElse {
                        _uiState.update { state -> state.copy(error = "Conversation context is corrupted and could not be loaded") }
                        return@launch
                    }
                currentConversationId = conv.id
                currentAssistantMessageId = null
                assistantTextBuffer.clear()
                assistantThinkingBuffer.clear()
                lastAssistantTextUiEmitAt = 0L
                browserSessionManager.selectConversation("conversation:${conv.id}", conv.agentId)
                _uiState.update { it.copy(
                    conversationId = conv.id.toString(),
                    workspacePath = conv.workspacePath,
                        assistantMode = runCatching { AssistantMode.valueOf(conv.assistantMode) }.getOrDefault(AssistantMode.forWorkspace(conv.workspacePath)),
                    ownerId = conv.ownerId,
                    agentId = conv.agentId,
                    messages = messages,
                    contextMessages = contextMessages,
                    totalInputTokens = 0,
                    totalOutputTokens = 0,
                    error = null,
                    isLoading = false,
                    isLoadingHistory = false,
                    isStreaming = false,
                    isAutoCompacting = false
                )}
            } ?: _uiState.update { it.copy(isLoading = false, isLoadingHistory = false, isStreaming = false, error = "Conversation not found") }
        }
    }

    override fun deleteConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        titleJobs.remove(longId)?.cancel()
        if (currentConversationId == longId) clearConversation()
        // The compaction ledger describes a conversation that is about to stop existing.
        ledgerStore.invalidate(id)
        scope.launch {
            conversationDao.deleteConversationById(longId)
            runCatching { sessionMemoryRepository.deleteSession(id) }
        }
    }

    override fun clearVisibleHistory(deleteContext: Boolean) {
        val conversationId = currentConversationId ?: return
        if (activeTurns.containsKey(conversationId)) {
            _uiState.update { it.copy(error = "Wait for the current response before clearing this chat") }
            return
        }
        val updatedContext = contextAfterHistoryClear(_uiState.value.contextMessages, deleteContext)
        // A cached ledger describes context that no longer exists.
        if (deleteContext) ledgerStore.invalidate(conversationId.toString())
        _uiState.update { it.copy(messages = emptyList(), contextMessages = updatedContext) }
        scope.launch {
            try {
                conversationSaveMutex.withLock {
                    conversationDao.clearConversationHistory(
                        id = conversationId,
                        contextMessagesJson = serializeMessagesToJson(updatedContext)
                    )
                }
                if (deleteContext) sessionMemoryRepository.deleteSession(conversationId.toString())
            } catch (error: Exception) {
                _uiState.update { it.copy(error = "Could not clear this chat: ${error.message.orEmpty()}") }
            }
        }
    }

    override fun compactConversation(focus: String) {
        val conversationId = currentConversationId ?: run {
            _uiState.update { it.copy(error = "Nothing to compress") }
            return
        }
        if (activeTurns.containsKey(conversationId) || compactJob?.isActive == true) {
            _uiState.update { it.copy(error = "Wait for the current operation before compressing") }
            return
        }
        val state = _uiState.value
        _uiState.update { it.copy(isCompressing = true, error = null) }
        compactJob = scope.launch {
            try {
                val history = state.contextMessages.flatMap { it.toChatMessages() }
                val summary = aiRepository.compressConversation(
                    conversationHistory = history,
                    selectedModel = state.selectedModel,
                    connectionId = state.activeModelKey.takeIf { it.startsWith("model|") }?.split('|', limit = 3)?.getOrNull(1),
                    focus = focus
                ).getOrThrow()
                val context = compressedSessionContext(summary)
                // The manual summary supersedes whatever the automatic ledger had accumulated.
                ledgerStore.invalidate(conversationId.toString())
                conversationSaveMutex.withLock {
                    conversationDao.updateConversationContext(
                        id = conversationId,
                        contextMessagesJson = serializeMessagesToJson(context)
                    )
                }
                if (currentConversationId == conversationId) {
                    _uiState.update { it.copy(contextMessages = context) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "Could not compress conversation") }
            } finally {
                _uiState.update { it.copy(isCompressing = false) }
                compactJob = null
            }
        }
    }

    override fun cancelCompactConversation() {
        compactJob?.cancel()
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
        browserSessionManager.setWorkspace(path)
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
        browserSessionManager.setWorkspace(if (mode == AssistantMode.CHAT) null else workspacePath)
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
                            "thinking" -> {
                                steps.add(MessageStep.Thinking(
                                    id = stepId,
                                    text = s.optString("text"),
                                    isStreaming = s.optBoolean("isStreaming"),
                                    startedAt = s.optLong("startedAt", 0L).takeIf { it > 0 },
                                    durationMs = s.optLong("durationMs", 0L).takeIf { it > 0 }
                                ))
                            }
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
        val contextMessages = _uiState.value.contextMessages
        val messagesJson = serializeMessagesToJson(messages)
        val contextMessagesJson = serializeMessagesToJson(contextMessages)
        scope.launch {
            conversationSaveMutex.withLock {
                try {
                    if (currentConversationId == conversationId &&
                        serializeMessagesToJson(_uiState.value.messages) != messagesJson
                    ) return@withLock
                    val existing = conversationDao.getConversationById(conversationId) ?: return@withLock
                    conversationDao.updateConversation(
                        existing.copy(
                            messagesJson = messagesJson,
                            contextMessagesJson = contextMessagesJson,
                            updatedAt = System.currentTimeMillis()
                        )
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
                            contextMessagesJson = serializeMessagesToJson(_uiState.value.contextMessages),
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
                        contextMessagesJson = serializeMessagesToJson(_uiState.value.contextMessages),
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
                contextMessagesJson = serializeMessagesToJson(_uiState.value.contextMessages),
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
                            is MessageStep.Thinking -> {
                                put("type", "thinking")
                                put("text", step.text)
                                put("isStreaming", step.isStreaming)
                                step.startedAt?.let { put("startedAt", it) }
                                step.durationMs?.let { put("durationMs", it) }
                            }
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

private const val COMPRESSED_SESSION_CONTEXT_PREFIX = "[COMPRESSED SESSION CONTEXT]"
private const val AUTO_COMPACTED_CONTEXT_PREFIX = "[AUTO-COMPACTED ACTIVE CONTEXT]"

/**
 * Close out a turn that was still running when the process died.
 *
 * Returns null when the list holds no stalled turn, so callers can tell "nothing to do" apart from
 * "recovered" and leave the stored column untouched.
 */
internal fun markInterruptedTurn(messages: List<UiMessage>): List<UiMessage>? {
    val index = messages.indexOfLast { message ->
        message.role == MessageRole.ASSISTANT &&
            message.metadata["turnStatus"].isNullOrBlank() &&
            (message.isThinking || message.toolExecutions.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING })
    }
    if (index < 0) return null
    fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
        tool.copy(
            status = ToolStatus.ERROR,
            result = tool.result ?: "Interrupted when the app process stopped",
            metadata = tool.metadata + mapOf("approvalRequired" to "false", "approvalState" to "cancelled")
        )
    } else tool
    val interrupted = messages[index].let { message ->
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
    return messages.toMutableList().also { it[index] = interrupted }
}

/** Above this, the stored model context is carrying more tool payload than it can ever replay. */
private const val MAX_STORED_TOOL_PAYLOAD_CHARS = 2_000_000
private const val KEEP_VERBATIM_TAIL_MESSAGES = 12
private const val STORED_TOOL_RESULT_MAX_CHARS = 4_000

/**
 * Bound the stored model context by shrinking old tool payloads, never by removing messages.
 *
 * The term that actually grows without limit is tool-result bytes — a DOM dump or a file read can be
 * hundreds of kilobytes and is kept verbatim forever. Removing whole messages would be the obvious
 * fix and is the wrong one: `ledgerCoverage` is an index into this list, so deleting entries
 * silently closes the compaction gate, and anything removed past the ledger's boundary would have no
 * record anywhere. Rewriting bodies in place keeps every index, every tool_call/tool_result pairing
 * and every message; only text the model has long stopped receiving is replaced, and it is replaced
 * with a line that says so. The visible transcript is a separate column and keeps the full text.
 */
internal fun digestOldToolPayloads(
    context: List<UiMessage>,
    maxTotalChars: Int = MAX_STORED_TOOL_PAYLOAD_CHARS,
    keepNewest: Int = KEEP_VERBATIM_TAIL_MESSAGES
): List<UiMessage> {
    // Every tool result is stored three times — toolExecutions, steps, and canonicalHistory — and
    // all three are serialized. Counting and trimming only one would leave the ceiling unenforced.
    fun trim(execution: ToolExecution): ToolExecution {
        val result = execution.result
        return if (result == null || !needsTrimming(result)) execution
        else execution.copy(result = storedToolDigest(execution.name, result))
    }

    val totalPayload = context.sumOf { message ->
        message.toolExecutions.sumOf { it.result?.length ?: 0 } +
            message.steps.filterIsInstance<MessageStep.ToolCall>().sumOf { it.execution.result?.length ?: 0 } +
            message.canonicalHistory.sumOf { it.length }
    }
    if (totalPayload <= maxTotalChars) return context

    val cutoff = (context.size - keepNewest).coerceAtLeast(0)
    return context.mapIndexed { index, message ->
        if (index >= cutoff) return@mapIndexed message
        val executions = message.toolExecutions.map(::trim)
        val steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = trim(it.execution)) else it }
        val canonical = message.canonicalHistory.map(::digestCanonicalToolResult)
        if (executions == message.toolExecutions && steps == message.steps && canonical == message.canonicalHistory) {
            message
        } else {
            message.copy(toolExecutions = executions, steps = steps, canonicalHistory = canonical)
        }
    }
}

private const val STORED_TRIM_MARKER = "result trimmed in stored context after"
private const val STORED_TRIM_SUFFIX = "chars; full text remains in the transcript]"

/**
 * Idempotent: an already-trimmed body must not be re-cut, which would shred its own marker.
 *
 * Anchored to the end of the string rather than searched for anywhere in it — a `read_file` or
 * `browser` result can legitimately quote this marker, and a substring test would exempt that
 * payload from the ceiling forever.
 */
private fun needsTrimming(result: String): Boolean =
    result.length > STORED_TOOL_RESULT_MAX_CHARS && !result.trimEnd().endsWith(STORED_TRIM_SUFFIX)

private fun storedToolDigest(toolName: String, result: String): String =
    result.take(STORED_TOOL_RESULT_MAX_CHARS).trimEnd() +
        "\n… [$toolName $STORED_TRIM_MARKER ${result.length} $STORED_TRIM_SUFFIX"

private fun digestCanonicalToolResult(entry: String): String {
    val json = runCatching { JSONObject(entry) }.getOrNull() ?: return entry
    if (json.optString("kind") != "tool_result") return entry
    val result = json.optString("result")
    if (!needsTrimming(result)) return entry
    return json.put("result", storedToolDigest(json.optString("name").ifBlank { "tool" }, result)).toString()
}

internal fun contextAfterHistoryClear(context: List<UiMessage>, deleteContext: Boolean): List<UiMessage> =
    if (deleteContext) emptyList() else context

/**
 * The compaction record carried in the model context.
 *
 * The two sources are tagged apart on purpose: automatic compaction replaces only its own record,
 * so a summary the user asked for with `/compact` is never overwritten by the host.
 */
internal fun compressedSessionContext(summary: String, auto: Boolean = false): List<UiMessage> = listOf(
    UiMessage(
        role = MessageRole.SYSTEM,
        content = "${if (auto) AUTO_COMPACTED_CONTEXT_PREFIX else COMPRESSED_SESSION_CONTEXT_PREFIX}\n${summary.trim()}",
        metadata = mapOf("compressed" to "true", "compactionSource" to if (auto) "auto" else "manual")
    )
)

// Extension to map domain to repository model
internal fun UiMessage.toChatMessages(): List<ChatMessage> {
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
        // Blank must become null: an empty string is rendered as an empty text content block, which
        // Anthropic rejects outright. An assistant turn that was pure tool calls, or that was
        // stopped before any prose, legitimately has no text.
        content = content.takeIf { it.isNotBlank() },
        images = attachments.filter { it.mimeType.startsWith("image/") }.map {
            com.amaya.intelligence.data.remote.api.ChatImage(it.dataBase64, it.mimeType, it.fileName)
        },
        toolCalls = calls.takeIf { it.isNotEmpty() },
        responseItems = responseItems
    )
    // An assistant turn that produced nothing at all — a turn that failed before the first delta —
    // must not be replayed. It has no text, no tool call and no image, so every provider sees an
    // empty content block and rejects the request; dropping it here also heals conversations
    // already stored in that state.
    if (role == MessageRole.ASSISTANT && message.content == null && calls.isEmpty() &&
        message.images.isEmpty() && responseItems.isEmpty()
    ) return emptyList()
    if (role != MessageRole.ASSISTANT || calls.isEmpty()) return listOf(message)
    return buildList {
        add(message)
        toolExecutions.forEach { execution ->
            // Every advertised tool call needs a matching result. A turn that died between
            // ToolCallStart and ToolCallResult leaves the execution pending, and replaying that as
            // a bare tool_call orphans the id — which every provider rejects on the next request.
            add(ChatMessage(
                role = MessageRole.TOOL,
                toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                    toolCallId = execution.toolCallId,
                    content = execution.result ?: UNFINISHED_TOOL_RESULT,
                    isError = execution.status == ToolStatus.ERROR || execution.result == null,
                    metadata = execution.metadata.filterKeys { it == "thoughtSignature" } +
                        ("toolName" to execution.name)
                )
            ))
        }
    }
}

internal const val UNFINISHED_TOOL_RESULT =
    "[no result: the turn ended before this tool returned]"

/**
 * Commit a run of streamed assistant text to the canonical model history as one entry.
 *
 * Text is buffered on the turn and flushed here whenever a tool call, tool result, response item, or
 * the end of the turn establishes an ordering boundary — so the canonical list holds one entry per
 * run of prose rather than one per stream delta.
 */
internal fun List<String>.appendCanonicalText(chunk: String?): List<String> {
    if (chunk.isNullOrEmpty()) return this
    return this + JSONObject().put("kind", "assistant_text").put("text", chunk).toString()
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
    return messages.withSyntheticToolResults()
}

/**
 * Guarantee every advertised tool call has a matching result.
 *
 * A turn that died between the call and its result — a provider error, a dropped stream, a killed
 * process — persists a `tool_call` with nothing answering it, and every provider rejects the next
 * request in that conversation. Repairing it here also heals conversations that were already stored
 * in that state.
 */
internal fun List<ChatMessage>.withSyntheticToolResults(): List<ChatMessage> {
    val answered = mapNotNull { it.toolResult?.toolCallId }.toSet()
    if (none { message -> message.toolCalls.orEmpty().any { it.id !in answered } }) return this
    return flatMap { message ->
        val unanswered = message.toolCalls.orEmpty().filter { it.id !in answered }
        if (unanswered.isEmpty()) listOf(message) else listOf(message) + unanswered.map { call ->
            ChatMessage(
                role = MessageRole.TOOL,
                toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                    toolCallId = call.id,
                    content = UNFINISHED_TOOL_RESULT,
                    isError = true,
                    metadata = mapOf("toolName" to call.name)
                )
            )
        }
    }
}

private fun JSONObject.toAnyMap(): Map<String, Any?> = buildMap {
    keys().forEach { key -> put(key, opt(key).takeUnless { it == JSONObject.NULL }) }
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, optString(key, "")) }
}
