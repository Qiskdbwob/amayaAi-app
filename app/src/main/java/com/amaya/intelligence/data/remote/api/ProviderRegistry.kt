package com.amaya.intelligence.data.remote.api

enum class ProviderCategory { SUBSCRIPTION, API, CUSTOM }

enum class ProviderAdapter { OPENAI_RESPONSES, OPENAI_COMPATIBLE, ANTHROPIC, GEMINI, CODEX }



data class ProviderConfig(
    val id: String,
    val displayName: String,
    val category: ProviderCategory,
    val adapter: ProviderAdapter,
    val defaultBaseUrl: String?,
    val credentialRequired: Boolean
) {
    val isSubscription: Boolean get() = category == ProviderCategory.SUBSCRIPTION
    val isCustom: Boolean get() = category == ProviderCategory.CUSTOM
}

object AmayaProviderRegistry {
    val providers: List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "openai_codex_bridge",
            displayName = "OpenAI",
            category = ProviderCategory.SUBSCRIPTION,
            adapter = ProviderAdapter.CODEX,
            defaultBaseUrl = null,
            credentialRequired = false
        ),
        ProviderConfig(
            id = "openai",
            displayName = "OpenAI API",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_RESPONSES,
            defaultBaseUrl = "https://api.openai.com/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "anthropic",
            displayName = "Anthropic API",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.ANTHROPIC,
            defaultBaseUrl = "https://api.anthropic.com/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "google_gemini_api",
            displayName = "Google Gemini API",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.GEMINI,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "github_models",
            displayName = "GitHub Models",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://models.github.ai/inference",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "vercel_ai_gateway",
            displayName = "Vercel AI Gateway",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://ai-gateway.vercel.sh/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "openrouter",
            displayName = "OpenRouter",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "groq",
            displayName = "Groq",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "deepseek",
            displayName = "DeepSeek",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.deepseek.com",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "xai",
            displayName = "xAI",
            category = ProviderCategory.API,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.x.ai/v1",
            credentialRequired = true
        ),
        ProviderConfig(
            id = "custom_openai_compatible",
            displayName = "OpenAI-compatible",
            category = ProviderCategory.CUSTOM,
            adapter = ProviderAdapter.OPENAI_COMPATIBLE,
            defaultBaseUrl = null,
            credentialRequired = false
        )
    )

    fun find(id: String?): ProviderConfig? = providers.firstOrNull { it.id == id }

    fun require(id: String): ProviderConfig =
        find(id) ?: error("Unsupported provider: $id")

    fun displayName(id: String?): String =
        find(id)?.displayName ?: id?.takeIf { it.isNotBlank() } ?: "Unknown Provider"

}
