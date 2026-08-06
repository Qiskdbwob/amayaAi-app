package com.amaya.intelligence.data.remote.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiRequestCodecTest {
    @Test
    fun responsesUsesCurrentTokenFieldAndOmitsLegacyFields() {
        val json = OpenAiRequestCodec.responsesBase("gpt-test", "rules", 4096, true)
        assertEquals(4096, json.getInt("max_output_tokens"))
        assertEquals(false, json.getBoolean("parallel_tool_calls"))
        assertFalse(json.has("messages"))
        assertFalse(json.has("max_tokens"))
        assertFalse(json.has("max_completion_tokens"))
        assertFalse(json.has("temperature"))
    }

    @Test
    fun responsesIncludeUsesOnlySupportedReasoningFields() {
        val include = OpenAiRequestCodec.responsesInclude()
        assertEquals(listOf("reasoning.encrypted_content"), (0 until include.length()).map(include::getString))
    }

    @Test
    fun responsesToolRequiredIsJsonArray() {
        val tool = AiToolDefinition(
            name = "update_todo",
            description = "Update todos",
            parameters = AiToolParameters(
                properties = mapOf(
                    "merge" to AiToolProperty("boolean", "Merge"),
                    "todos" to AiToolProperty("array", "Todos", items = AiToolPropertyItems("object"))
                ),
                required = listOf("merge", "todos")
            )
        )

        val schema = OpenAiRequestCodec.responsesTool(tool).getJSONObject("parameters")
        assertEquals(listOf("merge", "todos"), schema.getJSONArray("required").let { array ->
            (0 until array.length()).map(array::getString)
        })
    }

    @Test
    fun responsesToolRepairsStringifiedRequired() {
        val tool = AiToolDefinition(
            name = "update_todo",
            description = "Update todos",
            parameters = AiToolParameters(properties = emptyMap(), required = listOf("merge", "todos")),
            rawParametersJson = """{"type":"object","properties":{},"required":"[merge, todos]"}"""
        )

        assertEquals(2, OpenAiRequestCodec.functionSchema(tool).getJSONArray("required").length())
    }

    @Test
    fun compatibleChatOmitsTemperatureByDefault() {
        val json = OpenAiRequestCodec.compatibleChatBase("local", 2048, true, null)
        assertEquals(2048, json.getInt("max_completion_tokens"))
        assertTrue(json.has("messages"))
        assertFalse(json.has("max_tokens"))
        assertFalse(json.has("temperature"))
    }

    @Test
    fun compatibleChatIncludesExplicitTemperature() {
        val json = OpenAiRequestCodec.compatibleChatBase("local", 2048, false, 0.2f)
        assertEquals(0.2, json.getDouble("temperature"), 0.0001)
    }
}
