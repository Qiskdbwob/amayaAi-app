package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.local.entity.ConversationScope
import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolCallMessage
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.domain.models.agentMentionMarkdown
import com.amaya.intelligence.tools.SubagentResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class AgentConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao
) {
    private val lock = Mutex()

    suspend fun appendDelegationRequest(
        groupId: Long,
        source: AgentEntity,
        target: AgentEntity,
        workspacePath: String?,
        request: String
    ): AgentDelegationContext = lock.withLock {
        val now = System.currentTimeMillis()
        val existing = conversationDao.getAgentConversation(target.id)
        val existingContext = existing?.contextMessagesJson
            ?.takeIf(String::isNotBlank)
            ?: existing?.messagesJson.orEmpty()
        val history = delegationHistoryFromJson(existingContext)
        val incoming = request
        val modelIncoming = delegationPrompt(source, request)
        val messagesJson = appendDelegationMessage(
            existing?.messagesJson.orEmpty(),
            MessageRole.USER,
            incoming,
            mapOf(
                "delegation" to "incoming",
                "sourceAgentId" to source.localId.toString(),
                "sourceAgentDatabaseId" to source.id.toString(),
                "sourceAgentName" to source.name,
                "sourceAgentMention" to agentMentionMarkdown(source.localId, source.name)
            ),
            now
        )
        val conversationId = if (existing == null) {
            conversationDao.insertConversation(
                ConversationEntity(
                    title = target.name,
                    workspacePath = workspacePath,
                    messagesJson = messagesJson,
                    contextMessagesJson = appendDelegationMessage(existingContext, MessageRole.USER, modelIncoming, delegationMetadata(source), now),
                    scope = ConversationScope.LOCAL.wireName,
                    assistantMode = AssistantMode.AGENT.name,
                    ownerId = groupId.toString(),
                    agentId = target.id,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            conversationDao.updateConversation(existing.copy(
                messagesJson = messagesJson,
                contextMessagesJson = appendDelegationMessage(existingContext, MessageRole.USER, modelIncoming, delegationMetadata(source), now),
                updatedAt = now
            ))
            existing.id
        }
        AgentDelegationContext(conversationId, history, incoming)
    }

    suspend fun hasDelegationCompletion(conversationId: Long, taskId: Long): Boolean = lock.withLock {
        if (taskId <= 0L) return@withLock false
        val existing = conversationDao.getConversationById(conversationId) ?: return@withLock false
        hasDelegationCompletion(existing.messagesJson, taskId) ||
            hasDelegationCompletion(existing.contextMessagesJson.ifBlank { existing.messagesJson }, taskId)
    }

    suspend fun appendDelegationCompletion(
        conversationId: Long,
        title: String,
        sourceAgentName: String,
        targetAgentName: String,
        result: SubagentResult,
        failed: Boolean,
        taskId: Long = -1L,
        deliveryOrder: Long = 0L
    ): Boolean = lock.withLock {
        val existing = conversationDao.getConversationById(conversationId) ?: return@withLock false
        // The task callback is process-wide and may be retried after a timeout. Repair both
        // projections every time, but append the marker only to the column that lacks it.
        val storedContext = existing.contextMessagesJson.ifBlank { existing.messagesJson }
        val metadata = delegationCompletionMetadata(sourceAgentName, targetAgentName, result.completedAt, taskId, deliveryOrder)
        val event = delegationCompletionMessage(title, result.summary, metadata, result.completedAt, failed)
        val messagesHadEvent = hasDelegationCompletion(existing.messagesJson, taskId)
        val contextHadEvent = hasDelegationCompletion(storedContext, taskId)
        val completedMessages = completeDelegationTools(existing.messagesJson, taskId, result.summary, failed)
        val completedContext = completeDelegationTools(storedContext, taskId, result.summary, failed)
        val messagesJson = if (messagesHadEvent) completedMessages else appendDelegationMessageJson(completedMessages, event)
        val contextJson = if (contextHadEvent) completedContext else appendDelegationMessageJson(completedContext, event)
        if (messagesJson != existing.messagesJson || contextJson != storedContext) {
            conversationDao.updateConversation(existing.copy(
                messagesJson = messagesJson,
                contextMessagesJson = contextJson,
                updatedAt = maxOf(existing.updatedAt, result.completedAt)
            ))
        }
        !messagesHadEvent || !contextHadEvent
    }

    private fun delegationMetadata(source: AgentEntity) = mapOf(
        "delegation" to "incoming",
        "sourceAgentId" to source.localId.toString(),
        "sourceAgentDatabaseId" to source.id.toString(),
        "sourceAgentName" to source.name,
        "sourceAgentMention" to agentMentionMarkdown(source.localId, source.name)
    )
}

internal fun delegationCompletionMetadata(
    sourceAgentName: String,
    targetAgentName: String,
    completedAt: Long,
    taskId: Long = -1L,
    deliveryOrder: Long = 0L
): Map<String, String> = buildMap {
    put("sourceAgentName", sourceAgentName)
    put("targetAgentName", targetAgentName)
    put("completedAt", completedAt.toString())
    if (taskId > 0L) put("delegationTaskId", taskId.toString())
    if (deliveryOrder > 0L) put("deliveryOrder", deliveryOrder.toString())
}

internal fun delegationCompletionMessage(
    title: String,
    output: String,
    metadata: Map<String, String>,
    timestamp: Long,
    failed: Boolean = false
) = com.amaya.intelligence.domain.models.conversationEventMessage(
    type = com.amaya.intelligence.domain.models.ConversationEventType.DELEGATION_COMPLETED,
    label = title,
    state = if (failed) com.amaya.intelligence.domain.models.ConversationEventState.FAILED else com.amaya.intelligence.domain.models.ConversationEventState.DONE,
    detail = output,
    timestamp = timestamp,
    metadata = metadata
)

internal fun completeDelegationTools(json: String, taskId: Long, result: String, failed: Boolean): String {
    if (taskId <= 0L) return json
    val array = runCatching { JSONArray(json) }.getOrElse { return json }
    val taskIdText = taskId.toString()
    val terminalStatus = if (failed) "ERROR" else "SUCCESS"
    val delegationState = if (failed) "failed" else "done"
    fun complete(execution: JSONObject) {
        execution.put("status", terminalStatus).put("result", result)
        val metadata = execution.optJSONObject("metadata") ?: JSONObject().also { execution.put("metadata", it) }
        metadata.put("delegationTaskId", taskIdText).put("delegationState", delegationState)
    }
    for (index in 0 until array.length()) {
        val message = array.optJSONObject(index) ?: continue
        val executions = message.optJSONArray("toolExecutions")
        for (toolIndex in 0 until (executions?.length() ?: 0)) {
            val execution = executions?.optJSONObject(toolIndex) ?: continue
            if (execution.optJSONObject("metadata")?.optString("delegationTaskId") == taskIdText) {
                complete(execution)
            }
        }
        val canonical = message.optJSONArray("canonicalHistory")
        for (canonicalIndex in 0 until (canonical?.length() ?: 0)) {
            val item = runCatching { JSONObject(canonical?.optString(canonicalIndex).orEmpty()) }.getOrNull() ?: continue
            if (item.optString("kind") == "tool_result" && item.optLong("deferredTaskId", -1L) == taskId) {
                item.put("result", result).put("isError", failed)
                canonical?.put(canonicalIndex, item.toString())
            }
        }
        val steps = message.optJSONArray("steps")
        for (stepIndex in 0 until (steps?.length() ?: 0)) {
            val step = steps?.optJSONObject(stepIndex) ?: continue
            val execution = step.optJSONObject("execution") ?: continue
            if (execution.optJSONObject("metadata")?.optString("delegationTaskId") == taskIdText) {
                complete(execution)
            }
        }
    }
    return array.toString()
}

internal fun hasDelegationCompletion(json: String, taskId: Long): Boolean {
    if (taskId <= 0L) return false
    val array = runCatching { JSONArray(json) }.getOrElse { return false }
    val taskIdText = taskId.toString()
    for (index in 0 until array.length()) {
        val message = array.optJSONObject(index) ?: continue
        val metadata = message.optJSONObject("metadata")
        if (metadata?.optString("eventType") == "delegation_completed" &&
            metadata.optString("delegationTaskId") == taskIdText
        ) return true
        val steps = message.optJSONArray("steps")
        for (stepIndex in 0 until (steps?.length() ?: 0)) {
            val stepMetadata = steps?.optJSONObject(stepIndex)?.optJSONObject("metadata") ?: continue
            if (stepMetadata.optString("eventType") == "delegation_completed" &&
                stepMetadata.optString("delegationTaskId") == taskIdText
            ) return true
        }
    }
    return false
}

internal fun appendDelegationMessageJson(json: String, message: com.amaya.intelligence.domain.models.UiMessage): String {
    val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
    val item = JSONObject().apply {
        put("id", message.id)
        put("role", message.role.name)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("metadata", JSONObject(message.metadata))
    }
    val deliveryOrder = message.metadata["deliveryOrder"]?.toLongOrNull()
    val insertion = deliveryOrder?.let { candidate ->
        (0 until array.length()).firstOrNull { index ->
            val metadata = array.optJSONObject(index)?.optJSONObject("metadata") ?: return@firstOrNull false
            metadata.optString("eventType") == "delegation_completed" &&
                metadata.optString("deliveryOrder").toLongOrNull()?.let { it > candidate } == true
        }
    }
    if (insertion == null) array.put(item)
    else {
        val ordered = JSONArray()
        for (index in 0 until array.length()) {
            if (index == insertion) ordered.put(item)
            ordered.put(array.opt(index))
        }
        return ordered.toString()
    }
    return array.toString()
}

internal fun delegationPrompt(source: AgentEntity, request: String): String =
    "[HOST-AUTHORITATIVE DELEGATION from ${source.name} (agent_id=${source.localId})]\n$request"

data class AgentDelegationContext(
    val conversationId: Long,
    val history: List<ChatMessage>,
    val incomingMessage: String
)

internal fun delegationHistoryFromJson(json: String): List<ChatMessage> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = when (item.optString("role")) {
                    MessageRole.USER.name -> MessageRole.USER
                    MessageRole.ASSISTANT.name -> MessageRole.ASSISTANT
                    MessageRole.SYSTEM.name -> MessageRole.SYSTEM
                    else -> continue
                }
                val canonical = item.optJSONArray("canonicalHistory")
                if (role == MessageRole.ASSISTANT && canonical != null) addAll(delegationCanonicalHistory(canonical))
                else add(ChatMessage(role, item.optString("content").takeIf(String::isNotBlank)))
            }
        }
    }.getOrDefault(emptyList())
}

