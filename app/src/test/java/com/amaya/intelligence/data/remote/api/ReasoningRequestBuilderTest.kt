package com.amaya.intelligence.data.remote.api

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningRequestBuilderTest {

    @Test
    fun nonReasoningModelStripsAllEfforts() {
        val cap = ModelReasoningCap(ReasonKind.NONE, RequestShape.NONE, canDisable = false)
        for (effort in ThinkingEffort.values()) {
            assertNull(ReasoningRequestBuilder.build(cap, effort), "effort=$effort should be stripped")
        }
    }

    @Test
    fun alwaysOnModelStripsParamRegardlessOfEffort() {
        val cap = ModelReasoningCap(ReasonKind.ALWAYS_ON, RequestShape.NONE, canDisable = false)
        assertNull(ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH))
        assertNull(ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE))
    }

    @Test
    fun nullEffortReturnsNull() {
        val cap = ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
        assertNull(ReasoningRequestBuilder.build(cap, null))
    }

    @Test
    fun noneEffortOnNonDisablingTieredModelIsOmitted() {
        val cap = ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = false)
        assertNull(ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE))
    }

    @Test
    fun openAiEffortAttachesNestedEffortObject() {
        val cap = ModelReasoningCap(ReasonKind.TIERED, RequestShape.OPENAI_EFFORT, canDisable = true)
        val att = ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH)
        assertNotNull(att)
        assertEquals("reasoning", att.key)
        val obj = att.value as JSONObject
        assertEquals("high", obj.getString("effort"))
    }

    @Test
    fun vllmToggleEnablesThinkingWhenEffortPresent() {
        val cap = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.VLLM_TOGGLE, canDisable = true)
        val on = ReasoningRequestBuilder.build(cap, ThinkingEffort.LOW)
        assertNotNull(on)
        assertEquals("chat_template_kwargs", on.key)
        assertTrue((on.value as JSONObject).getBoolean("enable_thinking"))

        val off = ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE)
        assertNotNull(off)
        assertEquals(false, (off.value as JSONObject).getBoolean("enable_thinking"))
    }

    @Test
    fun glmThinkingTogglesType() {
        val cap = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.GLM_THINKING, canDisable = true)
        val enabled = ReasoningRequestBuilder.build(cap, ThinkingEffort.MEDIUM)
        assertEquals("enabled", (enabled!!.value as JSONObject).getString("type"))

        val disabled = ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE)
        assertEquals("disabled", (disabled!!.value as JSONObject).getString("type"))
    }

    @Test
    fun kimiThinkingAddsKeepAtMediumOrHigher() {
        val cap = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.KIMI_THINKING, canDisable = true)
        val low = ReasoningRequestBuilder.build(cap, ThinkingEffort.LOW)!!.value as JSONObject
        assertEquals("enabled", low.getString("type"))
        assertEquals(false, low.has("keep"))

        val high = ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH)!!.value as JSONObject
        assertEquals("all", high.getString("keep"))
    }

    @Test
    fun minimaxSplitOmitsWhenNone() {
        val cap = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.MINIMAX_SPLIT, canDisable = true)
        assertNull(ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE))
        val on = ReasoningRequestBuilder.build(cap, ThinkingEffort.LOW)
        assertEquals(true, on!!.value)
    }

    @Test
    fun minimaxM3ThinkingTogglesType() {
        val cap = ModelReasoningCap(ReasonKind.TOGGLE, RequestShape.MINIMAX_M3_THINKING, canDisable = true)
        val off = ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE)!!.value as JSONObject
        assertEquals("disabled", off.getString("type"))

        val on = ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH)!!.value as JSONObject
        assertEquals("adaptive", on.getString("type"))
    }

    @Test
    fun anthropicThinkingDisabledWhenNone() {
        val cap = ModelReasoningCap(ReasonKind.TIERED, RequestShape.ANTHROPIC_THINKING, canDisable = true)
        val off = ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE)
        assertEquals("disabled", (off!!.value as JSONObject).getString("type"))

        val on = ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH)
        val obj = on!!.value as JSONObject
        assertEquals("enabled", obj.getString("type"))
        assertTrue(obj.getInt("budget_tokens") > 0)
    }

    @Test
    fun geminiBudgetZeroWhenNone() {
        val cap = ModelReasoningCap(ReasonKind.TIERED, RequestShape.GEMINI_BUDGET, canDisable = true)
        val off = ReasoningRequestBuilder.build(cap, ThinkingEffort.NONE)
        assertEquals(0, (off!!.value as JSONObject).getInt("thinkingBudget"))

        val on = ReasoningRequestBuilder.build(cap, ThinkingEffort.HIGH)
        assertTrue((on!!.value as JSONObject).getInt("thinkingBudget") > 0)
    }
}
