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


internal suspend fun LocalIntelligenceService.persistConversationStart(state: ChatUiState, existingId: Long?): Long? = conversationSaveMutex.withLock {
        val messagesJson = serializeMessagesToJson(state.messages)
        val contextMessagesJson = serializeMessagesToJson(state.contextMessages)
        val now = System.currentTimeMillis()
        if (existingId != null) {
            val existing = conversationDao.getConversationById(existingId) ?: return@withLock null
            conversationDao.updateConversation(existing.copy(
                workspacePath = state.workspacePath,
                assistantMode = state.assistantMode.name,
                ownerId = state.ownerId,
                agentId = state.agentId,
                messagesJson = messagesJson,
                contextMessagesJson = contextMessagesJson,
                updatedAt = now
            ))
            return@withLock existingId
        }
        val title = state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.split("\\s+".toRegex())?.take(5)?.joinToString(" ")?.take(50).orEmpty().ifBlank { "New Conversation" }
        conversationDao.insertConversation(ConversationEntity(
            title = title,
            workspacePath = state.workspacePath,
            assistantMode = state.assistantMode.name,
            ownerId = state.ownerId,
            agentId = state.agentId,
            messagesJson = messagesJson,
            contextMessagesJson = contextMessagesJson,
            createdAt = now,
            updatedAt = now
        ))
    }

internal suspend fun LocalIntelligenceService.persistTurn(turn: LocalIntelligenceService.LocalTurn) = conversationSaveMutex.withLock {
        val existing = conversationDao.getConversationById(turn.conversationId) ?: return@withLock
        // The transcript keeps everything; the model-context column sheds tool payload it will never
        // replay, so a long tool-heavy conversation stops growing without bound on disk.
        val storedContext = digestOldToolPayloads(turn.state.contextMessages)
        turn.state = turn.state.copy(contextMessages = storedContext)
        conversationDao.updateConversation(existing.copy(
            messagesJson = serializeMessagesToJson(turn.state.messages),
            contextMessagesJson = serializeMessagesToJson(storedContext),
            updatedAt = System.currentTimeMillis()
        ))
    }

