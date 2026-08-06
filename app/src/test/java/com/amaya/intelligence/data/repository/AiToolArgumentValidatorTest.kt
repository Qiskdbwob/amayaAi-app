package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.AiToolDefinition
import com.amaya.intelligence.data.remote.api.AiToolParameters
import com.amaya.intelligence.data.remote.api.AiToolProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolArgumentValidatorTest {
    private val validator = AiToolArgumentValidator()

    @Test
    fun `normalizes integer strings`() {
        val tool = AiToolDefinition(
            name = "wait",
            description = "wait",
            parameters = AiToolParameters(
                properties = mapOf("timeout" to AiToolProperty(type = "integer", description = "timeout")),
                required = listOf("timeout")
            )
        )

        val result = validator.validate("wait", mapOf("timeout" to "1000"), listOf(tool)).getOrThrow()

        assertEquals(1000L, result["timeout"])
    }

    @Test
    fun `tool call ids may repeat in later iterations but not the current batch`() {
        val allowed = setOf("read_file")

        assertTrue(isValidToolCall("call_1", "read_file", allowed, emptySet()))
        assertTrue(!isValidToolCall("call_1", "read_file", allowed, setOf("call_1")))
        assertTrue(!isValidToolCall("call_2", "missing", allowed, emptySet()))
    }

    @Test
    fun `rejects unadvertised and unknown arguments`() {
        val tool = AiToolDefinition(
            name = "read",
            description = "read",
            parameters = AiToolParameters(
                properties = mapOf("path" to AiToolProperty(type = "string", description = "path")),
                required = listOf("path"),
                additionalProperties = false
            )
        )

        assertTrue(validator.validate("missing", emptyMap(), listOf(tool)).isFailure)
        assertTrue(validator.validate("read", mapOf("path" to "a", "extra" to true), listOf(tool)).isFailure)
    }
}
