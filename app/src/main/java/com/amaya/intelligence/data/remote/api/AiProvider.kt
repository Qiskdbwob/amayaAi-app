package com.amaya.intelligence.data.remote.api

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Unified interface for AI providers.
 *
 * All AI providers (Anthropic, OpenAI, Gemini) implement this interface
 * to allow seamless switching between providers.
 */
interface AiProvider {

    /**
     * Provider name for display and logging.
     */
    val name: String


    /**
     * Send a chat request and receive streaming responses.
     *
     * @param request The chat request with messages, tools, and settings
     * @return Flow of chat responses (text deltas, tool calls, or done signals)
     */
    suspend fun chat(request: ChatRequest): Flow<ChatResponse>


}

/**
 * Chat request to an AI provider.
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val tools: List<AiToolDefinition> = emptyList(),
    val maxTokens: Int = 8192,
    val temperature: Float? = null,
    val stream: Boolean = true,
    val connectionId: String = "",
    /** Stable conversation/session id for providers that support prompt caching or session headers. */
    val sessionId: String = ""
)

/**
 * A message in the conversation.
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String? = null,
    val images: List<ChatImage> = emptyList(),
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallMessage>? = null,
    val toolResult: ToolResultMessage? = null,
    /** Opaque Responses API output items preserved for stateless continuation. */
    val responseItems: List<String> = emptyList()
)

data class ChatImage(
    val base64: String,
    val mediaType: String,
    val fileName: String = ""
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

/**
 * A tool call made by the assistant.
 */
data class ToolCallMessage(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val metadata: Map<String, String> = emptyMap() // For provider-specific data
)

/**
 * Result of a tool execution.
 */
data class ToolResultMessage(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
    val metadata: Map<String, String> = emptyMap() // For provider-specific data
)

/** Image payload carried out-of-band from bridge tool JSON. */
internal data class BridgeImageAttachment(
    val base64: String,
    val format: String
) {
    val mediaType: String
        get() = "image/$format"
}

internal fun ToolResultMessage.bridgeImageAttachment(): BridgeImageAttachment? {
    val base64 = metadata["bridge_image_base64"]?.takeIf { it.isNotBlank() } ?: return null
    val rawFormat = metadata["bridge_image_format"]?.lowercase()?.trim().orEmpty()
    val format = when (rawFormat) {
        "png" -> "png"
        "jpg", "jpeg", "" -> "jpeg"
        "webp" -> "webp"
        else -> "jpeg"
    }
    return BridgeImageAttachment(base64 = base64, format = format)
}

internal fun ToolResultMessage.bridgeVisionPrompt(): String =
    "Actual visual screenshot returned by screen.capture. Inspect the image pixels directly for visible UI, text, icons, and layout. Also use the tool-result JSON accessibility block (coordinateGuide, windows/visualLabels, activeWindow, bounds, screenshotBounds, zIndex, state, cursorPosition) to map what you see to exact Windows mouse coordinates and windowId actions. Do not rely only on width/height metadata."

/**
 * Streaming response from an AI provider.
 */
sealed class ChatResponse {

    /**
     * A text chunk from the assistant's response.
     */
    data class TextDelta(val text: String) : ChatResponse()

    /**
     * The assistant wants to call a tool.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: Map<String, Any?>,
        val metadata: Map<String, String> = emptyMap()
    ) : ChatResponse()

    /** Complete typed output item from Responses API, retained across tool iterations. */
    data class ResponseItem(val json: String) : ChatResponse()

    /**
     * The response is complete.
     */
    data class Done(
        val usage: TokenUsage? = null,
        val finishReason: String? = null
    ) : ChatResponse()

    data class Incomplete(
        val reason: String,
        val retryable: Boolean = true
    ) : ChatResponse()

    /** An error occurred. */
    data class Error(
        val message: String,
        val code: String? = null,
        val retryable: Boolean = false
    ) : ChatResponse()
}

/**
 * Token usage statistics.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Tool definition in provider-agnostic format.
 */
data class AiToolDefinition(
    val name: String,
    val description: String,
    val parameters: AiToolParameters,
    /** Full JSON Schema for dynamic tools such as MCP; avoids lossy flattening. */
    val rawParametersJson: String? = null,
    val strict: Boolean = false
) {
    fun schemaJson(): String = rawParametersJson ?: JSONObject()
        .put("type", parameters.type)
        .put("properties", JSONObject(parameters.properties.mapValues { (_, property) ->
            mutableMapOf<String, Any?>(
                "type" to property.type,
                "description" to property.description
            ).apply {
                property.enum?.let { put("enum", it) }
                property.items?.let { put("items", mapOf("type" to it.type)) }
            }
        }))
        .put("required", org.json.JSONArray(parameters.required))
        .put("additionalProperties", parameters.additionalProperties)
        .toString()
}

data class AiToolParameters(
    val type: String = "object",
    val properties: Map<String, AiToolProperty>,
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean = false
)

data class AiToolProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: AiToolPropertyItems? = null
)

data class AiToolPropertyItems(
    val type: String = "string"
)

fun com.squareup.moshi.Moshi.parseJsonArgs(json: String): Result<Map<String, Any?>> = runCatching {
    require(json.isNotBlank()) { "Tool arguments are empty" }
    val type = com.squareup.moshi.Types.newParameterizedType(
        Map::class.java, String::class.java, Any::class.java
    )
    adapter<Map<String, Any?>>(type).fromJson(json)
        ?: error("Tool arguments must be a JSON object")
}
