package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceSessionManager



import com.amaya.intelligence.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray


internal suspend fun LocalIntelligenceService.sendMessageToConversationImpl(conversationId: Long, content: String): Boolean {
        val text = content.trim()
        if (text.isBlank() || activeTurns.containsKey(conversationId)) return false
        val entity = conversationDao.getConversationById(conversationId) ?: return false
        val messages = parseMessagesFromJson(entity.messagesJson).getOrNull() ?: return false
        val contextMessages = parseMessagesFromJson(entity.contextMessagesJson.ifBlank { entity.messagesJson }).getOrNull() ?: return false
        val settings = settingsManager.getSettings()
        val preferredModelKey = entity.agentId?.let { id -> agentDao.getById(id)?.defaultModelKeysJson }
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { array -> (0 until array.length()).map { array.optString(it) }.firstOrNull(String::isNotBlank) }
            ?: settings.activeSelection?.key.orEmpty()
        val modelParts = preferredModelKey.split('|', limit = 3)
        val selectedModel = modelParts.getOrNull(2).orEmpty().ifBlank { settings.activeSelection?.modelId.orEmpty() }
        val effort = if (modelParts.size == 3) settingsManager.getThinkingEffort(modelParts[1], modelParts[2]) else _uiState.value.effort
        val state = ChatUiState(
            messages = messages,
            contextMessages = contextMessages,
            selectedModel = selectedModel,
            workspacePath = entity.workspacePath,
            assistantMode = runCatching { AssistantMode.valueOf(entity.assistantMode) }.getOrDefault(AssistantMode.CHAT),
            ownerId = entity.ownerId,
            agentId = entity.agentId,
            modelOptions = _uiState.value.modelOptions,
            activeModelKey = preferredModelKey,
            conversationId = entity.id.toString(),
            effort = effort,
            sessionMode = IntelligenceSessionManager.SessionMode.LOCAL
        )
        return startTurn(text, emptyList(), state, projectVisible = false)
    }

