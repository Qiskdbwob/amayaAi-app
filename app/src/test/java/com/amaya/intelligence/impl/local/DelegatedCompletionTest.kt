package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.MessageRole
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
    fun `current visible assistant response wins`() {
        val currentTurn = listOf(UiMessage(role = MessageRole.ASSISTANT, content = "fresh final"))

        assertEquals("fresh final", completedDelegatedResponse(currentTurn, "provider failed"))
    }
}
