package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.ChatResponse
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ToolResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    @Test
    fun `only retryable stream failures without tool calls continue`() {
        assertTrue(canContinueStream(ChatResponse.Incomplete("EOF"), hasToolCalls = false))
        assertTrue(canContinueStream(ChatResponse.Error("Timeout", retryable = true), hasToolCalls = false))
        assertFalse(canContinueStream(ChatResponse.Error("Invalid chunk"), hasToolCalls = false))
        assertFalse(canContinueStream(ChatResponse.Incomplete("EOF"), hasToolCalls = true))
        assertTrue(shouldExecuteReceivedToolCalls(ChatResponse.Incomplete("EOF"), hasToolCalls = true))
        assertTrue(shouldExecuteReceivedToolCalls(ChatResponse.Error("Timeout", retryable = true), hasToolCalls = true))
        assertFalse(shouldExecuteReceivedToolCalls(ChatResponse.Error("Invalid chunk"), hasToolCalls = true))
    }

    @Test
    fun `stream continuation permits tools`() {
        assertFalse(STREAM_CONTINUATION_PROMPT.contains("Do not call tools"))
    }

    private val budgets = PromptBudgetManager()
    private val compressor = ConversationCompressor(budgets)

    @Test
    fun `provider request requires a user query`() {
        assertTrue(hasProviderUserQuery(listOf(ChatMessage(MessageRole.USER, "question"))))
        assertFalse(hasProviderUserQuery(listOf(ChatMessage(MessageRole.USER, images = emptyList()))))
        assertFalse(hasProviderUserQuery(listOf(ChatMessage(MessageRole.ASSISTANT, "answer"))))
        assertFalse(hasProviderUserQuery(listOf(ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("id", "result")))))
    }

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
    fun `auto compaction transcript retains tool call and result`() {
        val transcript = autoCompactionTranscript(
            listOf(
                ChatMessage(MessageRole.USER, "inspect page"),
                ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(com.amaya.intelligence.data.remote.api.ToolCallMessage("call", "browser", mapOf("action" to "get_dom")))),
                ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("call", "page data"))
            ),
            maxChars = 1_000
        )

        assertTrue(transcript.contains("tool_call browser"))
        assertTrue(transcript.contains("tool_result call: page data"))
    }

    @Test
    fun `auto compaction retains full active user turn`() {
        val user = ChatMessage(MessageRole.USER, "latest")
        val toolCall = ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(com.amaya.intelligence.data.remote.api.ToolCallMessage("call", "browser", emptyMap())))
        val toolResult = ChatMessage(MessageRole.TOOL, toolResult = ToolResultMessage("call", "result"))
        val messages = listOf(ChatMessage(MessageRole.USER, "old"), ChatMessage(MessageRole.ASSISTANT, "old response"), user, toolCall, toolResult)
        val fitted = listOf(user, toolCall, toolResult)

        assertEquals(listOf(messages[0], messages[1]), messagesOmittedByContextFit(messages, fitted))
    }

    @Test
    fun `oversized tool result truncates within its character budget`() {
        val truncated = truncateToolResultForContext("result ".repeat(2_000), 400)
        assertTrue(truncated.contains("[tool result truncated by context budget]"))
        assertTrue(truncated.length <= 400)
        assertEquals("short", truncateToolResultForContext("short", 400))
    }

    @Test
    fun `repeated browser failure warns without terminating`() {
        assertEquals(null, repeatedBrowserFailureWarning("NOT_FOUND:button", 1))
        assertTrue(repeatedBrowserFailureWarning("NOT_FOUND:button", 2)!!.contains("2 times"))
    }

    @Test
    fun `budget reserves output tools and safety`() {
        val withTools = budgets.historyBudgetFor(32_768, 8_192, 4_000)
        val withoutTools = budgets.historyBudgetFor(32_768, 8_192, 0)
        assertTrue(withTools < withoutTools)
    }
}
