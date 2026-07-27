package com.amaya.intelligence.impl.bridge.windows.services

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.local.entity.ConversationScope
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.data.repository.AgentRuntimeTarget
import com.amaya.intelligence.data.repository.AUTO_COMPACTED_CONTEXT_PREFIX
import com.amaya.intelligence.data.repository.AiRepository

import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager

import com.amaya.intelligence.domain.models.ChatUiState
import com.amaya.intelligence.domain.models.ConnectionState
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.impl.local.toChatMessages
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeController

import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.common.mappers.ToolUiMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WindowsBridgeIntelligenceService @Inject constructor(
    private val aiRepository: AiRepository,
    private val conversationDao: ConversationDao,
    private val settingsManager: AiSettingsManager,
    private val bridgeController: WindowsBridgeController,
    @ApplicationScope private val scope: CoroutineScope
) : IntelligenceService {

    private val _uiState = MutableStateFlow(ChatUiState(
        sessionMode = IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE,
        connectionState = mapConnectionState(bridgeController.currentConnectionState())
    ))
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    override val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    private var chatJob: Job? = null
    private var currentConversationId: Long? = null
    private var currentAssistantMessageId: String? = null
    private val assistantTextBuffer = StringBuilder()

    /**
     * Compaction ledger for the active bridge conversation.
     *
     * The bridge has no separate model-context column, so without holding it here the ledger the
     * host just paid for would be discarded and re-derived on the next turn.
     */
    private var compactionLedger: String? = null
    private val conversationSaveMutex = Mutex()

    init {
        scope.launch {
            conversationDao.observeConversationsByScope(ConversationScope.WINDOWS_BRIDGE.wireName)
                .collect { _conversations.value = it }
        }
        scope.launch {
            settingsManager.settingsFlow.collect { settings ->
                val options = settings.connections.flatMap { connection ->
                    connection.visibleModels.mapNotNull { model ->
                        ModelUiMapper.mapConnectionModel(connection, model)
                    }
                }
                _uiState.update {
                    it.copy(
                        modelOptions = options,
                        activeModelKey = settings.activeSelection?.key.orEmpty(),
                        selectedModel = settings.activeSelection?.modelId.orEmpty(),
                        sessionMode = IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
                    )
                }
            }
        }
        scope.launch {
            while (true) {
                _uiState.update {
                    it.copy(connectionState = mapConnectionState(bridgeController.currentConnectionState()))
                }
                delay(500)
            }
        }
    }

    override fun sendMessage(content: String) {
        chatJob?.cancel()
        currentAssistantMessageId = null
        assistantTextBuffer.clear()

        val userMsg = UiMessage(role = MessageRole.USER, content = content)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                isLoading = true,
                isStreaming = true,
                error = null,
                sessionMode = IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
            )
        }

        chatJob = scope.launch {
            try {
                val conversationIdForTurn = persistCurrentConversation()
                // Expand tool calls and results instead of keeping role+content only: dropping them
                // left the model with no record of what it had already done on the paired PC.
                val ledgerContext = compactionLedger?.let {
                    listOf(ChatMessage(MessageRole.SYSTEM, AUTO_COMPACTED_CONTEXT_PREFIX + "\n" + it))
                }.orEmpty()
                val history = ledgerContext + _uiState.value.messages.flatMap { it.toChatMessages() }
                aiRepository.chat(
                    message = content,
                    conversationHistory = history.dropLast(1),
                    conversationId = conversationIdForTurn,
                    connectionId = _uiState.value.activeModelKey
                        .takeIf { it.startsWith("model|") }
                        ?.split('|', limit = 3)
                        ?.getOrNull(1),
                    selectedModel = _uiState.value.selectedModel,
                    effort = _uiState.value.effort,
                    runtimeTarget = AgentRuntimeTarget.WINDOWS_BRIDGE,
                    onConfirmation = { false }
                ).collect { handleAgentEvent(it) }
            } catch (e: Exception) {
                flushAssistantTextBuffer()
                _uiState.update { it.copy(error = e.message, isLoading = false, isStreaming = false, isAutoCompacting = false) }
            }
        }
    }

    override fun stopGeneration() {
        chatJob?.cancel()
        bridgeController.emergencyStop()
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                isStreaming = false,
                messages = state.messages.mapIndexed { index, message ->
                    if (index != state.messages.lastIndex) message else message.copy(
                        toolExecutions = message.toolExecutions.map { tool ->
                            if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
                                tool.copy(status = ToolStatus.ERROR, result = tool.result ?: "Stopped by user.")
                            } else tool
                        },
                        steps = message.steps.map { step ->
                            if (step is MessageStep.ToolCall &&
                                (step.execution.status == ToolStatus.RUNNING || step.execution.status == ToolStatus.PENDING)
                            ) {
                                step.copy(execution = step.execution.copy(status = ToolStatus.ERROR, result = step.execution.result ?: "Stopped by user."))
                            } else step
                        }
                    )
                }
            )
        }
    }

    override fun clearConversation() {
        chatJob?.cancel()
        currentConversationId = null
        compactionLedger = null
        currentAssistantMessageId = null
        assistantTextBuffer.clear()
        _uiState.update {
            it.copy(
                conversationId = null,
                messages = emptyList(),
                error = null,
                isLoading = false,
                isStreaming = false,
                totalInputTokens = 0,
                totalOutputTokens = 0,
                sessionMode = IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
            )
        }
    }

    override fun loadConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        scope.launch {
            val entity = conversationDao.getConversationById(longId)
                ?.takeIf { it.scope == ConversationScope.WINDOWS_BRIDGE.wireName }
                ?: return@launch
            currentConversationId = entity.id
            currentAssistantMessageId = null
            compactionLedger = null
            assistantTextBuffer.clear()
            _uiState.update {
                it.copy(
                    conversationId = entity.id.toString(),
                    messages = parseMessagesFromJson(entity.messagesJson),
                    totalInputTokens = 0,
                    totalOutputTokens = 0,
                    error = null,
                    sessionMode = IntelligenceSessionManager.SessionMode.WINDOWS_BRIDGE
                )
            }
        }
    }

    override fun deleteConversation(id: String) {
        val longId = id.toLongOrNull() ?: return
        scope.launch {
            val entity = conversationDao.getConversationById(longId)
            if (entity?.scope != ConversationScope.WINDOWS_BRIDGE.wireName) return@launch
            conversationDao.deleteConversationById(longId)
            if (currentConversationId == longId) clearConversation()
        }
    }

    override fun selectModel(modelKey: String) {
        scope.launch {
            val parts = modelKey.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != "model") return@launch
            settingsManager.setActiveModel(
                com.amaya.intelligence.data.remote.api.ActiveModelSelection(
                    connectionId = parts[1],
                    modelId = parts[2]
                )
            )
        }
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun setEffort(effort: com.amaya.intelligence.data.remote.api.ThinkingEffort) {
        _uiState.update { it.copy(effort = effort) }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> bufferAssistantTextDelta(event.text)
            is AgentEvent.ThinkingDelta -> { /* reasoning not surfaced in bridge mode */ }
            is AgentEvent.ToolCallStart -> {
                flushAssistantTextBuffer()
                val toolExec = ToolExecution(
                    toolCallId = event.toolCallId,
                    name = event.name,
                    arguments = event.arguments,
                    status = ToolStatus.RUNNING,
                    metadata = mapOf(
                        "source" to "windows_bridge",
                        "executionTarget" to "WINDOWS_BRIDGE",
                        "animateOnMount" to "true"
                    ),
                    uiMetadata = ToolUiMapper.getToolUiMetadata(
                        event.name,
                        event.arguments,
                        mapOf("executionTarget" to "WINDOWS_BRIDGE")
                    )
                )
                ensureAssistantMessage()
                updateCurrentAssistantMessage { msg ->
                    msg.copy(
                        toolExecutions = msg.toolExecutions + toolExec,
                        steps = msg.steps + MessageStep.ToolCall(execution = toolExec)
                    )
                }
            }
            is AgentEvent.ToolCallResult -> {
                flushAssistantTextBuffer()
                updateToolExecution(event.toolCallId) { tool ->
                    tool.copy(
                        result = event.result,
                        status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS
                    )
                }
            }
            is AgentEvent.ResponseItem -> Unit
            is AgentEvent.Usage -> {
                _uiState.update {
                    it.copy(
                        totalInputTokens = it.totalInputTokens + event.inputTokens,
                        totalOutputTokens = it.totalOutputTokens + event.outputTokens
                    )
                }
            }
            is AgentEvent.Incomplete -> {
                flushAssistantTextBuffer()
                _uiState.update { it.copy(error = event.reason, isLoading = false, isStreaming = false, isAutoCompacting = false) }
            }
            is AgentEvent.Error -> {
                flushAssistantTextBuffer()
                _uiState.update { it.copy(error = event.message, isLoading = false, isStreaming = false, isAutoCompacting = false) }
            }
            AgentEvent.Done -> {
                flushAssistantTextBuffer()
                markCurrentAssistantCompleted()
                _uiState.update { it.copy(isLoading = false, isStreaming = false, isAutoCompacting = false) }
                persistCurrentConversationAsync()
            }
            AgentEvent.NewIteration -> Unit
            is AgentEvent.Compacting -> _uiState.update { it.copy(isAutoCompacting = true) }
            is AgentEvent.Compacted -> {
                compactionLedger = event.ledger.takeIf(String::isNotBlank)
                _uiState.update { it.copy(isAutoCompacting = false) }
            }
            is AgentEvent.SubagentUpdate -> Unit
        }
    }

    private fun bufferAssistantTextDelta(text: String) {
        assistantTextBuffer.append(text)
        ensureAssistantMessage()
        updateCurrentAssistantMessage { msg ->
            msg.copy(content = msg.content + text, steps = appendTextStep(msg.steps, text))
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

    private fun flushAssistantTextBuffer() {
        assistantTextBuffer.clear()
    }

    private fun ensureAssistantMessage() {
        if (currentAssistantMessageId != null && _uiState.value.messages.any { it.id == currentAssistantMessageId }) return
        val msg = UiMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            metadata = mapOf("source" to "windows_bridge")
        )
        currentAssistantMessageId = msg.id
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun updateCurrentAssistantMessage(transform: (UiMessage) -> UiMessage) {
        val id = currentAssistantMessageId ?: return
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun updateToolExecution(toolCallId: String, transform: (ToolExecution) -> ToolExecution) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { msg ->
                val updatedTools = msg.toolExecutions.map { if (it.toolCallId == toolCallId) transform(it) else it }
                val updatedSteps = msg.steps.map { step ->
                    if (step is MessageStep.ToolCall && step.execution.toolCallId == toolCallId) {
                        step.copy(execution = transform(step.execution))
                    } else step
                }
                msg.copy(toolExecutions = updatedTools, steps = updatedSteps)
            })
        }
        persistCurrentConversationAsync()
    }

    private fun markCurrentAssistantCompleted() {
        val id = currentAssistantMessageId ?: return
        val nowMs = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id == id) {
                    val durationMs = msg.thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
                    msg.copy(
                        metadata = msg.metadata + ("completedAt" to nowMs.toString()),
                        isThinking = false,
                        thinkingDurationMs = msg.thinkingDurationMs ?: durationMs
                    )
                } else msg
            })
        }
    }

    private fun persistCurrentConversationAsync() {
        scope.launch { persistCurrentConversation() }
    }

    private suspend fun persistCurrentConversation(): Long? = conversationSaveMutex.withLock {
        val messages = _uiState.value.messages
        if (messages.none { it.content.isNotBlank() || it.toolExecutions.isNotEmpty() }) return@withLock currentConversationId
        val now = System.currentTimeMillis()
        val title = messages.firstOrNull { it.role == MessageRole.USER }?.content
            ?.split("\\s+".toRegex())?.take(5)?.joinToString(" ")?.take(50)
            ?.ifBlank { "Windows Bridge Chat" } ?: "Windows Bridge Chat"
        val messagesJson = serializeMessagesToJson(messages)
        val existingId = currentConversationId
        return@withLock if (existingId != null) {
            val existing = conversationDao.getConversationById(existingId)
            if (existing != null && existing.scope == ConversationScope.WINDOWS_BRIDGE.wireName) {
                conversationDao.updateConversation(existing.copy(messagesJson = messagesJson, updatedAt = now))
                existingId
            } else {
                currentConversationId = null
                insertConversation(title, messagesJson, now)
            }
        } else {
            insertConversation(title, messagesJson, now)
        }
    }

    private suspend fun insertConversation(title: String, messagesJson: String, now: Long): Long {
        val id = conversationDao.insertConversation(
            ConversationEntity(
                title = title,
                workspacePath = null,
                messagesJson = messagesJson,
                createdAt = now,
                updatedAt = now,
                scope = ConversationScope.WINDOWS_BRIDGE.wireName
            )
        )
        currentConversationId = id
        _uiState.update { it.copy(conversationId = id.toString()) }
        return id
    }

    private fun serializeMessagesToJson(messages: List<UiMessage>): String = JSONArray().apply {
        messages.forEach { msg ->
            put(JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("metadata", JSONObject(msg.metadata))
                put("toolExecutions", JSONArray().apply {
                    msg.toolExecutions.forEach { put(serializeToolExecution(it)) }
                })
                put("steps", JSONArray().apply {
                    msg.steps.forEach { step ->
                        when (step) {
                            is MessageStep.Thinking -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "thinking")
                                put("text", step.text)
                                put("isStreaming", step.isStreaming)
                                step.startedAt?.let { put("startedAt", it) }
                                step.durationMs?.let { put("durationMs", it) }
                            })
                            is MessageStep.Text -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "text")
                                put("content", step.content)
                            })
                            is MessageStep.ToolCall -> put(JSONObject().apply {
                                put("id", step.id)
                                put("type", "toolCall")
                                put("execution", serializeToolExecution(step.execution))
                            })
                        }
                    }
                })
            })
        }
    }.toString()

    private fun serializeToolExecution(exec: ToolExecution): JSONObject = JSONObject().apply {
        put("toolCallId", exec.toolCallId)
        put("name", exec.name)
        put("arguments", JSONObject(exec.arguments))
        put("result", exec.result ?: JSONObject.NULL)
        put("status", exec.status.name)
        put("metadata", JSONObject(exec.metadata))
    }

    private fun parseMessagesFromJson(json: String): List<UiMessage> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val metadata = obj.optJSONObject("metadata")?.toStringMap() ?: emptyMap()
                val tools = obj.optJSONArray("toolExecutions")?.let { execArr ->
                    (0 until execArr.length()).map { parseToolExecution(execArr.getJSONObject(it)) }
                } ?: emptyList()
                val steps = obj.optJSONArray("steps")?.let { stepsArr ->
                    (0 until stepsArr.length()).mapNotNull { idx ->
                        val step = stepsArr.getJSONObject(idx)
                        when (step.optString("type")) {
                            "thinking" -> MessageStep.Thinking(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                text = step.optString("text"),
                                isStreaming = step.optBoolean("isStreaming"),
                                startedAt = step.optLong("startedAt", 0L).takeIf { it > 0 },
                                durationMs = step.optLong("durationMs", 0L).takeIf { it > 0 }
                            )
                            "text" -> MessageStep.Text(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                content = step.optString("content")
                            )
                            "toolCall" -> MessageStep.ToolCall(
                                id = step.optString("id", UUID.randomUUID().toString()),
                                execution = parseToolExecution(step.getJSONObject("execution"))
                            )
                            else -> null
                        }
                    }
                } ?: emptyList()
                UiMessage(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    role = runCatching { MessageRole.valueOf(obj.optString("role")) }.getOrDefault(MessageRole.USER),
                    content = obj.optString("content"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    metadata = metadata,
                    toolExecutions = tools,
                    steps = steps
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseToolExecution(obj: JSONObject): ToolExecution {
        val args = obj.optJSONObject("arguments")?.toAnyMap() ?: emptyMap()
        val metadata = obj.optJSONObject("metadata")?.toStringMap() ?: emptyMap()
        val name = obj.optString("name")
        return ToolExecution(
            toolCallId = obj.optString("toolCallId", UUID.randomUUID().toString()),
            name = name,
            arguments = args,
            result = obj.optString("result").takeIf { it.isNotBlank() && it != "null" },
            status = runCatching { ToolStatus.valueOf(obj.optString("status")) }.getOrDefault(ToolStatus.PENDING),
            metadata = metadata,
            uiMetadata = ToolUiMapper.getToolUiMetadata(name, args, metadata)
        )
    }

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key -> put(key, optString(key, "")) }
    }

    private fun JSONObject.toAnyMap(): Map<String, Any?> = buildMap {
        keys().forEach { key -> put(key, opt(key)) }
    }

    private fun formatTokenCount(count: Int): String = when {
        count >= 1_000_000 -> if (count % 1_000_000 == 0) "${count / 1_000_000}M" else String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> if (count % 1_000 == 0) "${count / 1_000}k" else String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
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
