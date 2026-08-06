package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentCompletionTest {
    @Test
    fun `final response is returned without truncation`() {
        val response = "x".repeat(20_000)
        assertEquals(response, completedSubagentResponse(false, response))
    }

    @Test
    fun `provider error survives completion`() {
        assertEquals("[ERROR] provider failed", completedSubagentResponse(false, "[ERROR] provider failed"))
    }

    @Test
    fun `blank response reports incomplete`() {
        assertTrue(completedSubagentResponse(false, "").startsWith("[INCOMPLETE]"))
    }
}
