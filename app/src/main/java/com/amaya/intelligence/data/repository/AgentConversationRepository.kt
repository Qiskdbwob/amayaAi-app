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

    private fun delegationMetadata(source: AgentEntity) = mapOf(
        "delegation" to "incoming",
        "sourceAgentId" to source.localId.toString(),
        "sourceAgentDatabaseId" to source.id.toString(),
        "sourceAgentName" to source.name,
        "sourceAgentMention" to agentMentionMarkdown(source.localId, source.name)
    )
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