private fun delegationCanonicalHistory(history: JSONArray): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    val text = StringBuilder()
    val calls = mutableListOf<ToolCallMessage>()
    fun flushAssistant() {
        if (text.isEmpty() && calls.isEmpty()) return
        messages += ChatMessage(MessageRole.ASSISTANT, text.toString().takeIf(String::isNotBlank), toolCalls = calls.toList().takeIf(List<ToolCallMessage>::isNotEmpty))
        text.clear()
        calls.clear()
    }
    for (index in 0 until history.length()) {
        val item = runCatching { JSONObject(history.getString(index)) }.getOrNull() ?: continue
        when (item.optString("kind")) {
            "assistant_text" -> text.append(item.optString("text"))
            "assistant_tool_call" -> calls += ToolCallMessage(
                id = item.optString("id"), name = item.optString("name"),
                arguments = item.optJSONObject("arguments")?.let { values -> buildMap { values.keys().forEach { key -> put(key, values.opt(key).takeUnless { it == JSONObject.NULL }) } } }.orEmpty(),
                metadata = item.optJSONObject("metadata")?.let { values -> buildMap { values.keys().forEach { key -> put(key, values.optString(key)) } } }.orEmpty()
            )
            "tool_result" -> {
                flushAssistant()
                messages += ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage(item.optString("id"), item.optString("result"), item.optBoolean("isError"), mapOf("toolName" to item.optString("name"))))
            }
        }
    }
    flushAssistant()
    return messages
}

