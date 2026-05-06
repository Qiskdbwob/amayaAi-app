package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.ModelCatalogDao
import com.amaya.intelligence.data.local.entity.ModelCatalogEntity
import com.amaya.intelligence.data.remote.api.KnownModelCatalog
import com.amaya.intelligence.data.remote.api.ModelCapability
import com.amaya.intelligence.data.remote.api.ModelCatalogEntry
import com.amaya.intelligence.data.remote.api.ModelCatalogSource
import com.amaya.intelligence.data.remote.api.ModelStatus
import com.amaya.intelligence.data.remote.api.ModelsDevClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local model catalog repository backed by Room.
 *
 * The repository seeds a built-in models.dev-shaped snapshot, then tries to sync
 * the live models.dev API. If the network is unavailable, callers keep using the
 * durable Room cache plus static fallback.
 */
@Singleton
class ModelCatalogRepository @Inject constructor(
    private val modelCatalogDao: ModelCatalogDao,
    private val modelsDevClient: ModelsDevClient
) {
    fun observeCatalog(): Flow<List<ModelCatalogEntry>> = modelCatalogDao.observeCatalog()
        .map { entries -> entries.map { it.toDomain() }.ifEmpty { KnownModelCatalog.entries }.withCodexSubscriptionEntries() }

    suspend fun seedBuiltInCatalogIfNeeded() {
        modelCatalogDao.upsertCatalog(KnownModelCatalog.entries.withCodexSubscriptionEntries().map { it.toEntity() })
    }

    suspend fun syncModelsDev(): Result<Int> = runCatching {
        val entries = modelsDevClient.fetchCatalog().withCodexSubscriptionEntries()
        if (entries.isNotEmpty()) {
            modelCatalogDao.upsertCatalog(entries.map { it.toEntity() })
        }
        entries.size
    }
}

private fun List<ModelCatalogEntry>.withCodexSubscriptionEntries(): List<ModelCatalogEntry> {
    val mirrored = filter { entry -> entry.providerId == "openai" }.map { entry ->
        entry.copy(
            id = "openai_codex_bridge/${entry.modelId}",
            providerId = "openai_codex_bridge",
            source = ModelCatalogSource.SUBSCRIPTION_TOOL,
            inputPricePerMillionTokens = null,
            outputPricePerMillionTokens = null,
            metadata = entry.metadata + mapOf(
                "providerName" to "OpenAI / ChatGPT / Codex",
                "sourceProviderId" to entry.providerId
            )
        )
    }
    return (this + mirrored).distinctBy { it.providerId to it.modelId }
}

private fun ModelCatalogEntry.toEntity(): ModelCatalogEntity = ModelCatalogEntity(
    id = id,
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    source = source.name,
    status = status.name,
    capabilitiesCsv = capabilities.joinToString(",") { it.name },
    inputPricePerMillionTokens = inputPricePerMillionTokens,
    outputPricePerMillionTokens = outputPricePerMillionTokens,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    releaseDate = releaseDate,
    knowledgeCutoff = knowledgeCutoff,
    lastSyncedAt = lastSyncedAt,
    metadataJson = JSONObject(metadata).toString()
)

private fun ModelCatalogEntity.toDomain(): ModelCatalogEntry = ModelCatalogEntry(
    id = id,
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    source = runCatching { ModelCatalogSource.valueOf(source) }.getOrDefault(ModelCatalogSource.MANUAL_ONLY),
    status = runCatching { ModelStatus.valueOf(status) }.getOrDefault(ModelStatus.AVAILABLE),
    capabilities = capabilitiesCsv.split(',').mapNotNull { raw ->
        raw.takeIf { it.isNotBlank() }?.let { runCatching { ModelCapability.valueOf(it) }.getOrNull() }
    }.toSet(),
    inputPricePerMillionTokens = inputPricePerMillionTokens,
    outputPricePerMillionTokens = outputPricePerMillionTokens,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    releaseDate = releaseDate,
    knowledgeCutoff = knowledgeCutoff,
    lastSyncedAt = lastSyncedAt,
    metadata = parseMetadata(metadataJson)
)

private fun parseMetadata(json: String): Map<String, String> = runCatching {
    val obj = JSONObject(json)
    buildMap {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            obj.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
        }
    }
}.getOrDefault(emptyMap())
