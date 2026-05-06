package com.amaya.intelligence.data.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches and normalizes the public models.dev catalog. */
@Singleton
class ModelsDevClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val URL = "https://models.dev/api.json"
    }

    suspend fun fetchCatalog(): List<ModelCatalogEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Amaya-Android/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("models.dev error ${response.code}: ${response.message}")
            val body = response.body?.string().orEmpty()
            parseCatalog(body)
        }
    }

    fun parseCatalog(json: String): List<ModelCatalogEntry> {
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        val entries = mutableListOf<ModelCatalogEntry>()
        val providerKeys = root.keys()
        while (providerKeys.hasNext()) {
            val rawProviderId = providerKeys.next()
            val providerId = mapModelsDevProviderId(rawProviderId)
            val provider = root.optJSONObject(rawProviderId) ?: continue
            val providerName = provider.optString("name", rawProviderId)
            val models = provider.optJSONObject("models") ?: continue
            val modelKeys = models.keys()
            while (modelKeys.hasNext()) {
                val key = modelKeys.next()
                val model = models.optJSONObject(key) ?: continue
                val modelId = model.optString("id", key)
                if (modelId.isBlank()) continue
                val displayName = model.optString("name", modelId)
                val modalities = model.optJSONObject("modalities")
                val inputModalities = modalities?.optJSONArray("input").toStringSet()
                val outputModalities = modalities?.optJSONArray("output").toStringSet()
                val capabilities = buildSet {
                    if ("text" in inputModalities) add(ModelCapability.TEXT_INPUT)
                    if ("text" in outputModalities) add(ModelCapability.TEXT_OUTPUT)
                    if (listOf("image", "pdf", "video").any { it in inputModalities }) add(ModelCapability.IMAGE_INPUT)
                    if ("image" in outputModalities) add(ModelCapability.IMAGE_OUTPUT)
                    if (model.optBoolean("tool_call", false)) add(ModelCapability.TOOL_CALLING)
                    if (model.optBoolean("reasoning", false)) add(ModelCapability.REASONING)
                    if (model.optBoolean("attachment", false)) add(ModelCapability.IMAGE_INPUT)
                    add(ModelCapability.STREAMING)
                    add(ModelCapability.JSON_MODE)
                }
                val cost = model.optJSONObject("cost")
                val limit = model.optJSONObject("limit")
                entries.add(
                    ModelCatalogEntry(
                        id = "$providerId/$modelId",
                        providerId = providerId,
                        modelId = modelId,
                        displayName = displayName,
                        source = ModelCatalogSource.MODELS_DEV,
                        status = if (model.optBoolean("deprecated", false)) ModelStatus.DEPRECATED else ModelStatus.AVAILABLE,
                        capabilities = capabilities,
                        inputPricePerMillionTokens = cost?.optNullableDouble("input"),
                        outputPricePerMillionTokens = cost?.optNullableDouble("output"),
                        contextWindow = limit?.optNullableInt("context"),
                        maxOutputTokens = limit?.optNullableInt("output"),
                        releaseDate = model.optString("release_date").takeIf { it.isNotBlank() },
                        knowledgeCutoff = model.optString("knowledge").takeIf { it.isNotBlank() },
                        lastSyncedAt = System.currentTimeMillis(),
                        metadata = buildMap {
                            put("providerName", providerName)
                            put("modelsDevProviderId", rawProviderId)
                            model.optString("family").takeIf { it.isNotBlank() }?.let { put("family", it) }
                            model.optString("last_updated").takeIf { it.isNotBlank() }?.let { put("lastUpdated", it) }
                        }
                    )
                )
            }
        }
        return entries
    }
}

private fun mapModelsDevProviderId(providerId: String): String = when (providerId) {
    "google" -> "google_gemini_api"
    "vercel" -> "vercel_ai_gateway"
    "github-copilot" -> "github_copilot"
    else -> providerId
}

private fun org.json.JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }.toSet()
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getDouble(key) }.getOrNull()
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getInt(key) }.getOrNull()
}
