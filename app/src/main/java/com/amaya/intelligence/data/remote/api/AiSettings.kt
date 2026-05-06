package com.amaya.intelligence.data.remote.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

// FIX 1.3: Removed unused import androidx.security.crypto.MasterKeys (replaced by MasterKey)

/**
 * A single agent / API profile the user has configured.
 * name    – display label (e.g. "OpenRouter Free", "My GPT-4o")
 * baseUrl – API base URL  (e.g. "https://openrouter.ai/api/v1")
 * modelId – model id       (e.g. "openai/gpt-4o-mini")
 * API key is stored encrypted separately, keyed by [id].
 */
data class AgentConfig(
    val id:           String  = java.util.UUID.randomUUID().toString(),
    val name:         String  = "",
    /** Legacy runtime provider type used by the current chat adapters. Filled when the user picks a provider. */
    val providerType: String  = "",
    /** New provider registry id. Filled when the user picks a provider. */
    val providerId:   String  = "",
    val baseUrl:      String  = "",
    val modelId:      String  = "",
    val enabled:      Boolean = true,
    val maxTokens:    Int     = 8192,
    val maxIterations:Int     = 10,
    val toolCalling:  Boolean = true,
    val vision:       Boolean = true,
    val reasoning:    Boolean = false,
    val structuredOutput: Boolean = false,
    val embeddings:   Boolean = false,
    val jsonMode:     Boolean = true,
    val streaming:    Boolean = true,
    /** Catalog model IDs the user enabled for this provider in Manage Models. Empty keeps only [modelId]. */
    val enabledModelIds: List<String> = emptyList()
)

private val Context.dataStore by preferencesDataStore(name = "ai_settings")

