package com.amaya.intelligence.data.remote.api

/**
 * Provider/model registry used by the Android app while the persistent provider
 * catalog is migrated in stages. The shape follows models.md, but remains local
 * and static so existing chat/runtime logic keeps working.
 */
enum class ProviderCategory { SUBSCRIPTION_LOGIN, API_KEY, CLOUD_CREDENTIALS, LOCAL, CUSTOM }
enum class ProviderEngine { TOOL_BRIDGE, GATEWAY_ENGINE, LOCAL_ENGINE }
enum class AuthMode { NONE, API_KEY, BEARER_TOKEN, X_API_KEY, OAUTH, BROWSER_LOGIN, DEVICE_FLOW, CUSTOM_HEADERS }
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT, TOOL_BRIDGE, LOCAL_OPENAI_COMPATIBLE, CUSTOM }
enum class CredentialStorage { LOCAL_SECURE_STORAGE, BACKEND_VAULT, EXTERNAL_CLI, NONE }
enum class ProviderFieldType { TEXT, PASSWORD, URL, SELECT, NUMBER, BOOLEAN, JSON, PATH }
enum class ModelCatalogSource { MODELS_DEV, PROVIDER_LIVE_API, LOCAL_SCAN, MANUAL_ONLY, SUBSCRIPTION_TOOL }

enum class ModelStatus {
    DISCOVERED,
    PENDING_REVIEW,
    AVAILABLE,
    ENABLED,
    DISABLED,
    HIDDEN,
    DEPRECATED,
    UNAVAILABLE,
    NEEDS_CREDENTIAL,
    NEEDS_ACCESS,
    REGION_UNSUPPORTED,
    MANUAL_MAPPING_REQUIRED,
    SUBSCRIPTION_TOOL_ONLY
}

enum class ModelCapability(val label: String) {
    TEXT_INPUT("Text input"),
    TEXT_OUTPUT("Text output"),
    IMAGE_INPUT("Vision"),
    IMAGE_OUTPUT("Image output"),
    TOOL_CALLING("Tools"),
    STRUCTURED_OUTPUT("Structured output"),
    REASONING("Reasoning"),
    EMBEDDINGS("Embeddings"),
    STREAMING("Streaming"),
    JSON_MODE("JSON mode")
}

data class ProviderField(
    val key: String,
    val label: String,
    val type: ProviderFieldType,
    val required: Boolean,
    val secret: Boolean = false,
    val placeholder: String? = null,
    val description: String? = null
)

data class ProviderConfig(
    val id: String,
    val displayName: String,
    val category: ProviderCategory,
    val engine: ProviderEngine,
    val authModes: List<AuthMode>,
    val apiFormat: ApiFormat,
    val defaultBaseUrl: String? = null,
    val requiredFields: List<ProviderField> = emptyList(),
    val optionalFields: List<ProviderField> = emptyList(),
    val credentialStorage: CredentialStorage,
    val supportsModelSync: Boolean,
    val modelCatalogSources: List<ModelCatalogSource>,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val supportsEmbeddings: Boolean,
    val supportsImageGeneration: Boolean = false,
    val supportsLocalRuntime: Boolean = false,
    val notes: String? = null
) {
    val isSubscription: Boolean get() = category == ProviderCategory.SUBSCRIPTION_LOGIN
}

