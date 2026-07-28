package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceSessionManager

import com.amaya.intelligence.data.remote.api.MessageRole


import com.amaya.intelligence.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.amaya.intelligence.tools.SubagentResult
import com.amaya.intelligence.util.StreamDebugLog
import org.json.JSONArray
import org.json.JSONObject


internal suspend fun LocalIntelligenceService.runDelegatedAgentTurnImpl(conversationId: Long, request: String): SubagentResult {
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
        StreamDebugLog.event(conversationId, null, "DELEGATE_START", "requestChars=${request.length} storedMessages=${messages.size}")
        check(startTurn(request, emptyList(), state, projectVisible = false, preexistingUserMessage = true)) {
            "Delegated session is already streaming"
        }
        val turn = activeTurns[conversationId] ?: error("Delegated session did not start")
        StreamDebugLog.event(conversationId, turn.turnId, "DELEGATE_TURN_STARTED")
        try {
            turn.job?.join()
        } finally {
            if (!currentCoroutineContext().isActive) turn.job?.cancelAndJoin()
        }
        val persisted = conversationDao.getConversationById(conversationId)
            ?.let { parseMessagesFromJson(it.messagesJson).getOrNull() }
            .orEmpty()
        // Restrict final-response lookup to this delegation. A stale assistant elsewhere can hide
        // an empty or failed response from the current turn.
        val newMessages = persisted.drop(messages.size)
        val turnMessages = newMessages.flatMap { it.toChatMessages() }
        val completed = newMessages.lastOrNull { message ->
            message.role == MessageRole.ASSISTANT && message.metadata["turnStatus"] == "completed"
        } ?: newMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val summary = completedDelegatedResponse(newMessages, turn.state.error)
        StreamDebugLog.event(conversationId, turn.turnId, "DELEGATE_END", "newMessages=${newMessages.size} assistantChars=${summary.length} status=${completed?.metadata?.get("turnStatus") ?: "missing"}")
        return SubagentResult(
            taskName = entity.title,
            summary = summary,
            turnMessages = turnMessages,
            startedAt = completed?.timestamp ?: System.currentTimeMillis(),
            completedAt = System.currentTimeMillis()
        )
    }

internal fun completedDelegatedResponse(messages: List<UiMessage>, error: String?): String {
    val completed = messages.lastOrNull { message ->
        message.role == MessageRole.ASSISTANT && message.metadata["turnStatus"] == "completed"
    } ?: messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val responseItemText = completed?.responseItems.orEmpty().asSequence()
        .mapNotNull(::responseItemText)
        .joinToString("\n")
        .takeIf(String::isNotBlank)
    return completed?.content?.takeIf(String::isNotBlank)
        ?: responseItemText
        ?: error?.let { "[ERROR] $it" }
        ?: "[INCOMPLETE] No final response."
}

private fun responseItemText(raw: String): String? = runCatching {
    val item = JSONObject(raw)
    when (item.optString("type")) {
        "message" -> item.optJSONArray("content")?.let { content ->
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("\n")
        }
        "output_text" -> item.optString("text")
        else -> null
    }?.takeIf(String::isNotBlank)
}.getOrNull()

