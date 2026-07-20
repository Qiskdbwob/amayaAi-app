package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.local.entity.ConversationScope
import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
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
        val history = delegationHistoryFromJson(existing?.messagesJson.orEmpty())
        val incoming = request
        val messagesJson = appendDelegationMessage(
            existing?.messagesJson.orEmpty(),
            MessageRole.USER,
            incoming,
            mapOf(
                "delegation" to "incoming",
                "sourceAgentId" to source.id.toString(),
                "sourceAgentName" to source.name,
                "sourceAgentMention" to agentMentionMarkdown(source.id, source.name)
            ),
            now
        )
        val conversationId = if (existing == null) {
            conversationDao.insertConversation(
                ConversationEntity(
                    title = target.name,
                    workspacePath = workspacePath,
                    messagesJson = messagesJson,
                    scope = ConversationScope.LOCAL.wireName,
                    assistantMode = AssistantMode.AGENT.name,
                    ownerId = groupId.toString(),
                    agentId = target.id,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            conversationDao.updateConversation(existing.copy(messagesJson = messagesJson, updatedAt = now))
            existing.id
        }
        AgentDelegationContext(conversationId, history, incoming)
    }

    suspend fun appendDelegationResponse(
        conversationId: Long,
        source: AgentEntity,
        result: SubagentResult,
        failed: Boolean
    ) = lock.withLock {
        val existing = conversationDao.getConversationById(conversationId) ?: return@withLock
        val now = System.currentTimeMillis()
        val messagesJson = appendDelegationTurn(
            existing.messagesJson,
            result,
            mapOf(
                "delegation" to if (failed) "failed" else "response",
                "sourceAgentId" to source.id.toString(),
                "sourceAgentName" to source.name,
                "completedAt" to result.completedAt.toString()
            )
        )
        conversationDao.updateConversation(existing.copy(messagesJson = messagesJson, updatedAt = now))
    }
}

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
                    else -> continue
                }
                item.optString("content").takeIf(String::isNotBlank)?.let { add(ChatMessage(role, it)) }
            }
        }
    }.getOrDefault(emptyList())
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
    val canonicalHistory = JSONArray()
    var visibleText = ""
    result.turnMessages.forEach { message ->
        when {
            message.role == MessageRole.ASSISTANT && !message.toolCalls.isNullOrEmpty() -> {
                message.content?.takeIf(String::isNotBlank)?.let { text ->
                    steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("content", text))
                    canonicalHistory.put(JSONObject().put("kind", "assistant_text").put("text", text).toString())
                }
                message.toolCalls.orEmpty().forEach { call ->
                    val execution = JSONObject()
                        .put("toolCallId", call.id)
                        .put("name", call.name)
                        .put("status", "SUCCESS")
                        .put("arguments", JSONObject(call.arguments))
                        .put("metadata", JSONObject(mapOf("source" to "local")))
                    val stepExecution = JSONObject(execution.toString())
                    executions.put(execution)
                    executionsById[call.id] = execution
                    stepExecutionsById[call.id] = stepExecution
                    steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "toolCall").put("execution", stepExecution))
                    canonicalHistory.put(JSONObject().put("kind", "assistant_tool_call").put("id", call.id).put("name", call.name).put("arguments", JSONObject(call.arguments)).toString())
                }
            }
            message.role == MessageRole.TOOL -> {
                val toolResult = message.toolResult ?: return@forEach
                listOfNotNull(executionsById[toolResult.toolCallId], stepExecutionsById[toolResult.toolCallId]).forEach { execution ->
                    execution.put("result", toolResult.content)
                    execution.put("status", if (toolResult.isError) "ERROR" else "SUCCESS")
                }
                canonicalHistory.put(JSONObject().put("kind", "tool_result").put("id", toolResult.toolCallId).put("result", toolResult.content).put("isError", toolResult.isError).toString())
            }
            message.role == MessageRole.ASSISTANT -> {
                message.content?.takeIf(String::isNotBlank)?.let { text ->
                    visibleText = text
                    steps.put(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("content", text))
                    canonicalHistory.put(JSONObject().put("kind", "assistant_text").put("text", text).toString())
                }
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
        put("canonicalHistory", canonicalHistory)
        put("metadata", JSONObject(metadata + ("completedAt" to result.completedAt.toString())))
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
