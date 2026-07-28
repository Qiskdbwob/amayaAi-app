package com.amaya.intelligence.data.local.dao

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationDaoTest {
    @Test
    fun `SQLite text chunk offset advances by code points`() {
        val chunk = "a".repeat(255_999) + "\uD83D\uDE00"

        assertEquals(256_001, chunk.length)
        assertEquals(256_001, conversationChunkNextOffset(1, chunk))
    }
}
