package com.amaya.intelligence.data.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderModelService @Inject constructor(
    private val httpClient: OkHttpClient
) {
    suspend fun testAndListModels(
        providerId: String,
        baseUrlOverride: String,
        apiKey: String
    ): Result<List<ConfiguredModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = AmayaProviderRegistry.require(providerId)
            require(!provider.isSubscription) { "Subscription models must come from the authenticated runtime" }
            if (provider.credentialRequired) require(apiKey.isNotBlank()) { "API key is required" }
            val baseUrl = resolveBaseUrl(provider, baseUrlOverride)
            val request = when {
                provider.id == "github_models" -> githubModelsRequest(apiKey)
                provider.adapter == ProviderAdapter.OPENAI_COMPATIBLE -> openAiModelsRequest(baseUrl, apiKey)
                provider.adapter == ProviderAdapter.ANTHROPIC -> anthropicModelsRequest(baseUrl, apiKey)
                provider.adapter == ProviderAdapter.GEMINI -> geminiModelsRequest(baseUrl, apiKey)
                else -> error("Subscription models must come from the authenticated runtime")
            }
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(providerError(provider, response.code, body, response.message))
                }
                parseModels(provider.adapter, body)
            }
        }
    }

    fun validateConnectionUrl(providerId: String, baseUrlOverride: String): Result<String> = runCatching {
        val provider = AmayaProviderRegistry.require(providerId)
        resolveBaseUrl(provider, baseUrlOverride).toString().trimEnd('/')
    }

    private fun resolveBaseUrl(provider: ProviderConfig, override: String): HttpUrl {
        val raw = if (provider.isCustom) override.trim() else provider.defaultBaseUrl.orEmpty()
        require(raw.isNotBlank()) { "Base URL is required" }
        val url = raw.toHttpUrlOrNull() ?: error("Enter a valid URL including https://")
        require(url.username.isEmpty() && url.password.isEmpty()) { "Base URL must not contain credentials" }
        require(url.query == null && url.fragment == null) { "Base URL must not contain a query or fragment" }
        require(
            !url.encodedPath.trimEnd('/').endsWith("/models") &&
                !url.encodedPath.trimEnd('/').endsWith("/chat/completions")
        ) { "Base URL must point to the API root, such as https://example.com/v1" }
        if (url.scheme == "http" && !isPrivateHost(url.host)) {
            error("Public provider URLs must use HTTPS")
        }
        return url
    }

    private fun openAiModelsRequest(baseUrl: HttpUrl, apiKey: String): Request {
        val builder = Request.Builder()
            .url(modelsUrl(baseUrl))
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    private fun anthropicModelsRequest(baseUrl: HttpUrl, apiKey: String): Request = Request.Builder()
        .url(modelsUrl(baseUrl))
        .header("Accept", "application/json")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .build()

    private fun geminiModelsRequest(baseUrl: HttpUrl, apiKey: String): Request = Request.Builder()
        .url(modelsUrl(baseUrl))
        .header("Accept", "application/json")
        .header("x-goog-api-key", apiKey)
        .build()

    private fun githubModelsRequest(apiKey: String): Request = Request.Builder()
        .url("https://models.github.ai/catalog/models")
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $apiKey")
        .build()

    private fun modelsUrl(baseUrl: HttpUrl): HttpUrl =
        baseUrl.newBuilder().addPathSegment("models").build()

    private fun providerError(
        provider: ProviderConfig,
        statusCode: Int,
        body: String,
        fallback: String
    ): String {
        val detail = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback.ifBlank { "Request failed" }
        return "${provider.displayName} returned HTTP $statusCode: ${detail.take(240)}"
    }

    private fun parseModels(adapter: ProviderAdapter, json: String): List<ConfiguredModel> {
        val content = json.ifBlank { "{}" }
        val array = if (content.trimStart().startsWith("[")) {
            JSONArray(content)
        } else {
            val root = JSONObject(content)
            when (adapter) {
                ProviderAdapter.GEMINI -> root.optJSONArray("models")
                else -> root.optJSONArray("data") ?: root.optJSONArray("models")
            } ?: JSONArray()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val rawId = item.optString("id").ifBlank { item.optString("name") }
            val id = rawId.removePrefix("models/").trim()
            if (id.isBlank()) return@mapNotNull null
            if (adapter == ProviderAdapter.GEMINI) {
                val methods = item.optJSONArray("supportedGenerationMethods")
                if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" }) {
                    return@mapNotNull null
                }
            }
            val displayName = item.optString("display_name").ifBlank {
                item.optString("displayName").ifBlank { item.optString("name").ifBlank { id } }
            }
            ConfiguredModel(id = id, displayName = displayName)
        }.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".local")) return true
        if (':' in normalized && (
                normalized.startsWith("fc") || normalized.startsWith("fd") ||
                    normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
                    normalized.startsWith("fea") || normalized.startsWith("feb")
            )
        ) return true
        val parts = normalized.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            parts[0] == 169 && parts[1] == 254 ||
            parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31
    }
}
