package com.amaya.intelligence.data.remote.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningCatalogTest {

    // ── Prefix matching ──────────────────────────────────────────────────────

    @Test
    fun gpt5TieredWithDisable() {
        val cap = ReasoningCatalog.cap("openai", "gpt-5")
        assertEquals(ReasonKind.TIERED, cap.kind)
        assertEquals(RequestShape.OPENAI_EFFORT, cap.shape)
        assertTrue(cap.canDisable)
    }

    @Test
    fun gpt5VariantMatchesPrefix() {
        val cap = ReasoningCatalog.cap("openai", "gpt-5-mini")
        assertEquals(RequestShape.OPENAI_EFFORT, cap.shape)
    }

    @Test
    fun o1AlwaysOnCannotDisable() {
        val cap = ReasoningCatalog.cap("openai", "o1")
        assertEquals(ReasonKind.ALWAYS_ON, cap.kind)
        assertEquals(false, cap.canDisable)
    }

    @Test
    fun o3VariantAlwaysOn() {
        val cap = ReasoningCatalog.cap("openai", "o3-mini")
        assertEquals(ReasonKind.ALWAYS_ON, cap.kind)
    }

    @Test
    fun gpt4oNonReasoning() {
        val cap = ReasoningCatalog.cap("openai", "gpt-4o")
        assertEquals(ReasonKind.NONE, cap.kind)
        assertEquals(RequestShape.NONE, cap.shape)
    }

    @Test
    fun deepseekR1AlwaysOn() {
        val cap = ReasoningCatalog.cap("deepseek", "deepseek-reasoner")
        assertEquals(ReasonKind.ALWAYS_ON, cap.kind)
        assertEquals(false, cap.canDisable)
    }

    @Test
    fun qwen3ToggleVllm() {
        val cap = ReasoningCatalog.cap("groq", "qwen3-32b")
        assertEquals(ReasonKind.TOGGLE, cap.kind)
        assertEquals(RequestShape.VLLM_TOGGLE, cap.shape)
    }

    @Test
    fun glmToggle() {
        val cap = ReasoningCatalog.cap("glm", "glm-4.6")
        assertEquals(RequestShape.GLM_THINKING, cap.shape)
    }

    @Test
    fun kimiToggle() {
        val cap = ReasoningCatalog.cap("kimi", "kimi-k2")
        assertEquals(RequestShape.KIMI_THINKING, cap.shape)
    }

    @Test
    fun minimaxToggle() {
        val cap = ReasoningCatalog.cap("minimax", "minimax-m2")
        assertEquals(RequestShape.MINIMAX_SPLIT, cap.shape)
    }

    @Test
    fun llamaNonReasoning() {
        val cap = ReasoningCatalog.cap("custom_openai_compatible", "llama-3.1-70b")
        assertEquals(ReasonKind.NONE, cap.kind)
    }

    @Test
    fun caseInsensitive() {
        val cap = ReasoningCatalog.cap("openai", "GPT-5")
        assertEquals(RequestShape.OPENAI_EFFORT, cap.shape)
    }

    // ── Provider fallback (unknown model) ────────────────────────────────────

    @Test
    fun unknownOpenaiModelFallsBackToTiered() {
        val cap = ReasoningCatalog.cap("openai", "some-future-model")
        assertEquals(RequestShape.OPENAI_EFFORT, cap.shape)
    }

    @Test
    fun unknownAnthropicModelFallsBack() {
        val cap = ReasoningCatalog.cap("anthropic", "claude-future")
        assertEquals(RequestShape.ANTHROPIC_THINKING, cap.shape)
    }

    @Test
    fun unknownGeminiModelFallsBack() {
        val cap = ReasoningCatalog.cap("google_gemini_api", "gemini-future")
        assertEquals(RequestShape.GEMINI_BUDGET, cap.shape)
    }

    @Test
    fun unknownCustomProviderDefaultsAlwaysOn() {
        val cap = ReasoningCatalog.cap("custom_openai_compatible", "mystery-local-model")
        assertEquals(ReasonKind.ALWAYS_ON, cap.kind)
        assertEquals(false, cap.canDisable)
    }
}
