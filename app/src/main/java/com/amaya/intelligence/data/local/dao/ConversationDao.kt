package com.amaya.intelligence.data.local.dao

import androidx.room.*
import com.amaya.intelligence.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

private const val CONVERSATION_CHUNK_CHARS = 256_000

private suspend fun readConversationColumn(readChunk: suspend (Int) -> String?): String = buildString {
    var offset = 1
    while (true) {
        val chunk = readChunk(offset).orEmpty()
        append(chunk)
        if (chunk.length < CONVERSATION_CHUNK_CHARS) return@buildString
        offset += chunk.length
    }
}

@Dao
interface ConversationDao {
    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local' AND assistant_mode = :assistantMode
          AND ((:ownerId IS NULL AND owner_id IS NULL) OR owner_id = :ownerId)
        ORDER BY updated_at DESC
    """)
    fun observeOwnedConversations(assistantMode: String, ownerId: String?): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local' AND assistant_mode = 'AGENT' AND agent_id = :agentId
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    fun observeAgentConversation(agentId: Long): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local' AND assistant_mode = 'AGENT' AND agent_id = :agentId
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    suspend fun getAgentConversationHeader(agentId: Long): ConversationEntity?

    @Transaction
    suspend fun getAgentConversation(agentId: Long): ConversationEntity? =
        getAgentConversationHeader(agentId)?.let { getConversationById(it.id) }

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = :scope
        ORDER BY updated_at DESC
    """)
    fun observeConversationsByScope(scope: String): Flow<List<ConversationEntity>>

    /** Opencode stores its remote session ID in `messages_json`; do not scan other scopes' payloads. */
    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'opencode'
        ORDER BY updated_at DESC
    """)
    suspend fun getOpencodeConversations(): List<ConversationEntity>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, '' AS context_messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations WHERE id = :id
    """)
    suspend fun getConversationHeader(id: Long): ConversationEntity?

    @Query("SELECT substr(messages_json, :offset, :length) FROM conversations WHERE id = :id")
    suspend fun getMessagesChunk(id: Long, offset: Int, length: Int): String?

    @Query("SELECT substr(context_messages_json, :offset, :length) FROM conversations WHERE id = :id")
    suspend fun getContextMessagesChunk(id: Long, offset: Int, length: Int): String?

    /** Reads large JSON columns in CursorWindow-safe chunks. */
    @Transaction
    suspend fun getConversationById(id: Long): ConversationEntity? {
        val header = getConversationHeader(id) ?: return null
        return header.copy(
            messagesJson = readConversationColumn { offset -> getMessagesChunk(id, offset, CONVERSATION_CHUNK_CHARS) },
            contextMessagesJson = readConversationColumn { offset -> getContextMessagesChunk(id, offset, CONVERSATION_CHUNK_CHARS) }
        )
    }

    suspend fun getById(id: Long): ConversationEntity? = getConversationById(id)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET owner_id = :projectId WHERE scope = 'local' AND assistant_mode = 'PROJECT' AND workspace_path = :workspacePath AND (owner_id = :workspacePath OR owner_id IS NULL)")
    suspend fun remapProjectOwner(workspacePath: String, projectId: String)

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET context_messages_json = :contextMessagesJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateConversationContext(id: Long, contextMessagesJson: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET messages_json = '[]', context_messages_json = :contextMessagesJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun clearConversationHistory(id: Long, contextMessagesJson: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("DELETE FROM conversations WHERE scope = 'local' AND assistant_mode = :assistantMode AND owner_id = :ownerId")
    suspend fun deleteOwnedConversations(assistantMode: String, ownerId: String)

    @Query("DELETE FROM conversations WHERE scope = 'local' AND assistant_mode = 'AGENT' AND agent_id = :agentId")
    suspend fun deleteAgentConversations(agentId: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
