package com.amaya.intelligence.data.local.dao

import androidx.room.*
import com.amaya.intelligence.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope
        FROM conversations
        WHERE scope = 'local'
        ORDER BY updated_at DESC
    """)
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query("""
        SELECT id, title, workspace_path, created_at, updated_at, '' AS messages_json, scope
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

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
