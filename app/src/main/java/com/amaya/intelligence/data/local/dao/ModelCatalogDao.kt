package com.amaya.intelligence.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.amaya.intelligence.data.local.entity.ManualModelOverrideEntity
import com.amaya.intelligence.data.local.entity.ModelAliasEntity
import com.amaya.intelligence.data.local.entity.ModelCatalogEntity
import com.amaya.intelligence.data.local.entity.ModelRouteEntity
import com.amaya.intelligence.data.local.entity.ProviderModelAvailabilityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelCatalogDao {
    @Query("SELECT * FROM model_catalog ORDER BY provider_id ASC, display_name ASC")
    fun observeCatalog(): Flow<List<ModelCatalogEntity>>

    @Query("SELECT * FROM model_catalog WHERE id = :id LIMIT 1")
    suspend fun getModel(id: String): ModelCatalogEntity?

    @Upsert
    suspend fun upsertCatalog(entries: List<ModelCatalogEntity>)

    @Upsert
    suspend fun upsertAvailability(entries: List<ProviderModelAvailabilityEntity>)

    @Query("SELECT * FROM provider_model_availability WHERE provider_connection_id = :connectionId")
    fun observeAvailability(connectionId: String): Flow<List<ProviderModelAvailabilityEntity>>

    @Upsert
    suspend fun upsertOverride(override: ManualModelOverrideEntity)

    @Query("SELECT * FROM manual_model_overrides")
    fun observeOverrides(): Flow<List<ManualModelOverrideEntity>>

    @Upsert
    suspend fun upsertAlias(alias: ModelAliasEntity)

    @Upsert
    suspend fun upsertRoutes(routes: List<ModelRouteEntity>)

    @Query("SELECT * FROM model_aliases WHERE enabled = 1 ORDER BY alias ASC")
    fun observeAliases(): Flow<List<ModelAliasEntity>>

    @Query("SELECT * FROM model_routes WHERE alias = :alias AND enabled = 1 ORDER BY priority ASC")
    suspend fun getRoutes(alias: String): List<ModelRouteEntity>
}
