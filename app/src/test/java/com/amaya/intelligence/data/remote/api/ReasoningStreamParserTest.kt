package com.amaya.intelligence.data.remote.api

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningStreamParserTest {

    @Test
    fun parsesDeepSeekReasoningContent() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("reasoning_content", "step 1"))))
        }
        assertEquals("step 1", ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun parsesReasoningDetailsArray() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().apply {
                put("reasoning_details", JSONArray().apply {
                    put(JSONObject().put("text", "a"))
                    put(JSONObject().put("text", "b"))
                })
            })))
        }
        assertEquals("ab", ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun parsesPlainReasoningField() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("reasoning", "plain"))))
        }
        assertEquals("plain", ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun parsesKimiThinkingReasoning() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().apply {
                put("thinking", JSONObject().put("reasoning", "kimi thinks"))
            })))
        }
        assertEquals("kimi thinks", ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun returnsNullWhenNoReasoningField() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "hello"))))
        }
        assertNull(ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun returnsNullWhenNoChoices() {
        val chunk = JSONObject()
        assertNull(ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun parsesNonStreamMessageShape() {
        val chunk = JSONObject().apply {
            put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("reasoning_content", "non-stream"))))
        }
        assertEquals("non-stream", ReasoningStreamParser.parseReasoningContent(chunk))
    }

    @Test
    fun parsesResponsesReasoningSummary() {
        val item = JSONObject().apply {
            put("type", "reasoning")
            put("summary", JSONArray().apply {
                put(JSONObject().put("text", "sum1"))
                put(JSONObject().put("text", "sum2"))
            })
        }
        assertEquals("sum1sum2", ReasoningStreamParser.parseResponsesReasoning(item))
    }

    @Test
    fun responsesReasoningReturnsNullForNonReasoningType() {
        val item = JSONObject().apply {
            put("type", "message")
            put("summary", JSONArray().put(JSONObject().put("text", "x")))
        }
        assertNull(ReasoningStreamParser.parseResponsesReasoning(item))
    }
}

class InlineThinkStripperTest {

    @Test
    fun passesThroughWhenNoTags() {
        val stripper = InlineThinkStripper()
        val (visible, thinking) = stripper.feed("hello world")
        assertEquals("hello world", visible)
        assertEquals("", thinking)
    }

    @Test
    fun stripsSingleThinkBlock() {
        val stripper = InlineThinkStripper()
        val (visible, thinking) = stripper.feed("a<think>secret</think>b")
        assertEquals("ab", visible)
        assertEquals("secret", thinking)
    }

    @Test
    fun handlesSplitAcrossFeeds() {
        val stripper = InlineThinkStripper()
        val (v1, t1) = stripper.feed("text<think>partial")
        assertEquals("text", v1)
        assertEquals("partial", t1)
        val (v2, t2) = stripper.feed("rest</think>more")
        assertEquals("more", v2)
        assertEquals("rest", t2)
    }

    @Test
    fun handlesMultipleBlocks() {
        val stripper = InlineThinkStripper()
        val (visible, thinking) = stripper.feed("a<think>x</think>b<think>y</think>c")
        assertEquals("abc", visible)
        assertTrue(thinking.contains("x"))
        assertTrue(thinking.contains("y"))
    }
}
