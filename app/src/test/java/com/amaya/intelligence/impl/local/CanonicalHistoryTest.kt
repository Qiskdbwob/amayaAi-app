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

    private fun item(kind: String) = JSONObject().put("kind", kind)
}
