package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.repository.AiRepository
import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.data.repository.SessionMemoryRepository

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.util.LocalStreamPerfLog
import com.amaya.intelligence.tools.ConfirmationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context


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

