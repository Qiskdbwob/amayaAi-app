package com.amaya.intelligence.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.amaya.intelligence.R

object ModelIcon {
    fun resourceId(modelId: String, providerId: String? = null, iconType: String? = null): Int? =
        when (resolveType(modelId, providerId, iconType)) {
            "openai" -> R.drawable.ic_openai
            "grok" -> R.drawable.ic_grok
            "groq" -> R.drawable.ic_groq
            "kimi" -> R.drawable.ic_kimi
            "zai" -> R.drawable.ic_zai
            "deepseek" -> R.drawable.ic_deepseek
            "meta" -> R.drawable.ic_meta
            "minimax" -> R.drawable.ic_minimax
            "mistral" -> R.drawable.ic_mistral
            "qwen" -> R.drawable.ic_qwen
            "gemini" -> R.drawable.ic_gemini
            "claude" -> R.drawable.ic_claude
            else -> null
        }

    private fun resolveType(modelId: String, providerId: String?, iconType: String?): String? {
        val providerType = providerType(providerId) ?: providerType(iconType)
        if (providerType != null) return providerType

        val value = "$modelId ${iconType.orEmpty()}".lowercase()
        return when {
            containsAny(value, "claude", "anthropic") -> "claude"
            containsAny(value, "gemini", "google") -> "gemini"
            containsAny(value, "deepseek") -> "deepseek"
            containsAny(value, "grok", "xai", "x.ai") -> "grok"
            containsAny(value, "groq") -> "groq"
            containsAny(value, "kimi", "moonshot") -> "kimi"
            containsAny(value, "zai", "zhipu", "glm") -> "zai"
            containsAny(value, "minimax", "abab") -> "minimax"
            containsAny(value, "mistral", "mixtral") -> "mistral"
            containsAny(value, "qwen", "tongyi") -> "qwen"
            containsAny(value, "meta", "llama", "facebook") -> "meta"
            containsAny(value, "openai", "gpt", "chatgpt", "codex") ||
                Regex("(^|[^a-z0-9])o[134]([^a-z0-9]|$)").containsMatchIn(value) -> "openai"
            else -> null
        }
    }

    private fun providerType(value: String?): String? = when (value?.lowercase()) {
        "openai", "openai_codex_bridge" -> "openai"
        "anthropic" -> "claude"
        "google", "google_gemini_api" -> "gemini"
        "deepseek" -> "deepseek"
        "xai", "x.ai" -> "grok"
        "groq" -> "groq"
        "moonshot", "kimi" -> "kimi"
        "zai", "zhipu" -> "zai"
        "meta" -> "meta"
        "minimax" -> "minimax"
        "mistral", "mistralai" -> "mistral"
        "qwen" -> "qwen"
        else -> null
    }

    private fun containsAny(value: String, vararg candidates: String): Boolean =
        candidates.any(value::contains)
}

@Composable
fun ModelLeadingIcon(
    modelId: String,
    providerId: String? = null,
    iconType: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val resourceId = ModelIcon.resourceId(modelId, providerId, iconType)
    if (resourceId != null) {
        Icon(
            painter = painterResource(resourceId),
            contentDescription = null,
            modifier = modifier,
            tint = tint
        )
    } else {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            modifier = modifier,
            tint = tint
        )
    }
}
