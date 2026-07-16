package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    private val budgets = PromptBudgetManager()
    private val compressor = ConversationCompressor(budgets)

    @Test
    fun `compression keeps contiguous recent suffix`() {
        val history = listOf(
            ChatMessage(MessageRole.USER, "old-a ".repeat(400)),
            ChatMessage(MessageRole.ASSISTANT, "small-old"),
            ChatMessage(MessageRole.USER, "old-b ".repeat(400)),
            ChatMessage(MessageRole.ASSISTANT, "r1"),
            ChatMessage(MessageRole.USER, "r2"),
            ChatMessage(MessageRole.ASSISTANT, "r3"),
            ChatMessage(MessageRole.USER, "r4"),
            ChatMessage(MessageRole.ASSISTANT, "r5"),
            ChatMessage(MessageRole.USER, "r6"),
            ChatMessage(MessageRole.ASSISTANT, "r7"),
            ChatMessage(MessageRole.USER, "r8")
        )

        val result = compressor.compress(history, 100)
        assertEquals(history.takeLast(result.messages.size), result.messages)
        assertTrue(result.compressedMessageCount > 0)
        assertTrue(result.summary.contains("older messages"))
    }

    @Test
    fun `compression preserves assistant tool span`() {
        val history = buildList {
            repeat(8) { index -> add(ChatMessage(MessageRole.USER, "old-$index ".repeat(40))) }
            add(ChatMessage(MessageRole.ASSISTANT, "tool request"))
            add(ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("call", "result ".repeat(40))))
        }
        val result = compressor.compress(history, 80)
        assertEquals(MessageRole.ASSISTANT, result.messages.first().role)
        assertEquals(MessageRole.TOOL, result.messages.last().role)
    }

    @Test
    fun `budget reserves output tools and safety`() {
        val withTools = budgets.historyBudgetFor(32_768, 8_192, 4_000)
        val withoutTools = budgets.historyBudgetFor(32_768, 8_192, 0)
        assertTrue(withTools < withoutTools)
    }
}
