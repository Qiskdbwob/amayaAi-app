package com.amaya.intelligence.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.amaya.intelligence.data.local.entity.DelegationTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DelegationTaskDao {
    @Query("SELECT id, group_id, agent_id, request, status, NULL AS result, created_at, updated_at FROM delegation_tasks WHERE group_id = :groupId ORDER BY updated_at DESC")
    fun observeByGroup(groupId: Long): Flow<List<DelegationTaskEntity>>

    @Insert
    suspend fun insert(task: DelegationTaskEntity): Long

    @Query("SELECT * FROM delegation_tasks WHERE id = :id")
    suspend fun getById(id: Long): DelegationTaskEntity?

    @Query("SELECT * FROM delegation_tasks WHERE group_id = :groupId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestByGroup(groupId: Long): DelegationTaskEntity?

    @Query("SELECT * FROM delegation_tasks WHERE group_id = :groupId AND created_at >= :createdAt ORDER BY id ASC")
    suspend fun getByGroupSince(groupId: Long, createdAt: Long): List<DelegationTaskEntity>

    @Query("UPDATE delegation_tasks SET status = :status, result = :result, updated_at = :updatedAt WHERE id = :id")
    suspend fun complete(id: Long, status: String, result: String?, updatedAt: Long = System.currentTimeMillis())
}
