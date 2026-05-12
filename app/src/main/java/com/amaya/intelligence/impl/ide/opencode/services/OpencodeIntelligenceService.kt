package com.amaya.intelligence.impl.ide.opencode.services

import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.models.ChatUiState
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ProjectFileEntry
import com.amaya.intelligence.domain.models.RemoteWorkspace
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeController
import com.amaya.intelligence.impl.ide.opencode.OpencodeClient
import com.amaya.intelligence.impl.ide.opencode.OpencodeMessagePartUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opencode implementation of [IntelligenceService]. This service stitches:
 *  - [OpencodeClient] for envelope I/O over the Windows Bridge WebSocket
 *  - [WindowsBridgeController] for connection state / agent-control gating
 *
 * It does not go through `AiRepository`. Opencode *is* the LLM orchestrator —
 * Amaya's local planner stays uninvolved.
 */
@Singleton
class OpencodeIntelligenceService @Inject constructor(
    private val opencodeClient: OpencodeClient,
    private val bridgeController: WindowsBridgeController,
    @ApplicationScope private val scope: CoroutineScope
) : IntelligenceService {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionMode = IntelligenceSessionManager.SessionMode.OPENCODE,
            connectionState = mapConnectionState(bridgeController.currentConnectionState())
        )
    )
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ProjectFileEntry>>(emptyList())
    override val projectFiles: StateFlow<List<ProjectFileEntry>> = _projectFiles.asStateFlow()

    private val _projectPath = MutableStateFlow("")
    override val projectPath: StateFlow<String> = _projectPath.asStateFlow()

    private val _workspaces = MutableStateFlow<List<RemoteWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<RemoteWorkspace>> = _workspaces.asStateFlow()

    @Volatile private var activeSessionId: String? = null
    @Volatile private var currentAssistantMessageId: String? = null

    init {
        opencodeClient.attach(scope)
        scope.launch {
            opencodeClient.events.collect { event -> handleEvent(event) }
        }
        scope.launch {
            while (true) {
                _uiState.update { it.copy(connectionState = mapConnectionState(bridgeController.currentConnectionState())) }
                delay(750)
            }
        }
        scope.launch {
            opencodeClient.runtime.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        sessionMode = IntelligenceSessionManager.SessionMode.OPENCODE,
                        error = snapshot.lastError
                    )
                }
            }
        }
    }

    override fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val userMsg = UiMessage(role = MessageRole.USER, content = trimmed)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                isLoading = true,
                isStreaming = true,
                error = null,
                sessionMode = IntelligenceSessionManager.SessionMode.OPENCODE
            )
        }
        ensureSessionAndPrompt(trimmed)
    }

    override fun stopGeneration() {
        val session = activeSessionId ?: return
        opencodeClient.abortPrompt(session)
        _uiState.update { it.copy(isLoading = false, isStreaming = false) }
    }

    override fun clearConversation() {
        currentAssistantMessageId = null
        val previous = activeSessionId
        activeSessionId = null
        _uiState.update {
            it.copy(
                conversationId = null,
                messages = emptyList(),
                error = null,
                isLoading = false,
                isStreaming = false,
                sessionMode = IntelligenceSessionManager.SessionMode.OPENCODE
            )
        }
        previous?.let { opencodeClient.deleteSession(it) }
    }

    override fun loadConversation(id: String) {
        // Opencode sessions live on the bridge; pointing to an id simply switches
        // the active conversation for the next prompt. Full history reload would
        // require opencode /session/{id}/message pagination — deferred.
        activeSessionId = id
        _uiState.update { it.copy(conversationId = id) }
    }

    override fun deleteConversation(id: String) {
        opencodeClient.deleteSession(id)
        if (activeSessionId == id) {
            activeSessionId = null
            _uiState.update { it.copy(conversationId = null) }
        }
    }

    override fun setSelectedAgent(agentId: String) {
        _uiState.update { it.copy(activeAgentId = agentId, selectedModel = agentId) }
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun refreshModels() {
        opencodeClient.requestProviders()
        opencodeClient.requestModels()
    }

    override fun resync() {
        opencodeClient.requestRuntimeStatus()
        opencodeClient.requestSessions()
    }

    override fun refreshState() {
        resync()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun ensureSessionAndPrompt(content: String) {
        val existing = activeSessionId
        if (existing != null) {
            promptActiveSession(existing, content)
            return
        }
        pendingPrompt = content
        opencodeClient.createSession(title = content.take(48))
    }

    @Volatile private var pendingPrompt: String? = null

    private fun promptActiveSession(sessionId: String, content: String) {
        opencodeClient.sendPrompt(sessionId = sessionId, text = content)
    }

    private fun handleEvent(event: OpencodeClient.Event) {
        when (event) {
            is OpencodeClient.Event.SessionCreated -> {
                activeSessionId = event.session.sessionId
                _uiState.update { it.copy(conversationId = event.session.sessionId) }
                pendingPrompt?.let { promptActiveSession(event.session.sessionId, it) }
                pendingPrompt = null
            }
            is OpencodeClient.Event.SessionDeleted -> {
                if (event.sessionId == activeSessionId) {
                    activeSessionId = null
                    _uiState.update { it.copy(conversationId = null) }
                }
            }
            is OpencodeClient.Event.MessagePart -> handleMessagePart(event.update)
            is OpencodeClient.Event.SessionStatus -> Unit
            is OpencodeClient.Event.SessionError -> _uiState.update {
                it.copy(error = event.message, isLoading = false, isStreaming = false)
            }
            is OpencodeClient.Event.PermissionAsked -> Unit // handled by controller approvals
            is OpencodeClient.Event.PlanUpdate -> Unit
            is OpencodeClient.Event.TodoUpdate -> Unit
            is OpencodeClient.Event.Sessions -> Unit
            is OpencodeClient.Event.Providers -> Unit
            is OpencodeClient.Event.Models -> Unit
            is OpencodeClient.Event.Mcp -> Unit
            is OpencodeClient.Event.Runtime -> Unit
            is OpencodeClient.Event.Config -> Unit
            is OpencodeClient.Event.Error -> _uiState.update {
                it.copy(error = event.message, isLoading = false, isStreaming = false)
            }
        }
    }

    private fun handleMessagePart(update: OpencodeMessagePartUpdate) {
        when (update.partType) {
            OpencodeMessagePartUpdate.PartType.TEXT -> appendAssistantText(update.text)
            OpencodeMessagePartUpdate.PartType.THOUGHT -> appendThinking(update.text)
            OpencodeMessagePartUpdate.PartType.TOOL -> upsertToolStep(update)
            OpencodeMessagePartUpdate.PartType.OTHER -> Unit
        }
    }

    private fun appendAssistantText(delta: String) {
        if (delta.isBlank()) return
        ensureAssistantMessage()
        _uiState.update { state ->
            val id = currentAssistantMessageId ?: return@update state
            state.copy(messages = state.messages.map { msg ->
                if (msg.id != id) msg
                else msg.copy(
                    content = msg.content + delta,
                    steps = appendTextStep(msg.steps, delta)
                )
            })
        }
    }

    private fun appendThinking(delta: String) {
        if (delta.isBlank()) return
        ensureAssistantMessage()
        _uiState.update { state ->
            val id = currentAssistantMessageId ?: return@update state
            state.copy(messages = state.messages.map { msg ->
                if (msg.id != id) msg
                else msg.copy(thinking = (msg.thinking ?: "") + delta, isThinking = true)
            })
        }
    }

    private fun upsertToolStep(update: OpencodeMessagePartUpdate) {
        val toolName = update.toolName ?: return
        ensureAssistantMessage()
        _uiState.update { state ->
            val id = currentAssistantMessageId ?: return@update state
            state.copy(messages = state.messages.map { msg ->
                if (msg.id != id) return@map msg
                val existing = msg.toolExecutions.firstOrNull { it.toolCallId == (update.partId ?: toolName) }
                if (existing == null) {
                    val execution = ToolExecution(
                        toolCallId = update.partId ?: toolName,
                        name = toolName,
                        arguments = emptyMap(),
                        status = when (update.toolState) {
                            "completed", "success" -> ToolStatus.SUCCESS
                            "error", "failed" -> ToolStatus.ERROR
                            else -> ToolStatus.RUNNING
                        }
                    )
                    msg.copy(
                        toolExecutions = msg.toolExecutions + execution,
                        steps = msg.steps + MessageStep.ToolCall(execution = execution)
                    )
                } else {
                    val updated = existing.copy(
                        status = when (update.toolState) {
                            "completed", "success" -> ToolStatus.SUCCESS
                            "error", "failed" -> ToolStatus.ERROR
                            else -> ToolStatus.RUNNING
                        }
                    )
                    msg.copy(
                        toolExecutions = msg.toolExecutions.map { if (it.toolCallId == updated.toolCallId) updated else it },
                        steps = msg.steps.map { step ->
                            if (step is MessageStep.ToolCall && step.execution.toolCallId == updated.toolCallId) {
                                step.copy(execution = updated)
                            } else step
                        }
                    )
                }
            })
        }
    }

    private fun appendTextStep(steps: List<MessageStep>, delta: String): List<MessageStep> {
        val last = steps.lastOrNull()
        return if (last is MessageStep.Text) {
            steps.dropLast(1) + last.copy(content = last.content + delta)
        } else {
            steps + MessageStep.Text(content = delta)
        }
    }

    private fun ensureAssistantMessage() {
        val id = currentAssistantMessageId
        if (id != null && _uiState.value.messages.any { it.id == id }) return
        val msg = UiMessage(role = MessageRole.ASSISTANT, content = "")
        currentAssistantMessageId = msg.id
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun mapConnectionState(state: WindowsBridgeConnectionState): ConnectionState = when (state) {
        WindowsBridgeConnectionState.CONNECTED,
        WindowsBridgeConnectionState.PAUSED -> ConnectionState.CONNECTED
        WindowsBridgeConnectionState.CONNECTING,
        WindowsBridgeConnectionState.RECONNECTING,
        WindowsBridgeConnectionState.CLOSING -> ConnectionState.CONNECTING
        WindowsBridgeConnectionState.DISCONNECTED,
        WindowsBridgeConnectionState.ERROR -> ConnectionState.DISCONNECTED
    }
}
