package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.repository.AiRepository
import com.amaya.intelligence.data.repository.SessionMemoryRepository

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.tools.ConfirmationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray
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
    internal val aiRepository: AiRepository,
    internal val conversationDao: ConversationDao,
    internal val agentDao: AgentDao,
    internal val projectDao: ProjectDao,
    internal val sessionMemoryRepository: SessionMemoryRepository,
    internal val ledgerStore: com.amaya.intelligence.data.repository.ActiveContextLedgerStore,
    internal val settingsManager: AiSettingsManager,
    internal val browserSessionManager: BrowserSessionManager,
    @ApplicationContext internal val appContext: Context,
    @ApplicationScope appScope: CoroutineScope
) : IntelligenceService {

    // Confine mutable chat/UI state to the main thread while retaining process lifetime.
    internal val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    internal val _uiState = MutableStateFlow(ChatUiState(
        sessionMode = IntelligenceSessionManager.SessionMode.LOCAL
    ))
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    internal val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()
    override val allLocalConversations: StateFlow<List<ConversationEntity>> = conversationDao.observeAllConversations()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    internal val _runningSessions = MutableStateFlow<List<RunningSession>>(emptyList())
    override val runningSessions: StateFlow<List<RunningSession>> = _runningSessions.asStateFlow()
    internal val _completedSessions = kotlinx.coroutines.flow.MutableSharedFlow<RunningSession>(extraBufferCapacity = 16)
    override val completedSessions: kotlinx.coroutines.flow.SharedFlow<RunningSession> = _completedSessions

    internal val _workspaces = MutableStateFlow<List<RemoteWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<RemoteWorkspace>> = _workspaces.asStateFlow()

    internal var chatJob: Job? = null
    internal var compactJob: Job? = null
    @Volatile internal var currentConversationId: Long? = null
    internal var currentAssistantMessageId: String? = null
    internal val assistantTextBuffer = StringBuilder()
    internal val assistantThinkingBuffer = StringBuilder()
    internal var lastAssistantTextUiEmitAt = 0L
    internal var assistantFlushJob: Job? = null
    internal var thinkingFlushJob: Job? = null
    internal val conversationSaveMutex = Mutex()
    internal val conversationStartMutex = Mutex()
    internal val pendingToolConfirmations = ToolConfirmationRegistry()
    internal val pendingConfirmationUi = ConcurrentHashMap<String, ConfirmationRequest>()
    internal val pendingApprovalIds = ConcurrentHashMap<String, String>()
    internal val titleJobs = ConcurrentHashMap<Long, Job>()
    internal val nextTurnId = AtomicLong(0L)
    internal val targetEpoch = AtomicLong(0L)
    internal val activeTurns = ConcurrentHashMap<Long, LocalTurn>()
    internal val startingConversations = ConcurrentHashMap<Long, Long>()
    internal val startingNewTurnId = AtomicLong(0L)
    internal val stoppingConversations = ConcurrentHashMap.newKeySet<Long>()
    internal val turnsById = ConcurrentHashMap<Long, LocalTurn>()
    internal data class PendingMessage(
        val content: String,
        val images: List<com.amaya.intelligence.data.remote.api.ChatImage>
    )
    internal data class LocalTurn(
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
    internal fun LocalTurn.drainCanonicalText(): String? {
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

    override suspend fun sendMessageToConversation(conversationId: Long, content: String): Boolean =
        sendMessageToConversationImpl(conversationId, content)

    suspend fun runDelegatedAgentTurn(conversationId: Long, request: String): SubagentResult =
        runDelegatedAgentTurnImpl(conversationId, request)

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

    internal fun updateTurnToolExecution(turn: LocalTurn, toolCallId: String, transform: (ToolExecution) -> ToolExecution) {
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

    internal fun launchTitleGeneration(userMessage: String, conversationId: Long?) {
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


}