@Singleton
class AiSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // KEY_ACTIVE_PROVIDER: legacy key kept in DataStore for backwards compat (not read by new code)
        private val KEY_ACTIVE_MODEL      = stringPreferencesKey("active_model")
        private val KEY_THEME             = stringPreferencesKey("theme")
        private val KEY_AGENT_CONFIGS     = stringPreferencesKey("agent_configs")
        private val KEY_ACTIVE_AGENT_ID   = stringPreferencesKey("active_agent_id")
        private val KEY_MCP_CONFIG_JSON   = stringPreferencesKey("mcp_config_json")
        private val KEY_LAST_WORKSPACE    = stringPreferencesKey("last_workspace_path")
        private val KEY_ONBOARDING_COMPLETED = androidx.datastore.preferences.core.booleanPreferencesKey("onboarding_completed")

        // Per-agent encrypted API key storage: key = "agent_key_" + agentId
        private const val ENC_AGENT_KEY_PREFIX  = "agent_key_"
        private const val SECURE_PREFS_NAME     = "amaya_secure_prefs"

        const val MCP_FIXED_PATH = "/storage/emulated/0/Amaya/mcp.json"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return runCatching {
            createEncryptedPrefsInternal()
        }.getOrElse { firstFailure ->
            clearCorruptedEncryptedPrefs(firstFailure)
            runCatching {
                createEncryptedPrefsInternal()
            }.getOrElse { secondFailure ->
                Log.e("AiSettingsManager", "Secure credential storage unavailable after recovery attempt", secondFailure)
                throw IllegalStateException("Secure credential storage is unavailable", secondFailure)
            }
        }
    }

    private fun createEncryptedPrefsInternal(): SharedPreferences {
        // FIX 5.8: Use non-deprecated MasterKey.Builder API (MasterKeys was deprecated in security-crypto 1.1.0-alpha)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedEncryptedPrefs(cause: Throwable) {
        Log.w("AiSettingsManager", "Encrypted prefs corrupted or unreadable, clearing and recreating", cause)
        runCatching { context.deleteSharedPreferences(SECURE_PREFS_NAME) }
        runCatching {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.listFiles()?.forEach { file ->
                    if (file.name.contains(SECURE_PREFS_NAME) || file.name.contains("androidx_security_crypto")) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
        runCatching {
            val fallbackFile = File(context.applicationInfo.dataDir, "shared_prefs/$SECURE_PREFS_NAME.xml")
            if (fallbackFile.exists()) fallbackFile.delete()
        }
    }

    // FIX 3.1: Cache the last emitted settings so getSettings() rarely needs runBlocking.
    // IMPORTANT: settingsFlow must be declared BEFORE init{} so it is non-null when the
    // coroutine starts. Kotlin initializes properties top-to-bottom, so declaration order matters.
    val settingsFlow: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        val configs = parseAgentConfigs(prefs[KEY_AGENT_CONFIGS] ?: "[]")
        AiSettings(
            activeModel       = prefs[KEY_ACTIVE_MODEL] ?: "",
            theme             = prefs[KEY_THEME] ?: "system",
            agentConfigs      = configs,
            activeAgentId     = prefs[KEY_ACTIVE_AGENT_ID] ?: "",
            mcpConfigJson     = prefs[KEY_MCP_CONFIG_JSON] ?: "",
            lastWorkspacePath = prefs[KEY_LAST_WORKSPACE]?.ifBlank { null },
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false
        )
    }

    // FIX 3.1: Cache declared AFTER settingsFlow so it is non-null when init{} coroutine starts.
    @Volatile private var cachedSettings: AiSettings? = null

    init {
        // Warm the cache on a background thread — settingsFlow is guaranteed non-null here
        CoroutineScope(Dispatchers.IO).launch {
            settingsFlow.collect { cachedSettings = it }
        }
    }

    fun getSettings(): AiSettings =
        cachedSettings ?: runBlocking { settingsFlow.first() }.also { cachedSettings = it }

    /** Retrieve encrypted API key for a specific agent config ID */
    fun getAgentApiKey(agentId: String): String =
        encryptedPrefs.getString("$ENC_AGENT_KEY_PREFIX$agentId", "") ?: ""

    /** Expose encrypted prefs for Codex token storage (used by [CodexAuthManager]). */
    fun getEncryptedPrefsForCodex(): android.content.SharedPreferences = encryptedPrefs

    // ── Write ────────────────────────────────────────────────────────

    /** Add or update an agent config and store its API key encrypted */
    suspend fun saveAgentConfig(config: AgentConfig, apiKey: String) {
        encryptedPrefs.edit().putString("$ENC_AGENT_KEY_PREFIX${config.id}", apiKey).apply()
        context.dataStore.edit { prefs ->
            val list = parseAgentConfigs(prefs[KEY_AGENT_CONFIGS] ?: "[]").toMutableList()
            val normalized = config.normalizeEnabledModelIds()
            val idx = list.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) list[idx] = normalized else list.add(normalized)
            prefs[KEY_AGENT_CONFIGS] = serializeAgentConfigs(list)
        }
    }

    /** Delete an agent config */
    suspend fun deleteAgentConfig(agentId: String) {
        encryptedPrefs.edit().remove("$ENC_AGENT_KEY_PREFIX$agentId").apply()
        context.dataStore.edit { prefs ->
            val list = parseAgentConfigs(prefs[KEY_AGENT_CONFIGS] ?: "[]").toMutableList()
            list.removeAll { it.id == agentId }
            prefs[KEY_AGENT_CONFIGS] = serializeAgentConfigs(list)
            if ((prefs[KEY_ACTIVE_AGENT_ID] ?: "") == agentId) {
                prefs[KEY_ACTIVE_AGENT_ID] = list.firstOrNull()?.id ?: ""
                prefs[KEY_ACTIVE_MODEL]    = list.firstOrNull()?.modelId ?: ""
            }
        }
    }

    /** Select which agent config is active; also updates the active model */
    suspend fun setActiveAgent(agentId: String, modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_AGENT_ID] = agentId
            prefs[KEY_ACTIVE_MODEL]    = modelId
        }
    }

    // FIX 1.1: Removed setAnthropicApiKey() and setGeminiApiKey() — dead wrappers around setApiKey().
    // FIX 1.2: Removed setActiveProvider() and setBaseUrl() — dead code from pre-agent-config era.
    //          Provider is now resolved from AgentConfig.providerType, not from activeProvider DataStore key.

    suspend fun setActiveModel(model: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ACTIVE_MODEL] = model }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = theme }
    }

    suspend fun setLastWorkspacePath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(KEY_LAST_WORKSPACE)
            else prefs[KEY_LAST_WORKSPACE] = path
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setMcpConfigJson(json: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MCP_CONFIG_JSON] = json }
        writeMcpConfigToFixedPath(json)
    }

    suspend fun loadMcpConfigFromFixedPath(): String? {
        return withContext(Dispatchers.IO) {
            val file = File(MCP_FIXED_PATH)
            if (!file.exists()) return@withContext null
            return@withContext runCatching { file.readText() }.getOrNull()
        }
    }

    suspend fun writeMcpConfigToFixedPath(json: String) {
        withContext(Dispatchers.IO) {
            val file = File(MCP_FIXED_PATH)
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────

    private fun parseAgentConfigs(json: String): List<AgentConfig> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { obj ->
                AgentConfig(
                    id           = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name         = obj.optString("name", ""),
                    providerType = obj.optString("providerType", ""),
                    providerId   = obj.optString("providerId", "").ifBlank {
                        inferProviderId(obj.optString("providerType", ""), obj.optString("baseUrl", ""), obj.optString("modelId", ""))
                    },
                    baseUrl      = obj.optString("baseUrl", ""),
                    modelId      = obj.optString("modelId", ""),
                    enabled      = obj.optBoolean("enabled", true),
                    maxTokens    = obj.optInt("maxTokens", 8192),
                    maxIterations= obj.optInt("maxIterations", 10),
                    toolCalling  = obj.optBoolean("toolCalling", true),
                    vision       = obj.optBoolean("vision", true),
                    reasoning    = obj.optBoolean("reasoning", false),
                    structuredOutput = obj.optBoolean("structuredOutput", false),
                    embeddings   = obj.optBoolean("embeddings", false),
                    jsonMode     = obj.optBoolean("jsonMode", true),
                    streaming    = obj.optBoolean("streaming", true),
                    enabledModelIds = obj.optJSONArray("enabledModelIds")?.let { arr ->
                        (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
                    }.orEmpty()
                ).normalizeEnabledModelIds()
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun serializeAgentConfigs(configs: List<AgentConfig>): String =
        JSONArray().also { arr ->
            configs.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id",           c.id)
                    put("name",         c.name)
                    put("providerType", c.providerType)
                    put("providerId",   c.providerId)
                    put("baseUrl",      c.baseUrl)
                    put("modelId",      c.modelId)
                    put("enabled",      c.enabled)
                    put("maxTokens",    c.maxTokens)
                    put("maxIterations",c.maxIterations)
                    put("toolCalling",  c.toolCalling)
                    put("vision",       c.vision)
                    put("reasoning",    c.reasoning)
                    put("structuredOutput", c.structuredOutput)
                    put("embeddings",   c.embeddings)
                    put("jsonMode",     c.jsonMode)
                    put("streaming",    c.streaming)
                    put("enabledModelIds", JSONArray(c.enabledModelIds.distinct()))
                })
            }
        }.toString()

    private fun inferProviderId(providerType: String, baseUrl: String, modelId: String): String {
        val value = "${providerType.lowercase()} ${baseUrl.lowercase()} ${modelId.lowercase()}"
        return when {
            value.contains("anthropic") || value.contains("claude") -> "anthropic"
            value.contains("gemini") || value.contains("google") -> "google_gemini_api"
            value.contains("openrouter") -> "openrouter"
            value.contains("groq") -> "groq"
            value.contains("deepseek") -> "deepseek"
            value.contains("x.ai") || value.contains("grok") -> "xai"
            value.contains("github") -> "github_models"
            value.contains("vercel") -> "vercel_ai_gateway"
            value.contains("ollama") -> "ollama"
            value.contains("lmstudio") || value.contains("lm-studio") || value.contains("localhost:1234") -> "lm_studio"
            baseUrl.isNotBlank() -> "custom_openai_compatible"
            else -> ""
        }
    }
}

private fun AgentConfig.normalizeEnabledModelIds(): AgentConfig {
    val normalizedEnabled = enabledModelIds.filter { it.isNotBlank() }.distinct()
    return if (AmayaProviderRegistry.find(providerId)?.isSubscription == true) {
        copy(modelId = "", enabledModelIds = normalizedEnabled)
    } else {
        copy(enabledModelIds = normalizedEnabled)
    }
}

data class AiSettings(
    // activeModel: fallback model ID if no agent config found (rarely used)
    val activeModel:       String            = "",
    val theme:             String            = "system",
    val agentConfigs:      List<AgentConfig> = emptyList(),
    val activeAgentId:     String            = "",
    val mcpConfigJson:     String            = "",
    /** Last workspace path the user opened — persisted across app restarts. */
    val lastWorkspacePath: String?           = null,
    val onboardingCompleted: Boolean          = false
)

enum class ProviderType { ANTHROPIC, OPENAI, GEMINI, CUSTOM_OPENAI_COMPATIBLE }
