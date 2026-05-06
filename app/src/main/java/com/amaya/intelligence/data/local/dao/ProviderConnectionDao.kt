package com.amaya.intelligence.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.amaya.intelligence.data.local.entity.ProviderConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderConnectionDao {
    @Query("SELECT * FROM provider_connections ORDER BY display_name ASC")
    fun observeAll(): Flow<List<ProviderConnectionEntity>>

    @Query("SELECT * FROM provider_connections WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderConnectionEntity?

    @Upsert
    suspend fun upsert(entity: ProviderConnectionEntity)

    @Query("DELETE FROM provider_connections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM provider_connections WHERE id LIKE 'legacy_agent_connection_%' AND id NOT IN (:activeIds)")
    suspend fun deleteLegacyConnectionsNotIn(activeIds: List<String>)

    @Query("DELETE FROM provider_connections WHERE id LIKE 'legacy_agent_connection_%'")
    suspend fun deleteAllLegacyConnections()
}
