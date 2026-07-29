package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegatedCompletionTest {
    @Test
    fun `current delegation never falls back to a stale assistant`() {
        val currentTurn = listOf(UiMessage(role = MessageRole.ASSISTANT, content = ""))

        assertTrue(completedDelegatedResponse(currentTurn, null).startsWith("[INCOMPLETE]"))
    }

    @Test
    fun `response item text is accepted as final delegated response`() {
        val responseItem = """{"type":"message","content":[{"type":"output_text","text":"final from response item"}]}"""
        val currentTurn = listOf(UiMessage(role = MessageRole.ASSISTANT, content = "", responseItems = listOf(responseItem)))

        assertEquals("final from response item", completedDelegatedResponse(currentTurn, null))
    }

    @Test
    fun `delegation completion can update an older assistant message`() {
        val taskId = 42L
        val execution = ToolExecution(
            toolCallId = "call-42",
            name = "delegate_agent",
            arguments = emptyMap(),
            status = ToolStatus.RUNNING,
            metadata = mapOf("delegationTaskId" to taskId.toString(), "delegationState" to "running")
        )
        val older = UiMessage(
            role = MessageRole.ASSISTANT,
            content = "Delegated",
            toolExecutions = listOf(execution),
            steps = listOf(MessageStep.ToolCall(execution = execution))
        )
        val continuation = UiMessage(role = MessageRole.ASSISTANT, content = "Continuing")
        val updated = completeDelegationMessages(listOf(older, continuation), taskId, "Agent answer", failed = false)

        val completed = updated.first().toolExecutions.single()
        assertEquals(ToolStatus.SUCCESS, completed.status)
        assertEquals("Agent answer", completed.result)
        assertEquals("done", completed.metadata["delegationState"])
        assertEquals(ToolStatus.SUCCESS, (updated.first().steps.single() as MessageStep.ToolCall).execution.status)
        assertEquals("Continuing", updated.last().content)
    }

    @Test
    fun `fast completion can be applied after deferred task identity arrives`() {
        val runningWithoutTaskId = ToolExecution(
            toolCallId = "call-fast",
            name = "delegate_agent",
            arguments = emptyMap(),
            status = ToolStatus.RUNNING
        )
        val deferredIdentity = runningWithoutTaskId.copy(
            metadata = mapOf("delegationTaskId" to "77", "delegationState" to "running")
        )
        val messages = listOf(UiMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolExecutions = listOf(deferredIdentity),
            steps = listOf(MessageStep.ToolCall(execution = deferredIdentity))
        ))

        val completed = completeDelegationMessages(messages, 77, "fast result", failed = false).single()

        assertEquals(ToolStatus.SUCCESS, completed.toolExecutions.single().status)
        assertEquals("fast result", completed.toolExecutions.single().result)
        assertEquals(ToolStatus.SUCCESS, (completed.steps.single() as MessageStep.ToolCall).execution.status)
    }

    @Test
    fun `current visible assistant response wins`() {
        val currentTurn = listOf(UiMessage(role = MessageRole.ASSISTANT, content = "fresh final"))

        assertEquals("fresh final", completedDelegatedResponse(currentTurn, "provider failed"))
    }
}
