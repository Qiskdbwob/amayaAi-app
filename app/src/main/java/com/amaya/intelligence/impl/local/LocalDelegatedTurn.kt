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

