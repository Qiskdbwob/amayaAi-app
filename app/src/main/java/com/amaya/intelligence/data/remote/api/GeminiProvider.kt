package com.amaya.intelligence.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini AI provider.
 *
 * API Documentation: https://ai.google.dev/gemini-api/docs
 *
 * KEY FEATURES:
 * - Function calling via functionDeclarations
 * - Streaming via streamGenerateContent endpoint
 * - Multimodal support for bridge screenshots via inlineData
 */
@Singleton
class GeminiProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
    private val settingsProvider: () -> AiSettings,
    private val settingsManager: AiSettingsManager
) : AiProvider {

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    override val name = "Google Gemini"

    override suspend fun chat(request: ChatRequest): Flow<ChatResponse> = callbackFlow {
        fun sendResponse(response: ChatResponse): Boolean {
            val result = trySend(response)
            if (result.isFailure) close(IllegalStateException("Gemini stream event buffer overflow"))
            return result.isSuccess
        }
        val settings = settingsProvider()
        val connectionId = request.connectionId.ifBlank {
            settings.activeSelection?.connectionId.orEmpty()
        }
        val apiKey = settingsManager.getConnectionApiKey(connectionId)

        if (apiKey.isBlank()) {
            sendResponse(ChatResponse.Error("Gemini API key is missing for the selected provider", "AUTH_ERROR"))
            close()
            return@callbackFlow
        }

        // Build request body
        val geminiRequest = buildGeminiRequest(request)
        val jsonBody = moshi.adapter(GeminiRequest::class.java).toJson(geminiRequest)

        val endpoint = if (request.stream) "streamGenerateContent" else "generateContent"
        val url = "$BASE_URL/models/${request.model}:$endpoint"

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        if (request.stream) {
            // Gemini uses newline-delimited JSON for streaming
            try {
                val response = httpClient.newCall(httpRequest).awaitResponse()

                if (!response.isSuccessful) {
                    val body = response.body?.readUtf8Limited(MAX_ERROR_BODY_BYTES)
                    sendResponse(ChatResponse.Error("API error: ${response.code} - $body"))
                    close()
                    return@callbackFlow
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                val jsonBuffer = StringBuilder()
                var bracketCount = 0
                var terminalFinishReason: String? = null
                // FIX 2.4: Use escapeCount parity to correctly handle \\" (double-escaped backslash).
                // Old logic: prevChar != '\\' only handled one level — "\\" before '"' was wrongly
                // treated as escape, flipping inString mid-object and causing parse errors.
                var inString = false
                var escapeCount = 0

                reader.use { bufferedReader ->
                    var char: Int
                    while (bufferedReader.read().also { char = it } != -1) {
                        val c = char.toChar()

                        // Skip array brackets at root level
                        if (bracketCount == 0 && (c == '[' || c == ']' || c == ',')) {
                            continue
                        }

                        jsonBuffer.append(c)

                        // Track string state with backslash parity
                        if (c == '\\') {
                            escapeCount++
                        } else {
                            if (c == '"' && escapeCount % 2 == 0) {
                                inString = !inString
                            }
                            escapeCount = 0
                        }

                        if (!inString) {
                            when (c) {
                                '{' -> bracketCount++
                                '}' -> {
                                    bracketCount--
                                    if (bracketCount == 0) {
                                        // Complete JSON object
                                        val json = jsonBuffer.toString().trim()
                                        jsonBuffer.clear()

                                        if (json.isNotEmpty() && json.startsWith("{")) {
                                            val chunk = moshi.adapter(GeminiResponse::class.java).fromJson(json)
                                            terminalFinishReason = chunk?.candidates?.firstOrNull()?.finishReason
                                                ?.takeIf { it.isNotBlank() } ?: terminalFinishReason
                                            processGeminiChunk(json).forEach { event -> sendResponse(event) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (bracketCount != 0 || jsonBuffer.isNotBlank()) {
                    sendResponse(ChatResponse.Incomplete("Gemini stream ended with a partial JSON object"))
                } else if (terminalFinishReason == null) {
                    sendResponse(ChatResponse.Incomplete("Gemini stream ended without finishReason"))
                } else if (terminalFinishReason == "STOP") {
                    sendResponse(ChatResponse.Done(finishReason = terminalFinishReason))
                } else {
                    sendResponse(ChatResponse.Incomplete("Gemini stopped with finishReason=$terminalFinishReason", retryable = false))
                }

            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                sendResponse(ChatResponse.Error("Stream error: ${e.message}"))
            }

            close()
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

                val geminiResponse = moshi.adapter(GeminiResponse::class.java)
                    .fromJson(body ?: "")

                geminiResponse?.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                    part.text?.let { text ->
                        sendResponse(ChatResponse.TextDelta(text))
                    }

                    part.functionCall?.let { functionCall ->
                        sendResponse(ChatResponse.ToolCall(
                            id = "call_${java.util.UUID.randomUUID()}",
                            name = functionCall.name,
                            arguments = functionCall.args ?: emptyMap()
                        ))
                    }
                }

                val finishReason = geminiResponse?.candidates?.firstOrNull()?.finishReason
                if (finishReason.isNullOrBlank()) {
                    sendResponse(ChatResponse.Incomplete("Gemini response ended without finishReason"))
                } else if (finishReason != "STOP") {
                    sendResponse(ChatResponse.Incomplete("Gemini stopped with finishReason=$finishReason", retryable = false))
                } else {
                    sendResponse(ChatResponse.Done(
                        usage = geminiResponse.usageMetadata?.let {
                            TokenUsage(it.promptTokenCount, it.candidatesTokenCount)
                        },
                        finishReason = finishReason
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

    // Returns ALL ChatResponse items from a single Gemini chunk (not just the first).
    // Previous bug: early `return` inside forEach caused only first part to be emitted.
    private fun processGeminiChunk(json: String): List<ChatResponse> {
        return try {
            val chunk = moshi.adapter(GeminiResponse::class.java).fromJson(json)
                ?: return listOf(ChatResponse.Error("Gemini returned an empty stream chunk", retryable = false))
            val responses = mutableListOf<ChatResponse>()

            // Collect thoughtSignature first (comes separately from text/functionCall)
            var thoughtSignature: String? = null
            chunk?.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                if (part.thoughtSignature != null) thoughtSignature = part.thoughtSignature
            }

            chunk?.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                part.text?.let { text ->
                    if (text.isNotEmpty()) {
                        responses.add(ChatResponse.TextDelta(text))
                    }
                }
                part.functionCall?.let { functionCall ->
                    val metadata = if (thoughtSignature != null)
                        mapOf("thoughtSignature" to thoughtSignature!!)
                    else emptyMap()
                    responses.add(ChatResponse.ToolCall(
                        id        = "call_${java.util.UUID.randomUUID()}",
                        name      = functionCall.name,
                        arguments = functionCall.args ?: emptyMap(),
                        metadata  = metadata
                    ))
                }
            }
            responses
        } catch (e: Exception) {
            listOf(ChatResponse.Error("Invalid Gemini stream chunk: ${e.message}", retryable = false))
        }
    }

    private fun buildGeminiRequest(request: ChatRequest): GeminiRequest {
        val contents = mutableListOf<GeminiContent>()

        // Convert messages to Gemini format
        request.messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    contents.add(GeminiContent(
                        role = "user",
                        parts = buildList {
                            msg.content?.let { add(GeminiPart(text = it)) }
                            msg.images.forEach { image ->
                                add(GeminiPart(inlineData = GeminiInlineData(image.mediaType, image.base64)))
                            }
                        }
                    ))
                }
                MessageRole.ASSISTANT -> {
                    val parts = mutableListOf<GeminiPart>()
                    msg.content?.let {
                        if (it.isNotEmpty()) {
                            parts.add(GeminiPart(text = it))
                        }
                    }
                    msg.toolCalls?.forEach { call ->
                        // Include thoughtSignature if present in metadata
                        val thoughtSig = call.metadata["thoughtSignature"]
                        parts.add(GeminiPart(
                            functionCall = GeminiFunctionCall(
                                name = call.name,
                                args = call.arguments
                            ),
                            thoughtSignature = thoughtSig
                        ))
                    }
                    if (parts.isNotEmpty()) {
                        contents.add(GeminiContent(role = "model", parts = parts))
                    }
                }
                MessageRole.TOOL -> {
                    msg.toolResult?.let { result ->
                        // Include thoughtSignature from metadata if present
                        val thoughtSig = result.metadata["thoughtSignature"]
                        // Get function name from metadata (Gemini needs name, not ID)
                        val functionName = result.metadata["toolName"] ?: result.toolCallId
                        val responseParts = mutableListOf<GeminiPart>()
                        responseParts.add(GeminiPart(
                            functionResponse = GeminiFunctionResponse(
                                name = functionName,
                                response = mapOf("result" to result.content)
                            )
                        ))
                        result.bridgeImageAttachment()?.let { attachment ->
                            responseParts.add(GeminiPart(text = result.bridgeVisionPrompt()))
                            responseParts.add(GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = attachment.mediaType,
                                    data = attachment.base64
                                )
                            ))
                        }
                        // Add thoughtSignature as a separate part if present
                        if (thoughtSig != null) {
                            responseParts.add(GeminiPart(
                                text = "",
                                thoughtSignature = thoughtSig
                            ))
                        }
                        contents.add(GeminiContent(
                            role = "user",
                            parts = responseParts
                        ))
                    }
                }
                MessageRole.SYSTEM -> { /* Handled in systemInstruction */ }
            }
        }

        val tools = if (request.tools.isNotEmpty()) {
            listOf(GeminiTools(
                functionDeclarations = request.tools.map { tool ->
                    GeminiFunctionDeclaration(
                        name = tool.name,
                        description = tool.description,
                        parameters = moshi.adapter(Map::class.java)
                            .fromJson(tool.schemaJson())
                            ?: emptyMap<String, Any?>()
                    )
                }
            ))
        } else null

        return GeminiRequest(
            contents = contents,
            systemInstruction = request.systemPrompt?.let {
                GeminiContent(parts = listOf(GeminiPart(text = it)))
            },
            tools = tools,
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = request.maxTokens,
                temperature = request.temperature
            )
        )
    }
}

// ============================================================================
// GEMINI API DATA CLASSES
// ============================================================================

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val tools: List<GeminiTools>? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
    val thoughtSignature: String? = null,
    val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionCall(
    val name: String,
    val args: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionResponse(
    val name: String,
    val response: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class GeminiTools(
    val functionDeclarations: List<GeminiFunctionDeclaration>
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<*, *>
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int = 8192,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiUsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val totalTokenCount: Int = 0
)
