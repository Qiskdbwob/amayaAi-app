package com.amaya.intelligence.impl.common.conversation

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.MessageAttachment
import com.amaya.intelligence.domain.models.ConversationEventType
import com.amaya.intelligence.domain.models.conversationEvent
import com.amaya.intelligence.domain.models.conversationEventMessage
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.SubagentExecution
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.tools.TodoItem
import com.amaya.intelligence.tools.TodoStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConversationJsonCodecTest {
    @Test
    fun localRoundTripPreservesConversationFields() {
        val execution = ToolExecution(
            toolCallId = "tool-1",
            name = "read_file",
            arguments = mapOf("path" to "src/Main.kt", "line" to 3),
            result = "content",
            status = ToolStatus.SUCCESS,
            children = listOf(SubagentExecution(0, "review", "check", "ok", ToolStatus.SUCCESS)),
            metadata = mapOf("source" to "local", "completedAt" to "20")
        )
        val original = UiMessage(
            id = "message-1",
            role = MessageRole.ASSISTANT,
            content = "answer",
            formattedContent = "**answer**",
            thinking = "reasoning",
            thinkingStartedAt = 10,
            thinkingDurationMs = 42,
            timestamp = 99,
            toolExecutions = listOf(execution),
            steps = listOf(
                MessageStep.Thinking("think-1", "reasoning", false, 10, 42),
                MessageStep.Text("text-1", "answer", "**answer**"),
                MessageStep.ToolCall("step-tool-1", execution)
            ),
            todoItems = listOf(TodoItem(1, TodoStatus.COMPLETED, "done", "doing")),
            metadata = mapOf("completedAt" to "99"),
            attachments = listOf(MessageAttachment("image/png", "aGVsbG8=", "image.png")),
            responseItems = listOf("response-item"),
            canonicalHistory = listOf("history-item")
        )

        val decoded = ConversationJsonCodec.parseLocal(
            ConversationJsonCodec.serializeLocal(listOf(original))
        ).getOrThrow().single()

        assertEquals(original.copy(toolExecutions = decoded.toolExecutions, steps = decoded.steps), decoded)
        assertEquals(execution.arguments["path"], decoded.toolExecutions.single().arguments["path"])
        assertEquals(execution.children, decoded.toolExecutions.single().children)
    }

    @Test
    fun localRoundTripPreservesWorkSummaryEventStep() {
        val event = conversationEventMessage(ConversationEventType.COMPACTION, "Compacted")
        val message = UiMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            steps = listOf(MessageStep.Event(event = event.conversationEvent()!!))
        )

        val decoded = ConversationJsonCodec.parseLocal(
            ConversationJsonCodec.serializeLocal(listOf(message))
        ).getOrThrow().single()

        assertEquals(MessageStep.Event::class, decoded.steps.single()::class)
        assertEquals("Compacted", decoded.steps.single().let { (it as MessageStep.Event).event.label })
    }

    @Test
    fun localLoadMarksInterruptedToolsAsErrors() {
        val running = ToolExecution("tool-1", "read_file", emptyMap(), status = ToolStatus.RUNNING)
        val message = UiMessage(role = MessageRole.ASSISTANT, content = "", toolExecutions = listOf(running))

        val decoded = ConversationJsonCodec.parseLocal(
            ConversationJsonCodec.serializeLocal(listOf(message))
        ).getOrThrow().single().toolExecutions.single()

        assertEquals(ToolStatus.ERROR, decoded.status)
        assertEquals("Stopped before completion", decoded.result)
        assertFalse(decoded.metadata["approvalState"] == "pending")
    }

    @Test
    fun windowsBridgeRoundTripPreservesThinkingDurationAndTools() {
        val execution = ToolExecution("tool-1", "shell", mapOf("command" to "pwd"), "ok", ToolStatus.SUCCESS)
        val message = UiMessage(
            id = "message-1",
            role = MessageRole.ASSISTANT,
            content = "done",
            timestamp = 5,
            toolExecutions = listOf(execution),
            steps = listOf(MessageStep.Thinking("thinking-1", "work", false, 1, 4), MessageStep.ToolCall("step-1", execution))
        )

        val decoded = ConversationJsonCodec.parseWindowsBridge(
            ConversationJsonCodec.serializeWindowsBridge(listOf(message))
        ).single()

        assertEquals(4, (decoded.steps.first() as MessageStep.Thinking).durationMs)
        assertEquals("pwd", decoded.toolExecutions.single().arguments["command"])
        assertEquals(ToolStatus.SUCCESS, decoded.toolExecutions.single().status)
    }

    @Test
    fun opencodeRoundTripPreservesThinkingDurationAndToolResult() {
        val execution = ToolExecution("tool-1", "read", emptyMap(), "ok", ToolStatus.SUCCESS)
        val message = UiMessage(
            id = "message-1",
            role = MessageRole.ASSISTANT,
            content = "done",
            thinking = "work",
            thinkingDurationMs = 7,
            steps = listOf(MessageStep.ToolCall("step-1", execution))
        )

        val decoded = ConversationJsonCodec.parseOpencode(
            ConversationJsonCodec.serializeOpencode(listOf(message), "session-1")
        ).single()

        assertEquals(7, decoded.thinkingDurationMs)
        assertEquals("ok", decoded.toolExecutions.single().result)
        assertEquals(ToolStatus.SUCCESS, decoded.toolExecutions.single().status)
    }
}
