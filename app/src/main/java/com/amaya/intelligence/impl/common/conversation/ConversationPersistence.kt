package com.amaya.intelligence.impl.common.conversation

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationPersistence @Inject constructor(
    private val conversationDao: ConversationDao
) {
    suspend fun updatePayload(
        id: Long,
        messagesJson: String,
        contextMessagesJson: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
        transform: (ConversationEntity) -> ConversationEntity = { it }
    ): Boolean {
        val current = conversationDao.getConversationById(id) ?: return false
        conversationDao.updateConversation(
            transform(current).copy(
                messagesJson = messagesJson,
                contextMessagesJson = contextMessagesJson ?: current.contextMessagesJson,
                updatedAt = updatedAt
            )
        )
        return true
    }

    suspend fun insert(entity: ConversationEntity): Long =
        conversationDao.insertConversation(entity)

    suspend fun delete(id: Long): Boolean {
        if (conversationDao.getConversationHeader(id) == null) return false
        conversationDao.deleteConversationById(id)
        return true
    }
}
