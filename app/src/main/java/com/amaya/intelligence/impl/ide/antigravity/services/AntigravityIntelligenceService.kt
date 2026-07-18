package com.amaya.intelligence.impl.ide.antigravity.services

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteSessionClient
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteEvent
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteAttachment
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteFileEntry as ClientRemoteFileEntry
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteWorkspace as ClientRemoteWorkspace
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.impl.ide.antigravity.services.streaming.StreamingStateManager
import com.amaya.intelligence.impl.ide.antigravity.services.event.AntigravityEventHandler
import com.amaya.intelligence.di.ApplicationScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Antigravity implementation of IntelligenceService.
 * Wraps RemoteSessionClient and delegates event handling to AntigravityEventHandler.
 */
@Singleton
class AntigravityIntelligenceService @Inject constructor(
    private val client: RemoteSessionClient,
    @ApplicationScope private val scope: CoroutineScope
) : IntelligenceService {

    private val _uiState = MutableStateFlow(ChatUiState(
        sessionMode = IntelligenceSessionManager.SessionMode.ANTIGRAVITY
    ))
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ProjectFileEntry>>(emptyList())
    override val projectFiles: StateFlow<List<ProjectFileEntry>> = _projectFiles.asStateFlow()

    private val _projectPath = MutableStateFlow("")
    override val projectPath: StateFlow<String> = _projectPath.asStateFlow()

    private val _workspaces = MutableStateFlow<List<RemoteWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<RemoteWorkspace>> = _workspaces.asStateFlow()

    private val stateManager = StreamingStateManager()
    private val locallyStoppedConversations = mutableMapOf<String, Long>()
    private var hasBootstrappedRemoteWorkspace = false
    
    private val eventHandler = AntigravityEventHandler(
        scope = scope,
        client = client,
        stateManager = stateManager,
        onUiStateUpdate = { update -> _uiState.update(update) },
        onConversationsUpdate = { entities -> _conversations.value = entities },
        onProjectFilesUpdate = { files, path -> 
            _projectFiles.value = files
            _projectPath.value = path
        },
        onWorkspacesUpdate = { workspaces -> _workspaces.value = workspaces }
    )

    init {
        scope.launch {
            client.events.collect { event ->
                if (shouldIgnoreAfterLocalStop(event)) {
                    AntigravityRemoteDebugLog.handlerNote("SERVICE_IGNORE_AFTER_STOP", AntigravityRemoteDebugLog.eventSummary(event))
                    return@collect
                }
                AntigravityRemoteDebugLog.eventBefore(event, _uiState.value)
                eventHandler.handleEvent(event, _uiState.value.conversationId)
                AntigravityRemoteDebugLog.eventAfter(event, _uiState.value)
            }
        }
        // Monitor connection state
        scope.launch {
            client.connectionState.collect { state ->
                AntigravityRemoteDebugLog.connection("STATE $state current=${AntigravityRemoteDebugLog.stateSummary(_uiState.value)}")
                _uiState.update { it.copy(connectionState = state) }
                if (state == ConnectionState.CONNECTED) {
                    if (!hasBootstrappedRemoteWorkspace) {
                        hasBootstrappedRemoteWorkspace = true
                        client.getWorkspaces()
                        _uiState.value.workspacePath?.takeIf { it.isNotBlank() }?.let { client.getProjectFiles(it) }
                    }
                    resync()
                }
            }
        }
    }

    override fun sendMessage(content: String) {
        val activeId = _uiState.value.conversationId
        val mode = _uiState.value.conversationMode.wireValue
        activeId?.let { locallyStoppedConversations.remove(it) }
        AntigravityRemoteDebugLog.handlerNote("SERVICE_SEND", "text len=${content.length} cid=${activeId ?: "-"} mode=$mode state=${AntigravityRemoteDebugLog.stateSummary(_uiState.value)}")
        client.sendMessage(content, activeId, mode)
        
        // Optimistic update
        val userMsg = UiMessage(
            role = MessageRole.USER,
            content = content
        )
        _uiState.update { it.copy(messages = it.messages + userMsg, isLoading = true) }
    }

    override fun sendMessageWithImage(content: String, imageBase64: String, mimeType: String, fileName: String) {
        android.util.Log.d("AntigravityIntelligenceService", "sendMessageWithImage: content=${content.take(50)}, mimeType=$mimeType, base64Len=${imageBase64.length}, fileName=$fileName")
        val activeId = _uiState.value.conversationId
        val mode = _uiState.value.conversationMode.wireValue
        val attachment = RemoteAttachment(mimeType, imageBase64, fileName)
        activeId?.let { locallyStoppedConversations.remove(it) }
        AntigravityRemoteDebugLog.handlerNote("SERVICE_SEND_IMAGE", "text len=${content.length} cid=${activeId ?: "-"} mode=$mode mime=$mimeType file=$fileName state=${AntigravityRemoteDebugLog.stateSummary(_uiState.value)}")
        client.sendMessage(content, activeId, mode, listOf(attachment))
        
        // Optimistic update with image attachment
        val userMsg = UiMessage(
            role = MessageRole.USER,
            content = content,
            attachments = listOf(MessageAttachment(mimeType, imageBase64, fileName))
        )
        _uiState.update { it.copy(messages = it.messages + userMsg, isLoading = true) }
    }

    override fun stopGeneration() {
        val conversationId = _uiState.value.conversationId
        conversationId?.let { locallyStoppedConversations[it] = System.currentTimeMillis() }
        AntigravityRemoteDebugLog.handlerNote("SERVICE_STOP", AntigravityRemoteDebugLog.stateSummary(_uiState.value))
        client.stopGeneration(conversationId)
        stateManager.clearAll()
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                isStreaming = false,
                messages = state.messages.mapIndexed { index, message ->
                    if (index != state.messages.lastIndex) message else message.copy(
                        toolExecutions = message.toolExecutions.map { tool ->
                            if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) tool.copy(status = ToolStatus.ERROR) else tool
                        },
                        steps = message.steps.map { step ->
                            if (step is MessageStep.ToolCall && (step.execution.status == ToolStatus.RUNNING || step.execution.status == ToolStatus.PENDING)) {
                                step.copy(execution = step.execution.copy(status = ToolStatus.ERROR))
                            } else step
                        }
                    )
                }
            )
        }
    }

    private fun shouldIgnoreAfterLocalStop(event: RemoteEvent): Boolean {
        val cid = event.conversationId ?: return false
        val stoppedAt = locallyStoppedConversations[cid] ?: return false
        if (System.currentTimeMillis() - stoppedAt > 30_000L) {
            locallyStoppedConversations.remove(cid)
            return false
        }
        return when (event) {
            is RemoteEvent.TextDelta,
            is RemoteEvent.AiThinking,
            is RemoteEvent.ToolCallStart,
            is RemoteEvent.ToolCallResult,
            is RemoteEvent.ToolActivity -> true
            is RemoteEvent.StateUpdate -> event.isStreaming
            is RemoteEvent.StateSync -> event.isStreaming
            is RemoteEvent.StreamDone -> {
                locallyStoppedConversations.remove(cid)
                false
            }
            else -> false
        }
    }

    override fun clearConversation() {
        AntigravityRemoteDebugLog.handlerNote("SERVICE_NEW_CHAT", AntigravityRemoteDebugLog.stateSummary(_uiState.value))
        client.newChat()
    }

    override fun loadConversation(id: String) {
        val resolvedId = eventHandler.resolveConversationId(id)
        AntigravityRemoteDebugLog.handlerNote("SERVICE_LOAD", "requested=$id resolved=$resolvedId before=${AntigravityRemoteDebugLog.stateSummary(_uiState.value)}")
        _uiState.update { it.copy(conversationId = resolvedId, isLoading = true, messages = emptyList()) }
        client.loadConversation(resolvedId)
    }

    override fun deleteConversation(id: String) {
        // Antigravity might not support deletion via client yet, or needs mapping
    }

    override fun selectModel(modelKey: String) {
        client.selectModel(modelKey)
    }

    override fun getProjectFiles(path: String) {
        client.getProjectFiles(path)
    }

    override fun respondToToolInteraction(executionId: String, confirmed: Boolean) {
        val conversationId = _uiState.value.conversationId
        AntigravityRemoteDebugLog.handlerNote("SERVICE_TOOL_INTERACTION", "id=$executionId confirmed=$confirmed cid=${conversationId ?: "-"} state=${AntigravityRemoteDebugLog.stateSummary(_uiState.value)}")
        client.respondToToolInteraction(
            toolCallId = executionId,
            accepted = confirmed,
            conversationId = conversationId
        )
    }

    override fun setWorkspace(path: String?) {
        _uiState.update { it.copy(workspacePath = path) }
        path?.let { client.getProjectFiles(it) }
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ponytail: ceiling = Antigravity protocol has no per-turn reasoning field.
    // Visual-only state update keeps the chat bulb consistent with other runtimes.
    override fun setEffort(effort: com.amaya.intelligence.data.remote.api.ThinkingEffort) {
        _uiState.update { it.copy(effort = effort) }
    }

    override fun connect(ip: String, port: Int) {
        client.connect(ip, port)
    }

    override fun resync() {
        AntigravityRemoteDebugLog.handlerNote("SERVICE_RESYNC", AntigravityRemoteDebugLog.stateSummary(_uiState.value))
        client.forceResync(resetSequence = true)
    }

    override fun refreshState() {
        val state = _uiState.value
        AntigravityRemoteDebugLog.handlerNote("SERVICE_REFRESH_ALL", AntigravityRemoteDebugLog.stateSummary(state))
        stateManager.clearAll()
        client.refreshAllState(
            conversationId = state.conversationId,
            workspacePath = state.workspacePath
        )
    }

    override fun setConversationMode(mode: ConversationMode) {
        _uiState.update { it.copy(conversationMode = mode) }
        client.setConversationMode(mode)
    }

    override fun refreshModels() {
        client.getModels()
    }

    override fun loadMoreConversations() {
        // Implementation for pagination if needed
    }

    override fun hasMoreConversations(): Boolean {
        return false
    }
}

// Extension to map remote models to domain
private fun ClientRemoteFileEntry.toProjectFileEntry(): ProjectFileEntry {
    return ProjectFileEntry(
        name = name,
        path = path,
        type = type,
        size = size
    )
}

private fun ClientRemoteWorkspace.toDomainWorkspace(): RemoteWorkspace {
    return RemoteWorkspace(
        name = name,
        path = path,
        isCurrent = isCurrent
    )
}
