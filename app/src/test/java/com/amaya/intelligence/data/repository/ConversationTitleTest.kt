package com.amaya.intelligence.data.repository

import com.amaya.intelligence.ui.screens.chat.shared.hyperTextFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationTitleTest {
    @Test
    fun sanitizerKeepsCompactPlainTitle() {
        assertEquals(
            "Perbaiki Judul Chat",
            sanitizeConversationTitle("**Title:** Perbaiki Judul Chat!", "fallback")
        )
    }

    @Test
    fun sanitizerRejectsReasoningAndInvalidWordCounts() {
        assertEquals(
            "Perbaiki judul chat",
            sanitizeConversationTitle("<think>reasoning</think>\nJudul yang terlalu panjang untuk percakapan ini", "Perbaiki judul chat")
        )
        assertEquals(
            "Perbaiki judul chat",
            sanitizeConversationTitle("Chat", "Perbaiki judul chat")
        )
    }

    @Test
    fun sanitizerAcceptsShortTitleAfterReasoning() {
        assertEquals(
            "Perbaiki Judul",
            sanitizeConversationTitle("<think>reasoning</think>\nPerbaiki Judul", "fallback")
        )
    }

    @Test
    fun sanitizerExtractsTaggedTitleFromExtraProse() {
        assertEquals(
            "Audit Logic Codebase",
            sanitizeConversationTitle("Here is the title:\n<title>Audit Logic Codebase</title>\nHope this helps", "fallback")
        )
    }

    @Test
    fun sanitizerRecoversConciseTitleFromVerboseModel() {
        assertEquals(
            "Apa Kabar",
            sanitizeConversationTitle(
                "\"Apa Kabar?\" – A Friendly Indonesian Greeting\n\nWould you like another option?",
                "fallback"
            )
        )
    }

    @Test
    fun sanitizerRejectsMetaIntroduction() {
        assertEquals(
            "fallback",
            sanitizeConversationTitle("Here is your title", "fallback")
        )
    }

    @Test
    fun hyperTextResolvesFromFallbackToGeneratedTitle() {
        assertEquals("Tolong perbaiki judul chat AI", hyperTextFrame("Tolong perbaiki judul chat AI", "Audit Logic Codebase", 0f))
        assertEquals("Audit Logic Codebase", hyperTextFrame("Tolong perbaiki judul chat AI", "Audit Logic Codebase", 1f))
        val midpoint = hyperTextFrame("Tolong perbaiki judul chat AI", "Audit Logic Codebase", 0.5f)
        assertTrue(midpoint.startsWith("Audit Log"))
        assertEquals(midpoint, hyperTextFrame("Tolong perbaiki judul chat AI", "Audit Logic Codebase", 0.5f))
    }

    @Test
    fun fallbackKeepsFullUserMessage() {
        assertEquals("Tolong perbaiki judul chat AI", fallbackConversationTitle("Tolong  perbaiki\njudul chat AI"))
    }
}