data class ModelCatalogEntry(
    val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val source: ModelCatalogSource = ModelCatalogSource.MODELS_DEV,
    val status: ModelStatus = ModelStatus.AVAILABLE,
    val capabilities: Set<ModelCapability> = emptySet(),
    val inputPricePerMillionTokens: Double? = null,
    val outputPricePerMillionTokens: Double? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val releaseDate: String? = null,
    val knowledgeCutoff: String? = null,
    val lastSyncedAt: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class EffectiveModelInfo(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val displayName: String,
    val status: ModelStatus,
    val capabilities: Set<ModelCapability>,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val inputPricePerMillionTokens: Double?,
    val outputPricePerMillionTokens: Double?,
    val sourceLabel: String
)

object AmayaProviderRegistry {
    private val apiKeyField = ProviderField("apiKey", "API Key", ProviderFieldType.PASSWORD, required = true, secret = true)
    private val baseUrlField = ProviderField("baseUrl", "Base URL", ProviderFieldType.URL, required = false)

    val providers: List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "google_subscription",
            displayName = "Google",
            category = ProviderCategory.SUBSCRIPTION_LOGIN,
            engine = ProviderEngine.TOOL_BRIDGE,
            authModes = listOf(AuthMode.OAUTH, AuthMode.BROWSER_LOGIN),
            apiFormat = ApiFormat.TOOL_BRIDGE,
            credentialStorage = CredentialStorage.BACKEND_VAULT,
            supportsModelSync = false,
            modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
            supportsStreaming = true,
            supportsTools = true,
            supportsVision = true,
            supportsEmbeddings = false,
            supportsLocalRuntime = false,
            notes = "Android-native Google login slot. Gemini API usage still belongs to Gemini API Key or Vertex provider."
        ),
        ProviderConfig(
            id = "github_copilot",
            displayName = "GitHub Copilot",
            category = ProviderCategory.SUBSCRIPTION_LOGIN,
            engine = ProviderEngine.TOOL_BRIDGE,
            authModes = listOf(AuthMode.OAUTH, AuthMode.DEVICE_FLOW),
            apiFormat = ApiFormat.TOOL_BRIDGE,
            credentialStorage = CredentialStorage.BACKEND_VAULT,
            supportsModelSync = false,
            modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
            supportsStreaming = true,
            supportsTools = true,
            supportsVision = false,
            supportsEmbeddings = false,
            notes = "Experimental. Requires Amaya backend bridge; do not scrape Copilot session."
        ),
        ProviderConfig(
            id = "openai_codex_bridge",
            displayName = "OpenAI / ChatGPT / Codex",
            category = ProviderCategory.SUBSCRIPTION_LOGIN,
            engine = ProviderEngine.TOOL_BRIDGE,
            authModes = listOf(AuthMode.BROWSER_LOGIN, AuthMode.DEVICE_FLOW),
            apiFormat = ApiFormat.TOOL_BRIDGE,
            credentialStorage = CredentialStorage.LOCAL_SECURE_STORAGE,
            supportsModelSync = false,
            modelCatalogSources = listOf(ModelCatalogSource.SUBSCRIPTION_TOOL),
            supportsStreaming = true,
            supportsTools = true,
            supportsVision = false,
            supportsEmbeddings = false,
            supportsLocalRuntime = true,
            notes = "Supports local server PKCE and device code auth against auth.openai.com using the public Codex client_id."
        ),

        ProviderConfig("openai", "OpenAI API", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://api.openai.com/v1", listOf(apiKeyField), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, true, true),
        ProviderConfig("anthropic", "Anthropic API", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.X_API_KEY), ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", listOf(apiKeyField.copy(label = "Anthropic API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, false),
        ProviderConfig("google_gemini_api", "Google Gemini API", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.API_KEY), ApiFormat.GEMINI_GENERATE_CONTENT, "https://generativelanguage.googleapis.com", listOf(apiKeyField.copy(label = "Gemini API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, true),
        ProviderConfig("github_models", "GitHub Models", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://models.github.ai", listOf(apiKeyField.copy(label = "GitHub Token")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, false),
        ProviderConfig("vercel_ai_gateway", "Vercel AI Gateway", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://ai-gateway.vercel.sh/v1", listOf(apiKeyField.copy(label = "Vercel AI Gateway API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, true),
        ProviderConfig("openrouter", "OpenRouter", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", listOf(apiKeyField.copy(label = "OpenRouter API Key")), emptyList(), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, false),
        ProviderConfig("groq", "Groq", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", listOf(apiKeyField.copy(label = "Groq API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, false, false),
        ProviderConfig("deepseek", "DeepSeek", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://api.deepseek.com", listOf(apiKeyField.copy(label = "DeepSeek API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, false, false),
        ProviderConfig("xai", "xAI", ProviderCategory.API_KEY, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, "https://api.x.ai/v1", listOf(apiKeyField.copy(label = "xAI API Key")), listOf(baseUrlField), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.MODELS_DEV, ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, false),
        ProviderConfig("ollama", "Ollama", ProviderCategory.LOCAL, ProviderEngine.LOCAL_ENGINE, listOf(AuthMode.NONE), ApiFormat.LOCAL_OPENAI_COMPATIBLE, "http://localhost:11434/v1", emptyList(), listOf(baseUrlField.copy(required = true)), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.LOCAL_SCAN, ModelCatalogSource.MANUAL_ONLY), true, false, true, true, supportsLocalRuntime = true),
        ProviderConfig("lm_studio", "LM Studio", ProviderCategory.LOCAL, ProviderEngine.LOCAL_ENGINE, listOf(AuthMode.NONE, AuthMode.BEARER_TOKEN), ApiFormat.LOCAL_OPENAI_COMPATIBLE, "http://localhost:1234/v1", emptyList(), listOf(baseUrlField.copy(required = true), apiKeyField.copy(required = false)), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.LOCAL_SCAN, ModelCatalogSource.MANUAL_ONLY), true, false, false, true, supportsLocalRuntime = true),
        ProviderConfig("custom_openai_compatible", "Custom OpenAI Compatible", ProviderCategory.CUSTOM, ProviderEngine.GATEWAY_ENGINE, listOf(AuthMode.NONE, AuthMode.BEARER_TOKEN), ApiFormat.OPENAI_COMPATIBLE, null, listOf(ProviderField("baseUrl", "Base URL", ProviderFieldType.URL, true)), listOf(apiKeyField.copy(required = false, label = "Bearer token")), CredentialStorage.LOCAL_SECURE_STORAGE, true, listOf(ModelCatalogSource.PROVIDER_LIVE_API, ModelCatalogSource.MANUAL_ONLY), true, true, true, true, notes = "OpenAI-compatible Chat Completions only. Optional key is sent as Authorization: Bearer <token>.")
    )

    fun find(id: String?): ProviderConfig? = providers.firstOrNull { it.id == id }

    fun displayName(id: String?): String = find(id)?.displayName ?: id?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replaceFirstChar { it.uppercaseChar() } ?: "Custom"

    fun legacyProviderType(providerId: String): ProviderType = when (providerId) {
        "anthropic" -> ProviderType.ANTHROPIC
        "google_gemini_api" -> ProviderType.GEMINI
        else -> ProviderType.OPENAI
    }
}

object KnownModelCatalog {
    private val commonCaps = setOf(ModelCapability.TEXT_INPUT, ModelCapability.TEXT_OUTPUT, ModelCapability.STREAMING, ModelCapability.JSON_MODE)
    private val toolCaps = commonCaps + ModelCapability.TOOL_CALLING
    private val visionToolCaps = toolCaps + ModelCapability.IMAGE_INPUT

    val entries: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry("openai/gpt-4.1", "openai", "gpt-4.1", "GPT-4.1", capabilities = visionToolCaps + ModelCapability.STRUCTURED_OUTPUT, contextWindow = 1_047_576, maxOutputTokens = 32_768, inputPricePerMillionTokens = 2.0, outputPricePerMillionTokens = 8.0),
        ModelCatalogEntry("openai/gpt-4o", "openai", "gpt-4o", "GPT-4o", capabilities = visionToolCaps + ModelCapability.STRUCTURED_OUTPUT, contextWindow = 128_000, maxOutputTokens = 16_384),
        ModelCatalogEntry("openai/gpt-4o-mini", "openai", "gpt-4o-mini", "GPT-4o mini", capabilities = visionToolCaps + ModelCapability.STRUCTURED_OUTPUT, contextWindow = 128_000, maxOutputTokens = 16_384),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.5", "openai_codex_bridge", "gpt-5.5", "GPT-5.5", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.4", "openai_codex_bridge", "gpt-5.4", "GPT-5.4", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.4-mini", "openai_codex_bridge", "gpt-5.4-mini", "GPT-5.4 Mini", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.3-codex", "openai_codex_bridge", "gpt-5.3-codex", "GPT-5.3 Codex", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.3-codex-spark", "openai_codex_bridge", "gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 128_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.2-codex", "openai_codex_bridge", "gpt-5.2-codex", "GPT-5.2 Codex", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.1-codex-max", "openai_codex_bridge", "gpt-5.1-codex-max", "GPT-5.1 Codex Max", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("openai_codex_bridge/gpt-5.1-codex-mini", "openai_codex_bridge", "gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", source = ModelCatalogSource.SUBSCRIPTION_TOOL, capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 272_000, maxOutputTokens = 128_000, metadata = mapOf("providerName" to "OpenAI / ChatGPT / Codex", "codexCompatibility" to "verified")),
        ModelCatalogEntry("anthropic/claude-sonnet", "anthropic", "claude-sonnet", "Claude Sonnet", capabilities = visionToolCaps + ModelCapability.REASONING, contextWindow = 200_000, maxOutputTokens = 8_192),
        ModelCatalogEntry("google/gemini-2.5-pro", "google_gemini_api", "gemini-2.5-pro", "Gemini 2.5 Pro", capabilities = visionToolCaps + ModelCapability.REASONING + ModelCapability.STRUCTURED_OUTPUT, contextWindow = 1_000_000, maxOutputTokens = 65_536),
        ModelCatalogEntry("google/gemini-2.5-flash", "google_gemini_api", "gemini-2.5-flash", "Gemini 2.5 Flash", capabilities = visionToolCaps + ModelCapability.REASONING + ModelCapability.STRUCTURED_OUTPUT, contextWindow = 1_000_000, maxOutputTokens = 65_536),
        ModelCatalogEntry("deepseek/deepseek-chat", "deepseek", "deepseek-chat", "DeepSeek Chat", capabilities = toolCaps, contextWindow = 64_000),
        ModelCatalogEntry("deepseek/deepseek-reasoner", "deepseek", "deepseek-reasoner", "DeepSeek Reasoner", capabilities = toolCaps + ModelCapability.REASONING, contextWindow = 64_000),
        ModelCatalogEntry("xai/grok-4", "xai", "grok-4", "Grok 4", capabilities = visionToolCaps + ModelCapability.REASONING, contextWindow = 256_000),
        ModelCatalogEntry("groq/llama", "groq", "llama", "Llama on Groq", capabilities = toolCaps, contextWindow = 128_000)
    )

    fun infer(modelId: String, providerId: String? = null): EffectiveModelInfo {
        val normalized = modelId.trim()
        val provider = providerId?.takeIf { it.isNotBlank() }
        val direct = entries.firstOrNull { entry ->
            entry.modelId.equals(normalized, ignoreCase = true) || entry.id.equals(normalized, ignoreCase = true)
        }
        val inferredProvider = provider ?: direct?.providerId ?: inferProviderId(normalized)
        val entry = direct ?: entries.firstOrNull { it.providerId == inferredProvider && normalized.contains(it.modelId, ignoreCase = true) }
        val config = AmayaProviderRegistry.find(inferredProvider)
        val display = entry?.displayName ?: normalized.ifBlank { "Unknown model" }
        return EffectiveModelInfo(
            providerId = inferredProvider,
            providerName = config?.displayName ?: AmayaProviderRegistry.displayName(inferredProvider),
            modelId = normalized,
            displayName = display,
            status = entry?.status ?: ModelStatus.AVAILABLE,
            capabilities = entry?.capabilities ?: inferCapabilities(normalized),
            contextWindow = entry?.contextWindow ?: inferContextWindow(normalized),
            maxOutputTokens = entry?.maxOutputTokens,
            inputPricePerMillionTokens = entry?.inputPricePerMillionTokens,
            outputPricePerMillionTokens = entry?.outputPricePerMillionTokens,
            sourceLabel = if (entry != null) "models.dev catalog" else "local fallback"
        )
    }

    private fun inferProviderId(model: String): String = when {
        model.contains("claude", true) -> "anthropic"
        model.contains("gemini", true) -> "google_gemini_api"
        model.contains("deepseek", true) -> "deepseek"
        model.contains("grok", true) -> "xai"
        model.contains("llama", true) -> "groq"
        model.contains("qwen", true) -> "openrouter"
        model.contains("gpt", true) || model.startsWith("o") -> "openai"
        else -> "custom_openai_compatible"
    }

    private fun inferCapabilities(model: String): Set<ModelCapability> = buildSet {
        addAll(commonCaps)
        add(ModelCapability.TOOL_CALLING)
        if (model.contains("gpt", true) || model.contains("gemini", true) || model.contains("claude", true) || model.contains("grok", true)) add(ModelCapability.IMAGE_INPUT)
        if (model.contains("reason", true) || model.contains("thinking", true) || model.startsWith("o")) add(ModelCapability.REASONING)
    }

    private fun inferContextWindow(model: String): Int? = when {
        model.contains("gpt-4.1", true) || model.contains("gemini", true) -> 1_000_000
        model.contains("claude", true) -> 200_000
        model.contains("gpt-4o", true) || model.contains("llama", true) || model.contains("qwen", true) -> 128_000
        model.contains("deepseek", true) -> 64_000
        else -> null
    }
}
