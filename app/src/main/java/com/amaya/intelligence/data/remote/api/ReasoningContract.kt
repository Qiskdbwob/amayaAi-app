package com.amaya.intelligence.data.remote.api

import org.json.JSONObject

// ── Public enums ─────────────────────────────────────────────────────────────

/** Global, user-facing reasoning level. Single source of truth from the chat bulb. */
enum class ThinkingEffort { NONE, LOW, MEDIUM, HIGH }

/** How a model reasons at a structural level — drives UI affordance + param shape. */
enum class ReasonKind { NONE, TOGGLE, ALWAYS_ON, TIERED }

/** Request wire-shape a provider/model expects. kept separate from ReasonKind so
 *  two TOGGLE models can need different fields (vLLM vs GLM vs Kimi vs MiniMax). */
enum class RequestShape {
    NONE,               // strip every reasoning param
    OPENAI_EFFORT,      // reasoning:{effort:"..."} — OpenAI Responses/Chat + gateways
    VLLM_TOGGLE,        // chat_template_kwargs:{enable_thinking:bool} — vLLM/Groq/Qwen3
    GLM_THINKING,       // thinking:{type:"enabled"|"disabled"} — Z.ai GLM-4.6
    KIMI_THINKING,      // thinking:{type,keep?} — Moonshot Kimi K2.6
    MINIMAX_SPLIT,      // reasoning_split:true|false — MiniMax M2
    MINIMAX_M3_THINKING,// thinking:{type:"disabled"|"adaptive"} — MiniMax M3
    ANTHROPIC_THINKING, // thinking:{type,budget_tokens} — Claude (phase 2)
    GEMINI_BUDGET       // thinkingConfig:{thinkingBudget} — Gemini 2.5 (phase 2)
}

/** Resolved capability for one provider+model. Pure data, no behavior. */
data class ModelReasoningCap(
    val kind: ReasonKind,
    val shape: RequestShape,
    /** When false, NONE is advisory only — model still reasons (realtime-2, o1, deepseek-r1). */
    val canDisable: Boolean = true
)

// ── Per-model catalog (prefix match, case-insensitive) ───────────────────────

/**
 * Model-id prefix → capability. Kept in its own object so [AmayaProviderRegistry]
 * stays transport-only ("no model catalog" per AGENTS).
 *
 * ponytail: ceiling = fetch OpenRouter allowed_passthrough_parameters + provider
 * model-metadata endpoints for dynamic capability; add when static catalog is insufficient.
 */
object ReasoningCatalog {
    private val TIERED_OPENAI = ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
    private val REALTIME = ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = false)
    private val NON_REASONING = ModelReasoningCap(ReasonKind.NONE, RequestShape.NONE, canDisable = false)
    private val ALWAYS_ON = ModelReasoningCap(ReasonKind.ALWAYS_ON, RequestShape.NONE, canDisable = false)
    private val TOGGLE_VLLM = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.VLLM_TOGGLE, canDisable = true)
    private val TOGGLE_GLM = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.GLM_THINKING, canDisable = true)
    private val TOGGLE_KIMI = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.KIMI_THINKING, canDisable = true)
    private val TOGGLE_MINIMAX = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.MINIMAX_SPLIT, canDisable = true)
    private val TOGGLE_MINIMAX_M3 = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.MINIMAX_M3_THINKING, canDisable = true)
    private val TIERED_ANTHROPIC = ModelReasoningCap(ReasonKind.TIERED, RequestShape.ANTHROPIC_THINKING, canDisable = true)
    private val TIERED_GEMINI = ModelReasoningCap(ReasonKind.TIERED, RequestShape.GEMINI_BUDGET, canDisable = true)

    private val PREFIXES: List<Pair<String, ModelReasoningCap>> = listOf(
        // OpenAI reasoning tier
        "gpt-5.6" to TIERED_OPENAI,
        "gpt-5.5" to TIERED_OPENAI,
        "gpt-5.4" to TIERED_OPENAI,
        "o3" to ALWAYS_ON,
        "o1" to ALWAYS_ON,
        // OpenAI realtime can't be disabled
        "gpt-realtime" to REALTIME,
        // OpenAI non-reasoning — strip param or HTTP 400
        "gpt-4o" to NON_REASONING,
        "gpt-4.1" to NON_REASONING,
        "gpt-4-turbo" to NON_REASONING,
        "gpt-3.5" to NON_REASONING,
        // Anthropic
        "claude-opus-4" to TIERED_ANTHROPIC,
        "claude-sonnet-4" to TIERED_ANTHROPIC,
        "claude-3.7" to TIERED_ANTHROPIC,
        // Gemini
        "gemini-2.5-pro" to TIERED_GEMINI,
        "gemini-2.5-flash-thinking" to TIERED_GEMINI,
        "gemini-2.5-flash" to NON_REASONING,
        // DeepSeek
        "deepseek-r1" to ALWAYS_ON,
        "deepseek-reasoner" to ALWAYS_ON,
        "deepseek-chat" to NON_REASONING,
        "deepseek-v3" to NON_REASONING,
        // Qwen / Qwq via vLLM/Groq/Ollama-compat
        "qwen3" to TOGGLE_VLLM,
        "qwq" to TOGGLE_VLLM,
        // Z.ai GLM
        "glm-4.6" to TOGGLE_GLM,
        "glm-4.5" to TOGGLE_GLM,
        // Moonshot Kimi
        "kimi-k2" to TOGGLE_KIMI,
        // MiniMax
        "minimax-m3" to TOGGLE_MINIMAX_M3,
        "minimax-m2" to TOGGLE_MINIMAX,
        // Plain non-reasoning OSS
        "llama-3" to NON_REASONING,
        "mistral" to NON_REASONING,
        "phi-" to NON_REASONING,
        "gemma-" to NON_REASONING
    )

    /** Resolve capability. Falls back to [ReasoningProfileRegistry] for the provider. */
    fun cap(providerId: String, modelId: String): ModelReasoningCap {
        val id = modelId.lowercase().trim()
        PREFIXES.firstOrNull { (prefix, _) -> id.startsWith(prefix) }?.let { (_, cap) -> return cap }
        return ReasoningProfileRegistry.fallback(providerId)
    }
}

