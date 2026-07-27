package com.amaya.intelligence.impl.local


import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.entity.ConversationEntity

import com.amaya.intelligence.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock


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