internal fun appendDelegationTurn(
    json: String,
    result: SubagentResult,
    metadata: Map<String, String>
): String {
    val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
    val steps = JSONArray()
    val executions = JSONArray()
    val executionsById = mutableMapOf<String, JSONObject>()
    val stepExecutionsById = mutableMapOf<String, JSONObject>()
    var visibleText = ""
    result.turnMessages.forEach { message ->
        when {
            message.role == MessageRole.ASSISTANT && !message.toolCalls.isNullOrEmpty() -> {
                message.content?.takeIf(String::isNotBlank)?.let { text ->
                    visibleText = text
                    steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("content", text))
                }
                message.toolCalls.orEmpty().forEach { call ->
                    val execution = JSONObject().put("toolCallId", call.id).put("name", call.name).put("status", "SUCCESS").put("arguments", JSONObject(call.arguments))
                    val stepExecution = JSONObject(execution.toString())
                    executions.put(execution)
                    executionsById[call.id] = execution
                    stepExecutionsById[call.id] = stepExecution
                    steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "toolCall").put("execution", stepExecution))
                }
            }
            message.role == MessageRole.TOOL -> message.toolResult?.let { toolResult ->
                listOfNotNull(executionsById[toolResult.toolCallId], stepExecutionsById[toolResult.toolCallId]).forEach { execution ->
                    execution.put("result", toolResult.content).put("status", if (toolResult.isError) "ERROR" else "SUCCESS")
                }
            }
            message.role == MessageRole.ASSISTANT -> message.content?.takeIf(String::isNotBlank)?.let { text ->
                visibleText = text
                steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("content", text))
            }
        }
    }
    if (visibleText.isBlank()) visibleText = result.summary
    array.put(JSONObject().apply {
        put("id", UUID.randomUUID().toString())
        put("role", MessageRole.ASSISTANT.name)
        put("content", visibleText)
        put("timestamp", result.startedAt)
        put("toolExecutions", executions)
        put("steps", steps)
        put("metadata", JSONObject(metadata + ("turnStatus" to "completed") + ("completedAt" to result.completedAt.toString())))
    })
    return array.toString()
}

internal fun appendDelegationMessage(
    json: String,
    role: MessageRole,
    content: String,
    metadata: Map<String, String>,
    timestamp: Long = System.currentTimeMillis()
): String {
    val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
    array.put(JSONObject().apply {
        put("id", UUID.randomUUID().toString())
        put("role", role.name)
        put("content", content)
        put("timestamp", timestamp)
        put("metadata", JSONObject(metadata))
    })
    return array.toString()
}
