package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.ConversationEventType
import com.amaya.intelligence.domain.models.conversationEventMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueuedConversationEventTest {
    @Test
    fun `delegation result detail reaches provider at safe iteration boundary`() {
        val event = conversationEventMessage(
            type = ConversationEventType.DELEGATION_COMPLETED,
            label = "Agent 3",
            detail = "Agent 3 output"
        )

        assertEquals(
            "--- Agent 3 done ---\nThis is the final delivered result for this delegation. Use it directly; do not delegate this task again.\nResult detail:\nAgent 3 output",
            queuedConversationEventMessage(event)?.content
        )
    }

    @Test
    fun `queued event keeps its detail until provider injection`() {
        val event = conversationEventMessage(
            type = ConversationEventType.DELEGATION_COMPLETED,
            label = "Agent 2",
            detail = "Agent 2 output"
        )

        val injected = queuedConversationEventMessage(event)

        assertEquals("Agent 2 output", injected?.content?.substringAfter("Result detail:\n"))
        assertEquals(true, injected?.content?.contains("do not delegate this task again"))
    }

    @Test
    fun `non-system queued messages are not injected`() {
        assertNull(queuedConversationEventMessage(conversationEventMessage(
            type = ConversationEventType.DELEGATION_COMPLETED,
            label = "Agent 3",
            role = MessageRole.ASSISTANT
        )))
    }
}