// ── Per-provider fallback (used when model prefix is unknown) ────────────────

object ReasoningProfileRegistry {
    fun fallback(providerId: String): ModelReasoningCap = when (providerId) {
        "openai", "openai_codex_bridge" -> ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
        "anthropic" -> ModelReasoningCap(ReasonKind.TIERED, RequestShape.ANTHROPIC_THINKING, canDisable = true)
        "google_gemini_api" -> ModelReasoningCap(ReasonKind.TIERED, RequestShape.GEMINI_BUDGET, canDisable = true)
        "github_models", "vercel_ai_gateway" -> ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
        "openrouter" -> ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
        "groq" -> ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.VLLM_TOGGLE, canDisable = true)
        "deepseek" -> ModelReasoningCap(ReasonKind.ALWAYS_ON, RequestShape.NONE, canDisable = false)
        "glm" -> ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.GLM_THINKING, canDisable = true)
        "kimi" -> ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.KIMI_THINKING, canDisable = true)
        "minimax" -> ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.MINIMAX_SPLIT, canDisable = true)
        // custom_openai_compatible: universal fallback — send no param, parse stream only.
        // Most local users run reasoning models (Qwen3/DeepSeek-R1), so assume always-on.
        else -> ModelReasoningCap(ReasonKind.ALWAYS_ON, RequestShape.NONE, canDisable = false)
    }
}

// ── Request builder ──────────────────────────────────────────────────────────

/** A reasoning param to merge into the request body: `body[key] = value`. null = strip. */
data class ReasoningAttachment(val key: String, val value: Any) {
    init { require(value is JSONObject || value is Boolean) { "value must be JSONObject or Boolean" } }
}

/**
 * Maps (capability, effort) → request attachment. null means "do not attach" (strip).
 * All adapter heterogeneity is contained here; providers just merge the result.
 */
object ReasoningRequestBuilder {
    fun build(cap: ModelReasoningCap, effort: ThinkingEffort?): ReasoningAttachment? {
        if (effort == null) return null
        if (cap.kind == ReasonKind.NONE) return null           // non-reasoning: strip to avoid HTTP 400
        if (cap.kind == ReasonKind.ALWAYS_ON) return null      // always-on: no param, stream carries reasoning
        if (effort == ThinkingEffort.NONE && !cap.canDisable) return null  // realtime etc.: NONE advisory → omit
        return when (cap.shape) {
            RequestShape.NONE -> null
            RequestShape.OPENAI_EFFORT -> ReasoningAttachment(
                "reasoning",
                JSONObject().put("effort", effort.wire())
            )
            RequestShape.VLLM_TOGGLE -> ReasoningAttachment(
                "chat_template_kwargs",
                JSONObject().put("enable_thinking", effort != ThinkingEffort.NONE)
            )
            RequestShape.GLM_THINKING -> ReasoningAttachment(
                "thinking",
                JSONObject().put("type", if (effort == ThinkingEffort.NONE) "disabled" else "enabled")
            )
            RequestShape.KIMI_THINKING -> {
                val obj = JSONObject().put("type", if (effort == ThinkingEffort.NONE) "disabled" else "enabled")
                if (effort != ThinkingEffort.NONE && effort >= ThinkingEffort.MEDIUM) obj.put("keep", "all")
                ReasoningAttachment("thinking", obj)
            }
            RequestShape.MINIMAX_SPLIT -> if (effort == ThinkingEffort.NONE) null
                else ReasoningAttachment("reasoning_split", true)
            RequestShape.MINIMAX_M3_THINKING -> ReasoningAttachment(
                "thinking",
                JSONObject().put("type", if (effort == ThinkingEffort.NONE) "disabled" else "adaptive")
            )
            RequestShape.ANTHROPIC_THINKING -> {
                if (effort == ThinkingEffort.NONE) ReasoningAttachment(
                    "thinking",
                    JSONObject().put("type", "disabled")
                ) else ReasoningAttachment(
                    "thinking",
                    JSONObject().put("type", "enabled").put("budget_tokens", effort.anthropicBudget())
                )
            }
            RequestShape.GEMINI_BUDGET -> ReasoningAttachment(
                "thinkingConfig",
                JSONObject().put("thinkingBudget", if (effort == ThinkingEffort.NONE) 0 else effort.geminiBudget())
            )
        }
    }

