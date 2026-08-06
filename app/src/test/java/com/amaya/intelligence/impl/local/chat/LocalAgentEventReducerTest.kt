package com.amaya.intelligence.impl.local.chat

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentEventReducerTest {
    @Test
    fun `text closes thinking and appends answer`() {
        val thinking = LocalAgentEventReducer.thinking(message(), "plan")
        val reduced = LocalAgentEventReducer.text(thinking, "answer")

        assertEquals("answer", reduced.content)
        assertFalse(reduced.isThinking)
        assertTrue(reduced.steps.first() is MessageStep.Thinking)
        assertFalse((reduced.steps.first() as MessageStep.Thinking).isStreaming)
        assertEquals("answer", (reduced.steps.last() as MessageStep.Text).content)
    }

    @Test
    fun `tool lifecycle preserves canonical ordering`() {
        val start = AgentEvent.ToolCallStart("call-1", "read_file", mapOf("path" to "README.md"))
        val execution = ToolExecution("call-1", "read_file", start.arguments, status = ToolStatus.RUNNING)
        val called = LocalAgentEventReducer.toolCall(message(), start, execution, "before")
        val completed = LocalAgentEventReducer.toolResult(
            called,
            AgentEvent.ToolCallResult("call-1", "read_file", "contents", isError = false),
            "after"
        )

        assertEquals(ToolStatus.SUCCESS, completed.toolExecutions.single().status)
        assertEquals("contents", completed.toolExecutions.single().result)
        assertEquals(
            listOf("assistant_text", "assistant_tool_call", "assistant_text", "tool_result"),
            completed.canonicalHistory.map { JSONObject(it).getString("kind") }
        )
    }

    @Test
    fun `terminal finish captures buffered prose and status`() {
        val reduced = LocalAgentEventReducer.finish(message(), "completed", "tail", now = 10L)

        assertEquals("completed", reduced.metadata["turnStatus"])
        assertEquals("10", reduced.metadata["completedAt"])
        assertEquals("tail", JSONObject(reduced.canonicalHistory.single()).getString("text"))
        assertFalse(reduced.isThinking)
    }

    @Test
    fun `stopping repairs pending tools`() {
        val execution = ToolExecution("call-1", "read_file", emptyMap(), status = ToolStatus.RUNNING)
        val reduced = LocalAgentEventReducer.stopTools(
            message().copy(
                toolExecutions = listOf(execution),
                steps = listOf(MessageStep.ToolCall(execution = execution))
            ),
            "Interrupted"
        )

        assertEquals(ToolStatus.ERROR, reduced.toolExecutions.single().status)
        assertEquals("Interrupted", reduced.toolExecutions.single().result)
        assertEquals(ToolStatus.ERROR, (reduced.steps.single() as MessageStep.ToolCall).execution.status)
    }

    private fun message() = UiMessage(role = MessageRole.ASSISTANT, content = "")
}
