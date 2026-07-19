package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentCompletionTest {
    @Test
    fun `final response is returned without truncation`() {
        val response = "x".repeat(20_000)
        assertEquals(response, completedSubagentResponse(false, 1, 8, response))
    }

    @Test
    fun `provider error survives completion`() {
        assertEquals("[ERROR] provider failed", completedSubagentResponse(false, 1, 8, "[ERROR] provider failed"))
    }

    @Test
    fun `iteration cap reports incomplete`() {
        assertTrue(completedSubagentResponse(true, 8, 8, "interim").startsWith("[INCOMPLETE]"))
    }
}
