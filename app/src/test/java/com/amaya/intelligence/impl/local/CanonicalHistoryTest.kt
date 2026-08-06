package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.MessageRole
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalHistoryTest {
    @Test
    fun `tool iterations retain assistant call result order`() {
        val history = listOf(
            item("assistant_text").put("text", "checking ").toString(),
            item("assistant_tool_call").put("id", "a").put("name", "read_file")
                .put("arguments", JSONObject().put("path", "/tmp/a")).put("metadata", JSONObject()).toString(),
            item("tool_result").put("id", "a").put("name", "read_file").put("result", "A").put("isError", false).toString(),
            item("assistant_text").put("text", "done").toString()
        )

        val messages = canonicalHistoryToChatMessages(history)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("a", messages[0].toolCalls?.single()?.id)
        assertEquals("a", messages[1].toolResult?.toolCallId)
        assertEquals("done", messages[2].content)
    }

    @Test
    fun `rendered transcript load omits opaque model state`() {
        val json = org.json.JSONArray().put(JSONObject()
            .put("role", "ASSISTANT")
            .put("content", "visible")
            .put("responseItems", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", "opaque").toString()))
            .put("canonicalHistory", org.json.JSONArray().put(item("assistant_text").put("text", "canonical").toString())))
            .toString()

        val visible = parseMessagesFromJson(json, includeModelState = false).getOrThrow().single()
        val context = parseMessagesFromJson(json).getOrThrow().single()

        assertEquals("visible", visible.content)
        assertEquals(emptyList<String>(), visible.responseItems)
        assertEquals(emptyList<String>(), visible.canonicalHistory)
        assertEquals(1, context.responseItems.size)
        assertEquals(1, context.canonicalHistory.size)
    }

    private fun item(kind: String) = JSONObject().put("kind", kind)
}
