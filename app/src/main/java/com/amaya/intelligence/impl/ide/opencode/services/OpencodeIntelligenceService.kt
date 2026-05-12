package com.amaya.intelligence.impl.ide.opencode.services

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.local.entity.ConversationScope
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.domain.bridge.AgentModes
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
import com.amaya.intelligence.tools.TodoItem
import com.amaya.intelligence.tools.TodoRepository
import com.amaya.intelligence.tools.TodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opencode implementation of [IntelligenceService]. Rides on the Windows Bridge
 * transport through [OpencodeClient] + [WindowsBridgeController]. History
 * persists to the conversation DB under [ConversationScope.OPENCODE].
 */
@Singleton
class OpencodeIntelligenceService @Inject constructor(
    private val opencodeClient: OpencodeClient,
    private val bridgeController: WindowsBridgeController,
    private val conversationDao: ConversationDao,
    private val todoRepository: TodoRepository,
    @ApplicationScope private val scope: CoroutineScope
) : IntelligenceService {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionMode = IntelligenceSessionManager.SessionMode.OPENCODE,
            connectionState = mapConnectionState(bridgeController.currentConnectionState()),
            conversationModeId = AgentModes.BUILD
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

    private val _mode = MutableStateFlow(AgentModes.BUILD)
    val mode: StateFlow<String> = _mode.asStateFlow()

    @Volatile private var activeSessionId: String? = null
    @Volatile private var currentAssistantMessageId: String? = null
    @Volatile private var pendingPrompt: String? = null
    @Volatile private var currentRoomConversationId: Long? = null
    private val persistMutex = Mutex()

    init {
        opencodeClient.attach(scope)
        scope.launch {
            conversationDao.observeConversationsByScope(ConversationScope.OPENCODE.wireName)
                .collect { entities -> _conversations.value = entities }
        }
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

    // ── Mode / routing helpers ──────────────────────────────────────────────

    fun setMode(newMode: String) {
        _mode.value = when (newMode) {
            AgentModes.BUILD, AgentModes.PLAN -> newMode
            else -> AgentModes.BUILD
        }
        _uiState.update { it.copy(conversationModeId = _mode.value) }
    }

    override fun setConversationModeId(modeId: String) {
        setMode(modeId)
    }

    // ── IntelligenceService ────────────────────────────────────────────────

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
        scheduleSend(trimmed)
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
        currentRoomConversationId = null
        todoRepository.clear()
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
        val longId = id.toLongOrNull()
        if (longId != null) {
            scope.launch {
                val entity = conversationDao.getConversationById(longId)
                    ?.takeIf { it.scope == ConversationScope.OPENCODE.wireName }
                    ?: return@launch
                currentRoomConversationId = entity.id
                val parsed = parseMessagesFromJson(entity.messagesJson)
                activeSessionId = extractOpencodeSessionId(entity.messagesJson)
                _uiState.update {
                    it.copy(
                        conversationId = entity.id.toString(),
                        messages = parsed,
                        isLoading = false,
                        isStreaming = false,
                        error = null
                    )
                }
            }
        } else {
            activeSessionId = id
            _uiState.update { it.copy(conversationId = id) }
        }
    }

    override fun deleteConversation(id: String) {
        val longId = id.toLongOrNull()
        if (longId != null) {
            scope.launch {
                val entity = conversationDao.getConversationById(longId) ?: return@launch
                if (entity.scope != ConversationScope.OPENCODE.wireName) return@launch
                val opencodeSession = extractOpencodeSessionId(entity.messagesJson)
                conversationDao.deleteConversationById(longId)
                if (currentRoomConversationId == longId) {
                    currentRoomConversationId = null
                    if (activeSessionId == opencodeSession) {
                        activeSessionId = null
                        _uiState.update {
                            it.copy(conversationId = null, messages = emptyList())
                        }
                    }
                }
                opencodeSession?.let { opencodeClient.deleteSession(it) }
            }
        } else {
            opencodeClient.deleteSession(id)
            if (activeSessionId == id) {
                activeSessionId = null
                _uiState.update { it.copy(conversationId = null) }
            }
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

    override fun respondToToolInteraction(executionId: String, confirmed: Boolean) {
        opencodeClient.respondCurrentPermission(if (confirmed) "once" else "reject")
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun scheduleSend(content: String) {
        val existing = activeSessionId
        val currentMode = _mode.value
        if (existing != null) {
            opencodeClient.sendPrompt(
                sessionId = existing,
                text = content,
                agent = currentMode
            )
        } else {
            pendingPrompt = content
            opencodeClient.createSession(title = content.take(48), agent = currentMode)
        }
    }

    private fun handleEvent(event: OpencodeClient.Event) {
        when (event) {
            is OpencodeClient.Event.SessionCreated -> {
                activeSessionId = event.session.sessionId
                _uiState.update { it.copy(conversationId = event.session.sessionId) }
                pendingPrompt?.let { content ->
                    opencodeClient.sendPrompt(
                        sessionId = event.session.sessionId,
                        text = content,
                        agent = _mode.value
                    )
                }
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
            is OpencodeClient.Event.PermissionAsked -> Unit
            is OpencodeClient.Event.PlanUpdate -> handlePlanUpdate(event.entries)
            is OpencodeClient.Event.TodoUpdate -> handleTodoUpdate(event.todos)
            is OpencodeClient.Event.Sessions,
            is OpencodeClient.Event.Providers,
            is OpencodeClient.Event.Models,
            is OpencodeClient.Event.Mcp,
            is OpencodeClient.Event.Runtime,
            is OpencodeClient.Event.Config,
            is OpencodeClient.Event.SessionCreated -> Unit
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
        persistCurrentConversationAsync()
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
                val callId = update.partId ?: toolName
                val existing = msg.toolExecutions.firstOrNull { it.toolCallId == callId }
                val nextStatus = when (update.toolState) {
                    "completed", "success" -> ToolStatus.SUCCESS
                    "error", "failed" -> ToolStatus.ERROR
                    else -> ToolStatus.RUNNING
                }
                if (existing == null) {
                    val execution = ToolExecution(
                        toolCallId = callId,
                        name = toolName,
                        arguments = emptyMap(),
                        status = nextStatus
                    )
                    msg.copy(
                        toolExecutions = msg.toolExecutions + execution,
                        steps = msg.steps + MessageStep.ToolCall(execution = execution)
                    )
                } else {
                    val updated = existing.copy(status = nextStatus)
                    msg.copy(
                        toolExecutions = msg.toolExecutions.map { if (it.toolCallId == callId) updated else it },
                        steps = msg.steps.map { step ->
                            if (step is MessageStep.ToolCall && step.execution.toolCallId == callId) {
                                step.copy(execution = updated)
                            } else step
                        }
                    )
                }
            })
        }
    }

    private fun handlePlanUpdate(entries: List<Map<String, Any?>>) {
        val todos = entries.mapIndexedNotNull { index, entry ->
            val content = entry["content"] as? String ?: return@mapIndexedNotNull null
            val status = when (entry["status"] as? String) {
                "completed" -> TodoStatus.COMPLETED
                "in_progress" -> TodoStatus.IN_PROGRESS
                else -> TodoStatus.PENDING
            }
            TodoItem(
                id = index,
                status = status,
                content = content
            )
        }
        todoRepository.replaceAll(todos)
    }

    private fun handleTodoUpdate(todos: List<Map<String, Any?>>) {
        val items = todos.mapIndexedNotNull { index, entry ->
            val content = entry["content"] as? String ?: return@mapIndexedNotNull null
            val status = when (entry["status"] as? String) {
                "completed" -> TodoStatus.COMPLETED
                "in_progress" -> TodoStatus.IN_PROGRESS
                else -> TodoStatus.PENDING
            }
            TodoItem(
                id = index,
                status = status,
                content = content
            )
        }
        todoRepository.replaceAll(items)
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

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun persistCurrentConversationAsync() {
        scope.launch { persistCurrentConversation() }
    }

    private suspend fun persistCurrentConversation(): Long? = persistMutex.withLock {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return@withLock currentRoomConversationId
        val opencodeSessionId = activeSessionId
        val title = messages.firstOrNull { it.role == MessageRole.USER }
            ?.content
            ?.split("\\s+".toRegex())
            ?.take(5)
            ?.joinToString(" ")
            ?.take(50)
            ?.ifBlank { "Opencode Chat" }
            ?: "Opencode Chat"
        val now = System.currentTimeMillis()
        val json = serializeMessagesToJson(messages, opencodeSessionId)
        val existing = currentRoomConversationId
        return@withLock if (existing != null) {
            val row = conversationDao.getConversationById(existing)
            if (row != null && row.scope == ConversationScope.OPENCODE.wireName) {
                conversationDao.updateConversation(row.copy(messagesJson = json, updatedAt = now))
                existing
            } else {
                currentRoomConversationId = null
                insertConversation(title, json, now)
            }
        } else {
            insertConversation(title, json, now)
        }
    }

    private suspend fun insertConversation(title: String, json: String, now: Long): Long {
        val id = conversationDao.insertConversation(
            ConversationEntity(
                title = title,
                workspacePath = null,
                messagesJson = json,
                createdAt = now,
                updatedAt = now,
                scope = ConversationScope.OPENCODE.wireName
            )
        )
        currentRoomConversationId = id
        _uiState.update { it.copy(conversationId = id.toString()) }
        return id
    }

    private fun serializeMessagesToJson(
        messages: List<UiMessage>,
        opencodeSessionId: String?
    ): String {
        val root = JSONObject().apply {
            put("opencodeSessionId", opencodeSessionId ?: JSONObject.NULL)
            put("messages", JSONArray().apply {
                messages.forEach { msg -> put(serializeMessage(msg)) }
            })
        }
        return root.toString()
    }

    private fun serializeMessage(msg: UiMessage): JSONObject = JSONObject().apply {
        put("id", msg.id)
        put("role", msg.role.name)
        put("content", msg.content)
        put("timestamp", msg.timestamp)
        msg.thinking?.let { put("thinking", it) }
        put("steps", JSONArray().apply {
            msg.steps.forEach { step ->
                when (step) {
                    is MessageStep.Text -> put(JSONObject().apply {
                        put("id", step.id)
                        put("type", "text")
                        put("content", step.content)
                    })
                    is MessageStep.ToolCall -> put(JSONObject().apply {
                        put("id", step.id)
                        put("type", "toolCall")
                        put("execution", JSONObject().apply {
                            put("toolCallId", step.execution.toolCallId)
                            put("name", step.execution.name)
                            put("status", step.execution.status.name)
                            put("result", step.execution.result ?: JSONObject.NULL)
                        })
                    })
                }
            }
        })
    }

    private fun parseMessagesFromJson(json: String): List<UiMessage> {
        return runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val steps = mutableListOf<MessageStep>()
                val tools = mutableListOf<ToolExecution>()
                val stepsArr = obj.optJSONArray("steps")
                if (stepsArr != null) {
                    for (j in 0 until stepsArr.length()) {
                        val step = stepsArr.optJSONObject(j) ?: continue
                        when (step.optString("type")) {
                            "text" -> steps.add(
                                MessageStep.Text(
                                    id = step.optString("id", UUID.randomUUID().toString()),
                                    content = step.optString("content")
                                )
                            )
                            "toolCall" -> {
                                val exec = step.optJSONObject("execution") ?: continue
                                val execution = ToolExecution(
                                    toolCallId = exec.optString("toolCallId", UUID.randomUUID().toString()),
                                    name = exec.optString("name"),
                                    arguments = emptyMap(),
                                    status = runCatching {
                                        ToolStatus.valueOf(exec.optString("status"))
                                    }.getOrDefault(ToolStatus.SUCCESS),
                                    result = exec.optString("result").takeIf { it.isNotBlank() && it != "null" }
                                )
                                tools.add(execution)
                                steps.add(MessageStep.ToolCall(id = step.optString("id", UUID.randomUUID().toString()), execution = execution))
                            }
                        }
                    }
                }
                UiMessage(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    role = runCatching { MessageRole.valueOf(obj.optString("role")) }
                        .getOrDefault(MessageRole.USER),
                    content = obj.optString("content"),
                    thinking = obj.optString("thinking").takeIf { it.isNotBlank() },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    toolExecutions = tools,
                    steps = steps
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractOpencodeSessionId(json: String): String? = runCatching {
        val root = JSONObject(json)
        root.optString("opencodeSessionId").takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

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
