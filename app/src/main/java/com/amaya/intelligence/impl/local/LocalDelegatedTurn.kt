package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceSessionManager

import com.amaya.intelligence.data.remote.api.MessageRole


import com.amaya.intelligence.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray


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

