package com.amaya.intelligence.data.remote.provider.openai

import com.amaya.intelligence.data.remote.api.ReasoningAttachment
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ============================================================================

@JsonClass(generateAdapter = true)
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiToolDef>? = null,
    @Json(name = "max_completion_tokens") val maxTokens: Int = 8192,
    val temperature: Float? = null,
    val stream: Boolean = false,
    @Json(name = "stream_options") val streamOptions: OpenAiStreamOptions? = null,
    /** Reasoning attachment to merge into the JSON body, or null to strip. */
    val reasoning: ReasoningAttachment? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiStreamOptions(
    @Json(name = "include_usage") val includeUsage: Boolean = true
)

@JsonClass(generateAdapter = true)
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiToolDef(
    val type: String = "function",
    val function: OpenAiFunctionDef
)

@JsonClass(generateAdapter = true)
data class OpenAiFunctionDef(
    val name: String,
    val description: String,
    val parametersJson: String
)

@JsonClass(generateAdapter = true)
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunction,
    val index: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiFunction(
    val name: String,
    val arguments: String
)

@JsonClass(generateAdapter = true)
data class OpenAiResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage?
)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @Json(name = "finish_reason") val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class OpenAiUsage(
    @Json(name = "prompt_tokens") val promptTokens: Int,
    @Json(name = "completion_tokens") val completionTokens: Int,
    @Json(name = "total_tokens") val totalTokens: Int
)

@JsonClass(generateAdapter = true)
data class OpenAiStreamChunk(
    // OpenAI-compatible providers may omit these unused metadata fields in delta chunks.
    val id: String? = null,
    val `object`: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<OpenAiStreamChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiStreamChoice(
    val index: Int,
    val delta: OpenAiDelta,
    @Json(name = "finish_reason") val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<OpenAiDeltaToolCall>? = null,
    /** Vendor reasoning field (DeepSeek/vLLM/LM Studio/GLM/Kimi). Moshi ignores when absent. */
    @Json(name = "reasoning_content") val reasoningContent: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiDeltaToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiDeltaFunction? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiDeltaFunction(
    val name: String? = null,
    val arguments: String? = null
)
