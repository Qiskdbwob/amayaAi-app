package com.amaya.intelligence.data.remote.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ConfiguredModel(
    val id: String,
    val displayName: String = id
)

data class ProviderConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val providerId: String = "",
    val baseUrl: String = "",
    val visibleModels: List<ConfiguredModel> = emptyList()
)

data class ActiveModelSelection(
    val connectionId: String,
    val modelId: String
) {
    val key: String get() = "model|$connectionId|$modelId"
}

private val Context.dataStore by preferencesDataStore(name = "ai_settings")

@Singleton
class AiSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_CONNECTIONS = stringPreferencesKey("provider_connections_v2")
        private val KEY_ACTIVE_SELECTION = stringPreferencesKey("active_model_selection_v2")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_MCP_CONFIG_JSON = stringPreferencesKey("mcp_config_json")
        private val KEY_LAST_WORKSPACE = stringPreferencesKey("last_workspace_path")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        private val LEGACY_AGENT_CONFIGS = stringPreferencesKey("agent_configs")
        private val LEGACY_ACTIVE_AGENT_ID = stringPreferencesKey("active_agent_id")
        private val LEGACY_ACTIVE_MODEL = stringPreferencesKey("active_model")

        private const val ENC_CONNECTION_KEY_PREFIX = "agent_key_"
        private const val SECURE_PREFS_NAME = "amaya_secure_prefs"
        private const val STARTUP_PREFS_NAME = "amaya_startup_prefs"
        private const val STARTUP_THEME_KEY = "theme"

        const val MCP_FIXED_PATH = "/storage/emulated/0/Amaya/mcp.json"
    }

    private val encryptedPrefs: SharedPreferences by lazy { createEncryptedPrefs() }
    private val startupPrefs by lazy {
        context.getSharedPreferences(STARTUP_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { failure ->
            Log.e("AiSettingsManager", "Secure credential storage unavailable", failure)
            throw IllegalStateException(
                "Secure credential storage is unavailable. Reinstall the app only if credential recovery is impossible.",
                failure
            )
        }
    }

    val settingsFlow: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        val connections = parseConnections(
            prefs[KEY_CONNECTIONS] ?: prefs[LEGACY_AGENT_CONFIGS].orEmpty()
        )
        val activeSelection = parseActiveSelection(prefs[KEY_ACTIVE_SELECTION])
            ?: legacyActiveSelection(
                connections = connections,
                connectionId = prefs[LEGACY_ACTIVE_AGENT_ID].orEmpty(),
                modelId = prefs[LEGACY_ACTIVE_MODEL].orEmpty()
            )
        AiSettings(
            theme = prefs[KEY_THEME] ?: "system",
            connections = connections,
            activeSelection = activeSelection?.takeIf { selection ->
                connections.any { connection ->
                    connection.id == selection.connectionId &&
                        connection.visibleModels.any { it.id == selection.modelId }
                }
            },
            mcpConfigJson = prefs[KEY_MCP_CONFIG_JSON].orEmpty(),
            lastWorkspacePath = prefs[KEY_LAST_WORKSPACE]?.ifBlank { null },
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false
        )
    }

    @Volatile
    private var cachedSettings: AiSettings? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            settingsFlow.collect {
                cachedSettings = it
                startupPrefs.edit().putString(STARTUP_THEME_KEY, it.theme).apply()
            }
        }
    }

    fun getSettings(): AiSettings =
        cachedSettings ?: runBlocking { settingsFlow.first() }.also { cachedSettings = it }

    fun getStartupTheme(): String =
        startupPrefs.getString(STARTUP_THEME_KEY, "system") ?: "system"



    fun getConnectionApiKey(connectionId: String): String =
        encryptedPrefs.getString("$ENC_CONNECTION_KEY_PREFIX$connectionId", "").orEmpty()

    fun hasConnectionApiKey(connectionId: String): Boolean =
        getConnectionApiKey(connectionId).isNotBlank()

    fun getEncryptedPrefsForCodex(): SharedPreferences = encryptedPrefs

    suspend fun saveConnection(connection: ProviderConnection, apiKey: String? = null) {
        require(connection.id.isNotBlank()) { "Connection ID is required" }
        require(AmayaProviderRegistry.find(connection.providerId) != null) { "Unsupported provider: ${connection.providerId}" }
        require(connection.name.isNotBlank()) { "Connection name is required" }
        val normalized = connection.copy(
            name = connection.name.trim(),
            baseUrl = connection.baseUrl.trim().trimEnd('/'),
            visibleModels = connection.visibleModels
                .map { it.copy(id = it.id.trim(), displayName = it.displayName.trim()) }
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .map { it.copy(displayName = it.displayName.ifBlank { it.id }) }
        )
        val credentialKey = "$ENC_CONNECTION_KEY_PREFIX${connection.id}"
        val previousCredential = if (apiKey != null) encryptedPrefs.getString(credentialKey, null) else null
        if (apiKey != null && !encryptedPrefs.edit().putString(credentialKey, apiKey.trim()).commit()) {
            error("Could not save the provider credential securely")
        }
        runCatching {
            context.dataStore.edit { prefs ->
                val list = parseConnections(
                    prefs[KEY_CONNECTIONS] ?: prefs[LEGACY_AGENT_CONFIGS].orEmpty()
                ).toMutableList()
                val index = list.indexOfFirst { it.id == normalized.id }
                if (index >= 0) list[index] = normalized else list.add(normalized)
                prefs[KEY_CONNECTIONS] = serializeConnections(list)
                val active = parseActiveSelection(prefs[KEY_ACTIVE_SELECTION])
                    ?: legacyActiveSelection(
                        connections = list,
                        connectionId = prefs[LEGACY_ACTIVE_AGENT_ID].orEmpty(),
                        modelId = prefs[LEGACY_ACTIVE_MODEL].orEmpty()
                    )
                if (active != null) prefs[KEY_ACTIVE_SELECTION] = serializeActiveSelection(active)
                prefs.remove(LEGACY_AGENT_CONFIGS)
                prefs.remove(LEGACY_ACTIVE_AGENT_ID)
                prefs.remove(LEGACY_ACTIVE_MODEL)
            }
        }.onFailure {
            if (apiKey != null) {
                val rollback = encryptedPrefs.edit()
                if (previousCredential == null) rollback.remove(credentialKey)
                else rollback.putString(credentialKey, previousCredential)
                rollback.commit()
            }
        }.getOrThrow()
    }

    suspend fun replaceConnectionApiKey(connectionId: String, apiKey: String) {
        val editor = encryptedPrefs.edit()
        if (apiKey.isBlank()) editor.remove("$ENC_CONNECTION_KEY_PREFIX$connectionId")
        else editor.putString("$ENC_CONNECTION_KEY_PREFIX$connectionId", apiKey.trim())
        if (!editor.commit()) error("Could not replace the provider credential securely")
    }

    suspend fun deleteConnection(connectionId: String) {
        context.dataStore.edit { prefs ->
            val remaining = parseConnections(
                prefs[KEY_CONNECTIONS] ?: prefs[LEGACY_AGENT_CONFIGS].orEmpty()
            ).filterNot { it.id == connectionId }
            prefs[KEY_CONNECTIONS] = serializeConnections(remaining)
            val active = parseActiveSelection(prefs[KEY_ACTIVE_SELECTION])
                ?: legacyActiveSelection(
                    connections = remaining,
                    connectionId = prefs[LEGACY_ACTIVE_AGENT_ID].orEmpty(),
                    modelId = prefs[LEGACY_ACTIVE_MODEL].orEmpty()
                )
            if (active?.connectionId == connectionId) {
                prefs.remove(KEY_ACTIVE_SELECTION)
            } else if (active != null) {
                prefs[KEY_ACTIVE_SELECTION] = serializeActiveSelection(active)
            }
            prefs.remove(LEGACY_AGENT_CONFIGS)
            prefs.remove(LEGACY_ACTIVE_AGENT_ID)
            prefs.remove(LEGACY_ACTIVE_MODEL)
        }
        if (!encryptedPrefs.edit().remove("$ENC_CONNECTION_KEY_PREFIX$connectionId").commit()) {
            Log.w("AiSettingsManager", "Connection deleted, but its credential could not be removed")
        }
    }

    suspend fun setActiveModel(selection: ActiveModelSelection?) {
        context.dataStore.edit { prefs ->
            if (selection == null) {
                prefs.remove(KEY_ACTIVE_SELECTION)
            } else {
                val connections = parseConnections(
                    prefs[KEY_CONNECTIONS] ?: prefs[LEGACY_AGENT_CONFIGS].orEmpty()
                )
                require(connections.any { connection ->
                    connection.id == selection.connectionId &&
                        connection.visibleModels.any { it.id == selection.modelId }
                }) { "Selected model is not visible for this provider connection" }
                prefs[KEY_ACTIVE_SELECTION] = serializeActiveSelection(selection)
            }
            prefs.remove(LEGACY_ACTIVE_AGENT_ID)
            prefs.remove(LEGACY_ACTIVE_MODEL)
        }
    }

    suspend fun setVisibleModels(connectionId: String, models: List<ConfiguredModel>) {
        val normalizedModels = models
            .map { model -> model.copy(id = model.id.trim(), displayName = model.displayName.trim()) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { model -> model.copy(displayName = model.displayName.ifBlank { model.id }) }
        context.dataStore.edit { prefs ->
            val connections = parseConnections(
                prefs[KEY_CONNECTIONS] ?: prefs[LEGACY_AGENT_CONFIGS].orEmpty()
            ).toMutableList()
            val index = connections.indexOfFirst { it.id == connectionId }
            require(index >= 0) { "Provider connection not found" }
            val active = parseActiveSelection(prefs[KEY_ACTIVE_SELECTION])
                ?: legacyActiveSelection(
                    connections = connections,
                    connectionId = prefs[LEGACY_ACTIVE_AGENT_ID].orEmpty(),
                    modelId = prefs[LEGACY_ACTIVE_MODEL].orEmpty()
                )
            connections[index] = connections[index].copy(visibleModels = normalizedModels)
            prefs[KEY_CONNECTIONS] = serializeConnections(connections)
            prefs.remove(LEGACY_AGENT_CONFIGS)
            if (active?.connectionId == connectionId && normalizedModels.none { it.id == active.modelId }) {
                prefs.remove(KEY_ACTIVE_SELECTION)
            } else if (active != null) {
                prefs[KEY_ACTIVE_SELECTION] = serializeActiveSelection(active)
            }
            prefs.remove(LEGACY_ACTIVE_AGENT_ID)
            prefs.remove(LEGACY_ACTIVE_MODEL)
        }
    }

    suspend fun setTheme(theme: String) {
        startupPrefs.edit().putString(STARTUP_THEME_KEY, theme).apply()
        context.dataStore.edit { it[KEY_THEME] = theme }
    }

    suspend fun setLastWorkspacePath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(KEY_LAST_WORKSPACE)
            else prefs[KEY_LAST_WORKSPACE] = path
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setMcpConfigJson(json: String) {
        context.dataStore.edit { it[KEY_MCP_CONFIG_JSON] = json }
        writeMcpConfigToFixedPath(json)
    }

    suspend fun loadMcpConfigFromFixedPath(): String? = withContext(Dispatchers.IO) {
        File(MCP_FIXED_PATH).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    }

    suspend fun writeMcpConfigToFixedPath(json: String) = withContext(Dispatchers.IO) {
        File(MCP_FIXED_PATH).apply {
            parentFile?.mkdirs()
            writeText(json)
        }
    }

    private fun parseConnections(json: String): List<ProviderConnection> = runCatching {
        val array = JSONArray(json.ifBlank { "[]" })
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val providerId = item.optString("providerId").ifBlank {
                inferLegacyProviderId(
                    providerType = item.optString("providerType"),
                    baseUrl = item.optString("baseUrl"),
                    modelId = item.optString("modelId")
                )
            }
            if (providerId.isBlank() || AmayaProviderRegistry.find(providerId) == null) {
                return@mapNotNull null
            }
            val legacyModelId = item.optString("modelId")
            val modelArray = item.optJSONArray("visibleModels")
            val configuredModels = if (modelArray != null) {
                (0 until modelArray.length()).mapNotNull { modelIndex ->
                    when (val value = modelArray.opt(modelIndex)) {
                        is JSONObject -> value.optString("id").takeIf { it.isNotBlank() }?.let {
                            ConfiguredModel(it, value.optString("displayName", it))
                        }
                        is String -> value.takeIf { it.isNotBlank() }?.let(::ConfiguredModel)
                        else -> null
                    }
                }
            } else {
                buildList {
                    legacyModelId.takeIf { it.isNotBlank() }?.let { add(ConfiguredModel(it)) }
                    item.optJSONArray("enabledModelIds")?.let { legacy ->
                        repeat(legacy.length()) { modelIndex ->
                            legacy.optString(modelIndex).takeIf { it.isNotBlank() }?.let { add(ConfiguredModel(it)) }
                        }
                    }
                }
            }
            ProviderConnection(
                id = id,
                name = item.optString("name").ifBlank { AmayaProviderRegistry.displayName(providerId) },
                providerId = providerId,
                baseUrl = item.optString("baseUrl"),
                visibleModels = configuredModels.distinctBy { it.id }
            )
        }
    }.getOrElse {
        Log.e("AiSettingsManager", "Could not parse provider connections", it)
        emptyList()
    }

    private fun serializeConnections(connections: List<ProviderConnection>): String =
        JSONArray().also { array ->
            connections.forEach { connection ->
                array.put(JSONObject().apply {
                    put("id", connection.id)
                    put("name", connection.name)
                    put("providerId", connection.providerId)
                    put("baseUrl", connection.baseUrl)
                    put("visibleModels", JSONArray().also { models ->
                        connection.visibleModels.forEach { model ->
                            models.put(JSONObject().put("id", model.id).put("displayName", model.displayName))
                        }
                    })
                })
            }
        }.toString()

    private fun serializeActiveSelection(selection: ActiveModelSelection): String = JSONObject()
        .put("connectionId", selection.connectionId)
        .put("modelId", selection.modelId)
        .toString()

    private fun parseActiveSelection(json: String?): ActiveModelSelection? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        JSONObject(json).let { item ->
            val connectionId = item.optString("connectionId")
            val modelId = item.optString("modelId")
            if (connectionId.isBlank() || modelId.isBlank()) null
            else ActiveModelSelection(connectionId, modelId)
        }
    }.getOrNull()

    private fun legacyActiveSelection(
        connections: List<ProviderConnection>,
        connectionId: String,
        modelId: String
    ): ActiveModelSelection? {
        if (connectionId.isBlank() || modelId.isBlank()) return null
        return connections.firstOrNull { it.id == connectionId }
            ?.takeIf { connection -> connection.visibleModels.any { it.id == modelId } }
            ?.let { ActiveModelSelection(it.id, modelId) }
    }

    private fun inferLegacyProviderId(providerType: String, baseUrl: String, modelId: String): String {
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
            baseUrl.isNotBlank() -> "custom_openai_compatible"
            else -> "openai"
        }
    }
}

data class AiSettings(
    val theme: String = "system",
    val connections: List<ProviderConnection> = emptyList(),
    val activeSelection: ActiveModelSelection? = null,
    val mcpConfigJson: String = "",
    val lastWorkspacePath: String? = null,
    val onboardingCompleted: Boolean = false
)
