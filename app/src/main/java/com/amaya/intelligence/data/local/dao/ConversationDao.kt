package com.amaya.intelligence.data.local.dao

import androidx.room.*
import com.amaya.intelligence.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local' AND assistant_mode = :assistantMode
          AND ((:ownerId IS NULL AND owner_id IS NULL) OR owner_id = :ownerId)
        ORDER BY updated_at DESC
    """)
    fun observeOwnedConversations(assistantMode: String, ownerId: String?): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local' AND assistant_mode = 'AGENT' AND agent_id = :agentId
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    fun observeAgentConversation(agentId: Long): Flow<List<ConversationEntity>>

    @Query("""
        SELECT * FROM conversations
        WHERE scope = 'local' AND assistant_mode = 'AGENT' AND agent_id = :agentId
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    suspend fun getAgentConversation(agentId: Long): ConversationEntity?

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope, assistant_mode, owner_id, agent_id
        FROM conversations
        WHERE scope = :scope
        ORDER BY updated_at DESC
    """)
    fun observeConversationsByScope(scope: String): Flow<List<ConversationEntity>>

    /**
     * One-shot snapshot of conversations in a scope, with the full `messages_json`
     * body. Used when we need to inspect payload metadata (e.g. opencode session id).
     */
    @Query("""
        SELECT *
        FROM conversations
        WHERE scope = :scope
        ORDER BY updated_at DESC
    """)
    suspend fun getConversationsByScope(scope: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

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
