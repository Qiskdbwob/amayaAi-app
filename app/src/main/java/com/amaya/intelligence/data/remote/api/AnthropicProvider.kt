package com.amaya.intelligence.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic (Claude) AI provider.
 *
 * API Documentation: https://docs.anthropic.com/en/api/messages
 *
 * KEY FEATURES:
 * - Native tool use support via tool_use content blocks
 * - Streaming via Server-Sent Events
 * - Strong system prompt support
 */
@Singleton
class AnthropicProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
    private val settingsProvider: () -> AiSettings,
    private val settingsManager: AiSettingsManager
) : AiProvider {

    companion object {
        const val BASE_URL = "https://api.anthropic.com/v1"
        const val API_VERSION = "2023-06-01"
    }

    override val name = "Anthropic (Claude)"

    override suspend fun chat(request: ChatRequest): Flow<ChatResponse> = callbackFlow {
        fun sendResponse(response: ChatResponse): Boolean {
            val result = trySend(response)
            if (result.isFailure) close(IllegalStateException("Anthropic stream event buffer overflow"))
            return result.isSuccess
        }
        val connectionId = request.connectionId.ifBlank {
            settingsProvider().activeSelection?.connectionId.orEmpty()
        }
        val apiKey = settingsManager.getConnectionApiKey(connectionId)

        if (apiKey.isBlank()) {
            sendResponse(ChatResponse.Error("Anthropic API key is missing for the selected provider", "AUTH_ERROR"))
            close()
            return@callbackFlow
        }

        // Build request body
        val anthropicRequest = buildAnthropicRequest(request)
        val jsonBody = moshi.adapter(AnthropicRequest::class.java).toJson(anthropicRequest)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        if (request.stream) {
            // Streaming request
            val eventSourceFactory = EventSources.createFactory(httpClient)

            val listener = object : EventSourceListener() {
                private val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
                private var terminal = false
                private var latestUsage: TokenUsage? = null
                private var stopReason: String? = null

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") return

                    try {
                        val event = moshi.adapter(AnthropicStreamEvent::class.java)
                            .fromJson(data) ?: error("Empty Anthropic stream event")

                        when (event.type) {
                            "content_block_start" -> {
                                event.contentBlock?.let { block ->
                                    if (block.type == "tool_use") {
                                        val index = event.index ?: error("Tool block missing index")
                                        toolCallBuilders[index] = ToolCallBuilder(
                                            id = block.id ?: "",
                                            name = block.name ?: ""
                                        )
                                    }
                                }
                            }

                            "content_block_delta" -> {
                                event.delta?.let { delta ->
                                    when (delta.type) {
                                        "text_delta" -> {
                                            delta.text?.let { text ->
                                                sendResponse(ChatResponse.TextDelta(text))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            delta.text?.let { text ->
                                                sendResponse(ChatResponse.ThinkingDelta(text))
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val index = event.index ?: error("Tool delta missing index")
                                            val builder = toolCallBuilders[index] ?: error("Tool delta has no matching block")
                                            delta.partialJson?.let(builder::appendJson)
                                        }
                                    }
                                }
                            }

                            "content_block_stop" -> {
                                toolCallBuilders[event.index]?.let { builder ->
                                    parseJsonArgs(builder.jsonBuilder.toString()).fold(
                                        onSuccess = { args ->
                                            sendResponse(ChatResponse.ToolCall(builder.id, builder.name, args))
                                        },
                                        onFailure = { error ->
                                            terminal = true
                                            sendResponse(ChatResponse.Error("Invalid tool arguments: ${error.message}"))
                                            close()
                                        }
                                    )
                                }
                            }

                            "message_stop" -> {
                                terminal = true
                                if (stopReason in setOf("end_turn", "stop_sequence", "tool_use")) {
                                    sendResponse(ChatResponse.Done(usage = latestUsage, finishReason = stopReason))
                                } else {
                                    sendResponse(ChatResponse.Incomplete(
                                        "Anthropic stopped with stop_reason=${stopReason ?: "missing"}",
                                        retryable = false
                                    ))
                                }
                                close()
                            }

                            "message_delta" -> {
                                event.delta?.stopReason?.let { stopReason = it }
                                event.usage?.let { usage ->
                                    latestUsage = TokenUsage(usage.inputTokens, usage.outputTokens)
                                }
                            }

                            "error" -> {
                                terminal = true
                                val error = event.error
                                sendResponse(ChatResponse.Error(
                                    error?.message ?: "Unknown Anthropic stream error",
                                    error?.type
                                ))
                                eventSource.cancel()
                                close()
                            }
                        }
                    } catch (e: Exception) {
                        terminal = true
                        sendResponse(ChatResponse.Error("Failed to parse event: ${e.message}"))
                        close()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    if (terminal) {
                        close()
                        return
                    }
                    val message = t?.message ?: response?.message ?: "Unknown error"
                    terminal = true
                    sendResponse(ChatResponse.Error(message, retryable = true))
                    close()
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!terminal) sendResponse(ChatResponse.Incomplete("Anthropic stream ended before message_stop"))
                    close()
                }
            }

            val eventSource = eventSourceFactory.newEventSource(httpRequest, listener)

            awaitClose {
                eventSource.cancel()
            }
        } else {
            // Non-streaming request
            try {
                val response = httpClient.newCall(httpRequest).awaitResponse()
                val body = response.body?.readUtf8Limited(
                    if (response.isSuccessful) MAX_REMOTE_BODY_BYTES else MAX_ERROR_BODY_BYTES
                )

                if (!response.isSuccessful) {
                    sendResponse(ChatResponse.Error("API error: ${response.code} - $body"))
                    close()
                    return@callbackFlow
                }

                val anthropicResponse = moshi.adapter(AnthropicResponse::class.java)
                    .fromJson(body ?: "")

                // Process content blocks
                anthropicResponse?.content?.forEach { block ->
                    when (block.type) {
                        "text" -> {
                            sendResponse(ChatResponse.TextDelta(block.text ?: ""))
                        }
                        "thinking" -> {
                            block.text?.takeIf { it.isNotEmpty() }?.let {
                                sendResponse(ChatResponse.ThinkingDelta(it))
                            }
                        }
                        "tool_use" -> {
                            sendResponse(ChatResponse.ToolCall(
                                id = block.id ?: "",
                                name = block.name ?: "",
                                arguments = block.input ?: emptyMap()
                            ))
                        }
                    }
                }

                val stopReason = anthropicResponse?.stopReason
                if (stopReason in setOf("end_turn", "stop_sequence", "tool_use")) {
                    sendResponse(ChatResponse.Done(
                        usage = anthropicResponse?.usage?.let {
                            TokenUsage(it.inputTokens, it.outputTokens)
                        },
                        finishReason = stopReason
                    ))
                } else {
                    sendResponse(ChatResponse.Incomplete(
                        "Anthropic stopped with stop_reason=${stopReason ?: "missing"}",
                        retryable = false
                    ))
                }

            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                sendResponse(ChatResponse.Error("Request failed: ${e.message}"))
            }

            close()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildAnthropicRequest(request: ChatRequest): AnthropicRequest {
        val messages = request.messages.mapNotNull { msg ->
            when (msg.role) {
                MessageRole.USER -> AnthropicMessage(
                    role = "user",
                    content = buildList {
                        msg.content?.let { add(AnthropicContentBlock(type = "text", text = it)) }
                        msg.images.forEach { image ->
                            add(AnthropicContentBlock(
                                type = "image",
                                source = AnthropicImageSource(mediaType = image.mediaType, data = image.base64)
                            ))
                        }
                    }
                )
                MessageRole.ASSISTANT -> {
                    val content = mutableListOf<AnthropicContentBlock>()
                    msg.content?.let { content.add(AnthropicContentBlock(type = "text", text = it)) }
                    msg.toolCalls?.forEach { call ->
                        content.add(AnthropicContentBlock(
                            type = "tool_use",
                            id = call.id,
                            name = call.name,
                            input = call.arguments
                        ))
                    }
                    AnthropicMessage(role = "assistant", content = content)
                }
                MessageRole.TOOL -> {
                    msg.toolResult?.let { result ->
                        val content = mutableListOf(AnthropicContentBlock(
                            type = "tool_result",
                            toolUseId = result.toolCallId,
                            content = result.content,
                            isError = result.isError
                        ))
                        result.bridgeImageAttachment()?.let { attachment ->
                            content.add(AnthropicContentBlock(
                                type = "text",
                                text = result.bridgeVisionPrompt()
                            ))
                            content.add(AnthropicContentBlock(
                                type = "image",
                                source = AnthropicImageSource(
                                    mediaType = attachment.mediaType,
                                    data = attachment.base64
                                )
                            ))
                        }
                        AnthropicMessage(
                            role = "user",
                            content = content
                        )
                    }
                }
                MessageRole.SYSTEM -> null // Handled separately
            }
        }

        val tools = request.tools.map { tool ->
            AnthropicTool(
                name = tool.name,
                description = tool.description,
                inputSchema = moshi.adapter(Map::class.java)
                    .fromJson(tool.schemaJson())
                    ?: emptyMap<String, Any?>()
            )
        }

        val thinkingConfig = buildThinkingConfig(request)

        return AnthropicRequest(
            model = request.model,
            messages = messages,
            system = request.systemPrompt,
            tools = tools.takeIf { it.isNotEmpty() },
            maxTokens = request.maxTokens,
            stream = request.stream,
            thinking = thinkingConfig
        )
    }

    /** Resolve Anthropic extended-thinking config from the request effort + model cap. */
    private fun buildThinkingConfig(request: ChatRequest): AnthropicThinkingConfig? {
        val effort = request.effort ?: return null
        val providerId = request.providerId.ifBlank { "anthropic" }
        val cap = ReasoningCatalog.cap(providerId, request.model)
        val attachment = ReasoningRequestBuilder.build(cap, effort) ?: return null
        // attachment.value for ANTHROPIC_THINKING is a JSONObject {type, budget_tokens}.
        val obj = attachment.value as? org.json.JSONObject ?: return null
        return AnthropicThinkingConfig(
            type = obj.optString("type"),
            budgetTokens = obj.optInt("budget_tokens")
        )
    }

    private fun parseJsonArgs(json: String): Result<Map<String, Any?>> = moshi.parseJsonArgs(json)

    private class ToolCallBuilder(val id: String, val name: String) {
        val jsonBuilder = StringBuilder()
        fun appendJson(json: String) { jsonBuilder.append(json) }
    }
}

// ============================================================================
// ANTHROPIC API DATA CLASSES
// ============================================================================

@JsonClass(generateAdapter = true)
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val tools: List<AnthropicTool>? = null,
    @Json(name = "max_tokens") val maxTokens: Int = 8192,
    val stream: Boolean = false,
    val thinking: AnthropicThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicThinkingConfig(
    val type: String,
    @Json(name = "budget_tokens") val budgetTokens: Int
)

@JsonClass(generateAdapter = true)
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>
)

@JsonClass(generateAdapter = true)
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null,
    @Json(name = "tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    @Json(name = "is_error") val isError: Boolean? = null,
    val source: AnthropicImageSource? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicImageSource(
    val type: String = "base64",
    @Json(name = "media_type") val mediaType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class AnthropicTool(
    val name: String,
    val description: String,
    @Json(name = "input_schema") val inputSchema: Map<*, *>
)

@JsonClass(generateAdapter = true)
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContentBlock>,
    val usage: AnthropicUsage?,
    @Json(name = "stop_reason") val stopReason: String? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicUsage(
    @Json(name = "input_tokens") val inputTokens: Int,
    @Json(name = "output_tokens") val outputTokens: Int
)

@JsonClass(generateAdapter = true)
data class AnthropicStreamEvent(
    val type: String,
    val index: Int? = null,
    @Json(name = "content_block") val contentBlock: AnthropicContentBlock? = null,
    val delta: AnthropicDelta? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicError? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    @Json(name = "partial_json") val partialJson: String? = null,
    @Json(name = "stop_reason") val stopReason: String? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicError(
    val type: String? = null,
    val message: String? = null
)
