package com.amaya.intelligence.data.remote.api

import org.json.JSONArray
import org.json.JSONObject

/** Small, testable request-shape helpers shared by OpenAI transports. */
internal object OpenAiRequestCodec {
    fun responsesBase(
        model: String,
        instructions: String?,
        maxOutputTokens: Int,
        stream: Boolean
    ): JSONObject = JSONObject()
        .put("model", model)
        .put("instructions", instructions.orEmpty())
        .put("input", JSONArray())
        .put("store", false)
        .put("stream", stream)
        .put("max_output_tokens", maxOutputTokens)
        .put("parallel_tool_calls", false)

    fun functionSchema(tool: AiToolDefinition): JSONObject {
        val schema = tool.rawParametersJson
            ?.let { JSONObject(it) }
            ?: JSONObject()
                .put("type", tool.parameters.type)
                .put("properties", JSONObject(tool.parameters.properties.mapValues { (_, property) ->
                    JSONObject()
                        .put("type", property.type)
                        .put("description", property.description)
                        .apply {
                            property.enum?.let { put("enum", JSONArray(it)) }
                            property.items?.let { put("items", JSONObject().put("type", it.type)) }
                        }
                }))
                .put("required", JSONArray(tool.parameters.required))
                .put("additionalProperties", tool.parameters.additionalProperties)
        if (schema.opt("required") !is JSONArray) {
            schema.put("required", JSONArray(tool.parameters.required))
        }
        return schema
    }

    fun responsesTool(tool: AiToolDefinition): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", tool.name)
        .put("description", tool.description)
        .put("parameters", functionSchema(tool))
        .put("strict", tool.strict)

    fun compatibleChatBase(
        model: String,
        maxCompletionTokens: Int,
        stream: Boolean,
        temperature: Float?
    ): JSONObject = JSONObject()
        .put("model", model)
        .put("messages", JSONArray())
        .put("max_completion_tokens", maxCompletionTokens)
        .put("stream", stream)
        .apply { temperature?.let { put("temperature", it.toDouble()) } }
}
