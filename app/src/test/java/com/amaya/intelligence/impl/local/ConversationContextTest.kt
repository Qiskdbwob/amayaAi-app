package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.repository.compressedSessionSummary
import com.amaya.intelligence.data.repository.withCompressedSessionContext
import com.amaya.intelligence.data.repository.delegationHistoryFromJson
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.domain.models.conversationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationContextTest {
    @Test
    fun `delegation history preserves tool call and result pairing`() {
        val history = delegationHistoryFromJson("""[
            {"role":"USER","content":"inspect"},
            {"role":"ASSISTANT","canonicalHistory":[
                "{\"kind\":\"assistant_tool_call\",\"id\":\"call-1\",\"name\":\"browser\",\"arguments\":{}}",
                "{\"kind\":\"tool_result\",\"id\":\"call-1\",\"name\":\"browser\",\"result\":\"ok\",\"isError\":false}"
            ]}
        ]""")

        assertEquals(MessageRole.USER, history[0].role)
        assertEquals("call-1", history[1].toolCalls?.single()?.id)
        assertEquals(MessageRole.TOOL, history[2].role)
        assertEquals("call-1", history[2].toolResult?.toolCallId)
    }

    @Test
    fun `manual compaction event uses shared marker`() {
        val event = com.amaya.intelligence.domain.models.conversationEventMessage(
            com.amaya.intelligence.domain.models.ConversationEventType.COMPACTION,
            "Compacted"
        )
        assertEquals("--- Compacted done ---", event.content)
        assertEquals(com.amaya.intelligence.domain.models.ConversationEventType.COMPACTION, event.conversationEvent()?.type)
    }

    @Test
    fun `compressed context is a system instruction for the next request`() {
        val summary = "Goal: fix login.\nNext: run tests."

        val messages = compressedSessionContext(summary).flatMap { it.toChatMessages() }

        assertEquals(MessageRole.SYSTEM, messages.single().role)
        assertEquals("[COMPRESSED SESSION CONTEXT]\n$summary", messages.single().content)
    }

    @Test
    fun `delete context clears model context`() {
        val context = listOf(UiMessage(role = MessageRole.USER, content = "old"))

        assertTrue(contextAfterHistoryClear(context, deleteContext = true).isEmpty())
        assertEquals(context, contextAfterHistoryClear(context, deleteContext = false))
    }

    @Test
    fun `compressed session summary enters main system prompt`() {
        val history = listOf(
            ChatMessage(MessageRole.SYSTEM, "[COMPRESSED SESSION CONTEXT]\nGoal: fix login."),
            ChatMessage(MessageRole.USER, "old prompt")
        )

        val summary = history.compressedSessionSummary()
        val prompt = "[SYSTEM]\nRules".withCompressedSessionContext(summary)

        assertEquals("Goal: fix login.", summary)
        assertTrue("[COMPRESSED CONVERSATION]\nGoal: fix login." in prompt)
        assertFalse(prompt.contains("[COMPRESSED SESSION CONTEXT]"))
    }
}
