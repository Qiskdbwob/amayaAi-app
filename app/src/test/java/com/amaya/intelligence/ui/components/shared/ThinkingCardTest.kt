package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.appendText
import com.amaya.intelligence.domain.models.appendThinking
import com.amaya.intelligence.domain.models.finishThinking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingCardTest {
    @Test
    fun localEventsKeepProviderOrder() {
        val ordered = emptyList<MessageStep>()
            .appendThinking("first", nowMs = 10)
            .finishThinking(nowMs = 20)
            .appendText("answer")
            .appendThinking("second", nowMs = 30)

        assertEquals(listOf("first", "answer", "second"), ordered.map {
            when (it) {
                is MessageStep.Thinking -> it.text
                is MessageStep.Text -> it.content
                is MessageStep.ToolCall -> it.execution.name
            }
        })
        assertFalse((ordered.first() as MessageStep.Thinking).isStreaming)
        assertTrue((ordered.last() as MessageStep.Thinking).isStreaming)
    }

    @Test
    fun nextThinkingFinishesPreviousThinking() {
        val steps = emptyList<MessageStep>()
            .appendThinking("first", nowMs = 10)
            .appendText("answer")
            .appendThinking("second", nowMs = 30)

        assertFalse((steps.first() as MessageStep.Thinking).isStreaming)
        assertTrue((steps.last() as MessageStep.Thinking).isStreaming)
    }

    @Test
    fun toolFinishesThinking() {
        val thinking = emptyList<MessageStep>().appendThinking("plan", nowMs = 10)
        val steps = thinking.finishThinking(nowMs = 20) + MessageStep.ToolCall(
            execution = com.amaya.intelligence.domain.models.ToolExecution(
                toolCallId = "tool-1",
                name = "read_file",
                arguments = emptyMap()
            )
        )

        assertFalse((steps.first() as MessageStep.Thinking).isStreaming)
    }
}
