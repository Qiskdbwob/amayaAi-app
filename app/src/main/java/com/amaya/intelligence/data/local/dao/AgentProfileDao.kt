package com.amaya.intelligence.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.amaya.intelligence.data.local.entity.AgentProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentProfileDao {
    @Query("SELECT * FROM agent_profiles ORDER BY name ASC")
    fun observeAll(): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentProfileEntity?

    @Upsert
    suspend fun upsert(entity: AgentProfileEntity)

    @Query("DELETE FROM agent_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM agent_profiles WHERE legacy_agent_config_json != '{}' AND id NOT IN (:activeIds)")
    suspend fun deleteMirroredProfilesNotIn(activeIds: List<String>)

    @Query("DELETE FROM agent_profiles WHERE legacy_agent_config_json != '{}'")
    suspend fun deleteAllMirroredProfiles()
}
