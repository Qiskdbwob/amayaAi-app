package com.amaya.intelligence.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.amaya.intelligence.data.local.entity.AgentEntity
import com.amaya.intelligence.data.local.entity.AgentGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_groups ORDER BY updated_at DESC, name")
    fun observeGroups(): Flow<List<AgentGroupEntity>>

    @Query("SELECT * FROM agents ORDER BY name")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE group_id = :groupId ORDER BY name")
    fun observeByGroup(groupId: Long): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agent_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): AgentGroupEntity?

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: Long): AgentEntity?

    @Query("SELECT * FROM agents WHERE group_id = :groupId ORDER BY name")
    suspend fun getByGroup(groupId: Long): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: AgentGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(agent: AgentEntity): Long

    @Update
    suspend fun updateGroup(group: AgentGroupEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Delete
    suspend fun deleteGroup(group: AgentGroupEntity)

    @Delete
    suspend fun delete(agent: AgentEntity)

    @Transaction
    suspend fun createGroup(group: AgentGroupEntity, members: List<AgentEntity>): Long {
        val groupId = insertGroup(group)
        members.forEach { insert(it.copy(groupId = groupId)) }
        return groupId
    }
}
