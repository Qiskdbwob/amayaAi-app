package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import org.junit.Test

class ToolSecurityTest {
    @Test
    fun `ordinary arguments survive unchanged`() {
        val args = mapOf<String, Any?>("path" to "/tmp/a", "limit" to 5.0)
        assertEquals(args, sanitizeModelArguments(args).getOrThrow())
    }

    @Test
    fun `nested host control keys are rejected`() {
        assertTrue(sanitizeModelArguments(mapOf("params" to mapOf("allow_sensitive" to true))).isFailure)
    }

    @Test
    fun `host control keys are rejected`() {
        listOf("__confirmed", "__eventEmitter", "allow_sensitive", "parent_call_id").forEach { key ->
            assertTrue(key, sanitizeModelArguments(mapOf(key to true)).isFailure)
        }
    }

    @Test
    fun `archive budget fails closed across entries`() {
        val budget = ByteReadBudget(4)
        assertEquals(3, budget.readBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3))).size)
        assertTrue(runCatching { budget.readBytes(ByteArrayInputStream(byteArrayOf(4, 5))) }.isFailure)
    }
}