    private fun ThinkingEffort.wire(): String = name.lowercase()
    private fun ThinkingEffort.anthropicBudget(): Int = when (this) {
        ThinkingEffort.LOW -> 1024
        ThinkingEffort.MEDIUM -> 4096
        ThinkingEffort.HIGH -> 16000
        ThinkingEffort.NONE -> 0
    }
    private fun ThinkingEffort.geminiBudget(): Int = when (this) {
        ThinkingEffort.LOW -> 8192
        ThinkingEffort.MEDIUM -> 16384
        ThinkingEffort.HIGH -> 24576
        ThinkingEffort.NONE -> 0
    }
}

// ── Response parser (universal field fallback chain) ─────────────────────────

/**
 * Extracts reasoning text from one Chat-Completions stream chunk (as JSONObject).
 * Probes every vendor field shape; returns null when this chunk carries no reasoning.
 * Covers: DeepSeek/vLLM/LM Studio/GLM/Kimi (reasoning_content), MiniMax (reasoning_details[]),
 * OpenRouter-ext (reasoning), Kimi non-stream (message.thinking.reasoning).
 */
object ReasoningStreamParser {
    fun parseReasoningContent(chunk: JSONObject): String? {
        val choices = chunk.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        // Prefer delta, fall back to message (non-streaming shape).
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null

        delta.optString("reasoning_content").takeIf { it.isNotBlank() }?.let { return it }

        delta.optJSONArray("reasoning_details")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotEmpty() }?.let { sb.append(it) }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        delta.optString("reasoning").takeIf { it.isNotBlank() }?.let { return it }

        delta.optJSONObject("thinking")?.optString("reasoning")?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    /** Extract reasoning summary text from a Responses API output item. */
    fun parseResponsesReasoning(item: JSONObject): String? {
        if (item.optString("type") != "reasoning") return null
        val summary = item.optJSONArray("summary") ?: return null
        val sb = StringBuilder()
        for (i in 0 until summary.length()) {
            summary.optJSONObject(i)?.optString("text")?.takeIf { it.isNotEmpty() }?.let { sb.append(it) }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}

// ── Inline <think> tag stripper (stateful per stream) ─────────────────────────

/**
 * Strips `` / `` tokens from a content stream, emitting the
 * inner reasoning separately. Models that never use these tags pass through
 * unchanged (no overhead beyond a contains() check). Covers Qwen3/Ollama-compat
 * and any server that leaks reasoning into the main content body.
 */
class InlineThinkStripper {
    private val thinking = StringBuilder()
    private var inThinking = false
    private val openTag = "<think>"
    private val closeTag = "</think>"

    /** Feed one content delta. Returns (visibleText, thinkingDelta) to emit. */
    fun feed(content: String): Pair<String, String> {
        if (!inThinking && !content.contains(openTag) && !content.contains(closeTag)) {
            return content to ""
        }
        val visible = StringBuilder()
        var cursor = 0
        while (cursor < content.length) {
            if (inThinking) {
                val closeIdx = content.indexOf(closeTag, cursor)
                if (closeIdx < 0) {
                    thinking.append(content, cursor, content.length)
                    break
                }
                thinking.append(content, cursor, closeIdx)
                cursor = closeIdx + closeTag.length
                inThinking = false
            } else {
                val openIdx = content.indexOf(openTag, cursor)
                if (openIdx < 0) {
                    visible.append(content, cursor, content.length)
                    break
                }
                visible.append(content, cursor, openIdx)
                cursor = openIdx + openTag.length
                inThinking = true
            }
        }
        val thinkingDelta = thinking.toString()
        thinking.setLength(0)
        return visible.toString() to thinkingDelta
    }
}
