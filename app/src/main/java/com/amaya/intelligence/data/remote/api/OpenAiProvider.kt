package com.amaya.intelligence.data.remote.api

import com.amaya.intelligence.data.remote.provider.openai.*
import com.squareup.moshi.Moshi
import com.amaya.intelligence.data.remote.api.isRetryableHttpStatus
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible AI provider.
 *
 * Handles OpenAI and OpenAI-compatible chat endpoints.
 */
@Singleton
class OpenAiProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
    private val settingsProvider: () -> AiSettings,
    private val settingsManager: AiSettingsManager,
    private val codexAuthManager: CodexAuthManager,
    private val providerModelService: ProviderModelService
) : AiProvider {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val MULTIPART_PREFIX = "\u0000amaya-multipart:"
    }

    override val name = "OpenAI Compatible"

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun chat(request: ChatRequest): Flow<ChatResponse> = callbackFlow {
        val eventSourceRef = AtomicReference<EventSource?>()
        val deltaCoalescer = OpenAiDeltaCoalescer()
        val sendLock = Any()
        var deltaFlushJob: Job? = null

        fun flushDeltas(cancelScheduledFlush: Boolean = true): Boolean = synchronized(sendLock) {
            if (cancelScheduledFlush) {
                deltaFlushJob?.cancel()
                deltaFlushJob = null
            }
            deltaCoalescer.flush()?.let { trySendBlocking(it).isSuccess } ?: true
        }

        fun scheduleDeltaFlush() {
            if (deltaFlushJob?.isActive == true) return
            deltaFlushJob = launch {
                delay(24)
                flushDeltas(cancelScheduledFlush = false)
                deltaFlushJob = null
            }
        }

        fun discardDeltas() = synchronized(sendLock) {
            deltaFlushJob?.cancel()
            deltaFlushJob = null
            deltaCoalescer.clear()
        }

        fun sendResponse(response: ChatResponse): Boolean = synchronized(sendLock) {
            if (isClosedForSend) return@synchronized false
            if (response is ChatResponse.TextDelta || response is ChatResponse.ThinkingDelta) {
                deltaCoalescer.append(response)?.let { if (!trySendBlocking(it).isSuccess) return@synchronized false }
                scheduleDeltaFlush()
                return@synchronized true
            }
            deltaFlushJob?.cancel()
            deltaFlushJob = null
            deltaCoalescer.flush()?.let { if (!trySendBlocking(it).isSuccess) return@synchronized false }
            trySendBlocking(response).isSuccess
        }

        val settings = settingsProvider()
        val connectionId = request.connectionId.ifBlank {
            settings.activeSelection?.connectionId.orEmpty()
        }
        val connection = settings.connections.find { it.id == connectionId }
        val isCodexSubscription = connection?.providerId == "openai_codex_bridge"
        val apiKey = if (isCodexSubscription) {
            codexAuthManager.refreshTokenIfNeeded().orEmpty()
        } else {
            settingsManager.getConnectionApiKey(connectionId)
        }
        if (isCodexSubscription && apiKey.isBlank()) {
            sendResponse(ChatResponse.Error("OpenAI subscription is not signed in. Open Settings → Manage Models → OpenAI and sign in.", retryable = false))
            close()
            return@callbackFlow
        }
        val protocol = connection?.providerId
            ?.let(AmayaProviderRegistry::require)
            ?.adapter
        if (protocol == ProviderAdapter.OPENAI_RESPONSES) {
            val baseUrl = providerModelService.validateConnectionUrl(connection.providerId, connection.baseUrl)
                .getOrElse {
                    sendResponse(ChatResponse.Error("Provider URL is invalid", retryable = false))
                    close()
                    return@callbackFlow
                }
            val body = buildResponsesRequest(request).toString()
            val officialCall = httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/responses")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", if (request.stream) "text/event-stream" else "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            )
            val officialJob = launch(Dispatchers.IO) {
                try {
                    officialCall.awaitResponse().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.readUtf8Limited(MAX_ERROR_BODY_BYTES)
                            sendResponse(ChatResponse.Error(
                                message = friendlyCodexError(response.code, errorBody, request.model, response.message),
                                code = response.code.toString(),
                                retryable = isRetryableHttpStatus(response.code)
                            ))
                            close()
                            return@use
                        }
                        val state = CodexStreamState()
                        if (request.stream) {
                            consumeResponsesSse(response, state) { event ->
                                if (!sendResponse(event)) {
                                    officialCall.cancel()
                                    close(IllegalStateException("OpenAI stream consumer is too slow"))
                                }
                            }
                            if (!state.completed && !officialCall.isCanceled()) {
                                sendResponse(ChatResponse.Error("OpenAI stream ended before a terminal event", retryable = true))
                            }
                        } else {
                            val json = JSONObject(response.body?.readUtf8Limited(MAX_REMOTE_BODY_BYTES).orEmpty())
                            emitResponsesObject(json, state) { sendResponse(it) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!officialCall.isCanceled()) sendResponse(ChatResponse.Error("OpenAI request failed: ${e.message}", retryable = true))
                } finally {
                    close()
                }
            }
            awaitClose {
                discardDeltas()
                officialCall.cancel()
                officialJob.cancel()
            }
            return@callbackFlow
        }
        if (isCodexSubscription) {
            val accountId = codexAuthManager.getChatGptAccountId()
            if (accountId.isNullOrBlank()) {
                sendResponse(ChatResponse.Error("OpenAI account ID was not found in the token. Sign out and sign in again.", retryable = false))
                close()
                return@callbackFlow
            }
            val promptCacheKey = request.sessionId.ifBlank { "amaya-${connectionId.ifBlank { request.model }}" }
            val codexBody = buildCodexResponsesRequest(request, promptCacheKey).toString()
            val codexUrl = "https://chatgpt.com/backend-api/codex/responses"
            val codexCall = httpClient.newCall(
                Request.Builder()
                    .url(codexUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("chatgpt-account-id", accountId)
                    .addHeader("originator", "pi")
                    .addHeader("OpenAI-Beta", "responses=experimental")
                    .addHeader("session_id", promptCacheKey)
                    .addHeader("x-client-request-id", promptCacheKey)
                    .post(codexBody.toRequestBody("application/json".toMediaType()))
                    .build()
            )
            val codexJob = launch(Dispatchers.IO) {
                try {
                    val response = codexCall.awaitResponse()
                    if (!response.isSuccessful) {
                        val body = response.body?.readUtf8Limited(MAX_ERROR_BODY_BYTES)
                        android.util.Log.e("OpenAiProvider", "Codex endpoint failed code=${response.code}")
                        sendResponse(ChatResponse.Error(friendlyCodexError(response.code, body, request.model, response.message), code = response.code.toString(), retryable = isRetryableHttpStatus(response.code)))
                        close()
                        return@launch
                    }
                    val state = CodexStreamState()
                    val dataLines = mutableListOf<String>()
                    response.body?.charStream()?.buffered()?.useLines { lines ->
                        lines.forEach { line ->
                            when {
                                line.isBlank() -> {
                                    val data = dataLines.joinToString("\n").trim()
                                    dataLines.clear()
                                    if (data.isNotBlank()) {
                                        processCodexSseData(data, state, onResponse = { sendResponse(it) }, onComplete = { close() })
                                    }
                                }
                                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
                            }
                        }
                    }
                    val tail = dataLines.joinToString("\n").trim()
                    if (tail.isNotBlank()) processCodexSseData(tail, state, onResponse = { sendResponse(it) }, onComplete = { close() })
                    if (!state.completed && !codexCall.isCanceled()) {
                        sendResponse(ChatResponse.Incomplete("OpenAI stream ended before a terminal event"))
                        close()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (!codexCall.isCanceled()) {
                        sendResponse(ChatResponse.Error("OpenAI request failed: ${e.message}", retryable = true))
                    }
                    close()
                }
            }
            awaitClose {
                discardDeltas()
                codexCall.cancel()
                codexJob.cancel()
            }
            return@callbackFlow
        }
        val baseUrl = connection?.let {
            providerModelService.validateConnectionUrl(it.providerId, it.baseUrl).getOrNull()
        } ?: run {
                sendResponse(ChatResponse.Error("Provider URL is invalid. Reconnect it in Settings → Manage Models.", retryable = false))
                close()
                return@callbackFlow
            }

        // Build request body
        val openaiRequest = buildOpenAiRequest(request)
        val jsonBody = buildOpenAiJsonBody(openaiRequest)

        val httpRequestBuilder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))

        // Add auth header if API key or Codex subscription token is set.
        if (apiKey.isNotBlank()) {
            httpRequestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val httpRequest = httpRequestBuilder.build()

        if (request.stream) {
            // Streaming request
            val eventSourceFactory = EventSources.createFactory(httpClient)

            val listener = object : EventSourceListener() {
                private val toolCallAccumulator = OpenAiToolCallAccumulator()
                private val inlineThinkStripper = InlineThinkStripper()
                private var receivedDone = false
                private var terminal = false
                private var finishReason: String? = null
                private var latestUsage: TokenUsage? = null

                private fun flushToolCalls(): Boolean {
                    if (!toolCallAccumulator.isNotEmpty()) return true
                    for (call in toolCallAccumulator.complete()) {
                        val arguments = parseJsonArgs(call.argumentsJson).getOrElse { error ->
                            terminal = true
                            sendResponse(ChatResponse.Error("Invalid tool arguments: ${error.message}", retryable = false))
                            return false
                        }
                        sendResponse(ChatResponse.ToolCall(call.id, call.name, arguments))
                    }
                    toolCallAccumulator.clear()
                    return true
                }

                private fun completeFromFinishReason() {
                    if (terminal || finishReason == null) return
                    if (normalizeOpenAiFinishReason(finishReason) == "tool_calls") {
                        if (!toolCallAccumulator.isNotEmpty()) {
                            terminal = true
                            sendResponse(ChatResponse.Error("Provider finished with tool_calls but supplied no complete call", retryable = false))
                            return
                        }
                        if (!flushToolCalls()) return
                    } else if (toolCallAccumulator.isNotEmpty()) {
                        terminal = true
                        sendResponse(ChatResponse.Error("Provider left a partial tool call at finish_reason=$finishReason", retryable = false))
                        return
                    }
                    terminal = true
                    val normalizedFinishReason = normalizeOpenAiFinishReason(finishReason)
                    if (isOpenAiCompletedFinishReason(normalizedFinishReason)) {
                        sendResponse(ChatResponse.Done(usage = latestUsage, finishReason = normalizedFinishReason))
                    } else {
                        sendResponse(ChatResponse.Incomplete("Provider stopped with finish_reason=$finishReason", retryable = false))
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        receivedDone = true
                        if (!terminal && flushToolCalls()) {
                            terminal = true
                            val normalizedFinishReason = normalizeOpenAiFinishReason(finishReason)
                            if (normalizedFinishReason == null || isOpenAiCompletedFinishReason(normalizedFinishReason)) {
                                sendResponse(ChatResponse.Done(usage = latestUsage, finishReason = normalizedFinishReason))
                            } else {
                                sendResponse(ChatResponse.Incomplete("Provider stopped with finish_reason=$finishReason", retryable = false))
                            }
                        }
                        if (!flushDeltas()) eventSource.cancel()
                        if (terminal && toolCallAccumulator.isNotEmpty()) eventSource.cancel()
                        close()
                        return
                    }

                    try {
                        val chunk = moshi.adapter(OpenAiStreamChunk::class.java)
                            .fromJson(data) ?: error("Empty Chat Completions stream chunk")
                        chunk.usage?.let { latestUsage = TokenUsage(it.promptTokens, it.completionTokens) }

                        // Usage/keepalive chunks legitimately omit choices.
                        chunk.choices.firstOrNull()?.let { choice ->
                            // Vendor reasoning field (DeepSeek/vLLM/GLM/Kimi) → ThinkingDelta.
                            choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                                sendResponse(ChatResponse.ThinkingDelta(it))
                            }

                            // Handle text delta (strip inline  tags if present).
                            choice.delta.content?.let { content ->
                                val (visible, thinking) = inlineThinkStripper.feed(content)
                                if (thinking.isNotEmpty()) sendResponse(ChatResponse.ThinkingDelta(thinking))
                                if (visible.isNotEmpty()) sendResponse(ChatResponse.TextDelta(visible))
                            }

                            choice.delta.toolCalls?.forEach { toolCall ->
                                toolCallAccumulator.append(
                                    index = toolCall.index,
                                    id = toolCall.id,
                                    name = toolCall.function?.name,
                                    argumentsDelta = toolCall.function?.arguments
                                )
                            }

                            choice.finishReason?.let { finishReason = normalizeOpenAiFinishReason(it) }
                        }


                    } catch (e: Exception) {
                        terminal = true
                        flushDeltas()
                        sendResponse(ChatResponse.Error("Failed to parse chunk: ${e.message}"))
                        eventSource.cancel()
                        close()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    // If we already received [DONE], this is just the connection closing normally
                    if (receivedDone) {
                        flushDeltas()
                        close()
                        return
                    }
                    completeFromFinishReason()
                    if (terminal) {
                        flushDeltas()
                        close()
                        return
                    }

                    val responseBody = try { response?.body?.readUtf8Limited(MAX_ERROR_BODY_BYTES) } catch (e: Exception) { null }
                    android.util.Log.e("OpenAiProvider", "Request failed code=${response?.code}")
                    val message = responseBody ?: t?.message ?: response?.message ?: "Unknown error"
                    terminal = true
                    sendResponse(ChatResponse.Error(message, retryable = true))
                    close()
                }

                override fun onClosed(eventSource: EventSource) {
                    completeFromFinishReason()
                    if (!terminal) {
                        terminal = true
                        sendResponse(ChatResponse.Incomplete("Provider stream closed without [DONE] or finish_reason"))
                    }
                    flushDeltas()
                    close()
                }
            }

            val eventSource = eventSourceFactory.newEventSource(httpRequest, listener)
            eventSourceRef.set(eventSource)

            awaitClose {
                discardDeltas()
                eventSourceRef.getAndSet(null)?.cancel()
            }
        } else {
            // Non-streaming request
            try {
                val response = httpClient.newCall(httpRequest).awaitResponse()
                val body = response.body?.readUtf8Limited(
                    if (response.isSuccessful) MAX_REMOTE_BODY_BYTES else MAX_ERROR_BODY_BYTES
                )

                if (!response.isSuccessful) {
                    sendResponse(ChatResponse.Error("API error: ${response.code} - $body", code = response.code.toString(), retryable = isRetryableHttpStatus(response.code)))
                    close()
                    return@callbackFlow
                }

                val openaiResponse = moshi.adapter(OpenAiResponse::class.java)
                    .fromJson(body ?: "")

                val choice = openaiResponse?.choices?.firstOrNull()
                val nonStreamStripper = InlineThinkStripper()
                choice?.let {
                    // Vendor reasoning field (non-streaming shape).
                    // Moshi does not model reasoning_content on OpenAiMessage; parse raw if needed.
                    it.message.content?.let { content ->
                        val (visible, thinking) = nonStreamStripper.feed(content)
                        if (thinking.isNotEmpty()) sendResponse(ChatResponse.ThinkingDelta(thinking))
                        if (visible.isNotEmpty()) sendResponse(ChatResponse.TextDelta(visible))
                    }

                    it.message.toolCalls?.forEach { toolCall ->
                        val arguments = parseJsonArgs(toolCall.function.arguments).getOrElse { error ->
                            sendResponse(ChatResponse.Error("Invalid tool arguments: ${error.message}", retryable = false))
                            close()
                            return@callbackFlow
                        }
                        sendResponse(ChatResponse.ToolCall(
                            id = toolCall.id,
                            name = toolCall.function.name,
                            arguments = arguments
                        ))
                    }
                }

                val finishReason = normalizeOpenAiFinishReason(choice?.finishReason)
                if (isOpenAiCompletedFinishReason(finishReason)) {
                    sendResponse(ChatResponse.Done(
                        usage = openaiResponse?.usage?.let {
                            TokenUsage(it.promptTokens, it.completionTokens)
                        },
                        finishReason = finishReason
                    ))
                } else {
                    sendResponse(ChatResponse.Incomplete(
                        "Provider stopped with finish_reason=${finishReason ?: "missing"}",
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

    private fun buildResponsesRequest(request: ChatRequest): JSONObject {
        // The cache key is the conversation id, stable for the whole session. Dropping it forfeited
        // prompt caching on every plain-Responses request.
        val body = buildCodexResponsesRequest(request, promptCacheKey = request.sessionId).apply {
            if (request.sessionId.isBlank()) remove("prompt_cache_key")
            remove("text")
        }
        val base = OpenAiRequestCodec.responsesBase(
            request.model,
            request.systemPrompt,
            request.maxTokens,
            request.stream
        )
        base.put("input", body.getJSONArray("input"))
        if (body.has("tools")) base.put("tools", body.getJSONArray("tools"))
        base.put("tool_choice", "auto")
        base.put("include", JSONArray()
            .put("reasoning.encrypted_content")
            .put("reasoning.summary_text"))
        resolveReasoningAttachment(request)?.let { att ->
            when (val v = att.value) {
                is JSONObject -> base.put(att.key, v)
                is Boolean -> base.put(att.key, v)
            }
        }
        return base
    }

    private fun consumeResponsesSse(
        response: okhttp3.Response,
        state: CodexStreamState,
        onResponse: (ChatResponse) -> Unit
    ) {
        val dataLines = mutableListOf<String>()
        response.body?.charStream()?.buffered()?.useLines { lines ->
            lines.forEach { line ->
                when {
                    line.isBlank() -> {
                        val data = dataLines.joinToString("\n").trim()
                        dataLines.clear()
                        if (data.isNotBlank()) processCodexSseData(data, state, onResponse, onComplete = {})
                    }
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                }
            }
        }
        dataLines.joinToString("\n").trim().takeIf { it.isNotBlank() }
            ?.let { processCodexSseData(it, state, onResponse, onComplete = {}) }
    }

    private fun emitResponsesObject(
        response: JSONObject,
        state: CodexStreamState,
        onResponse: (ChatResponse) -> Unit
    ) {
        when (response.optString("status")) {
            "completed" -> {
                emitCodexResponseText(response, onResponse)
                emitCodexResponseItems(response, state, onResponse)
                if (!state.completed) onResponse(ChatResponse.Done(parseCodexUsage(response), "completed"))
            }
            "incomplete" -> onResponse(ChatResponse.Incomplete(
                "OpenAI response incomplete: ${response.optJSONObject("incomplete_details") ?: "unknown reason"}"
            ))
            else -> onResponse(ChatResponse.Error(
                response.optJSONObject("error")?.optString("message") ?: "OpenAI response failed",
                code = response.optString("status"),
                retryable = false
            ))
        }
        state.completed = true
    }

    private fun emitCodexResponseItems(response: JSONObject, state: CodexStreamState, onResponse: (ChatResponse) -> Unit) {
        val output = response.optJSONArray("output") ?: return
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            emitResponseItem(item, state, onResponse)
        }
    }

    private fun buildCodexResponsesRequest(request: ChatRequest, promptCacheKey: String): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("store", false)
            .put("stream", true)
            .put("instructions", request.systemPrompt.orEmpty())
            .put("input", JSONArray())
            .put("text", JSONObject().put("verbosity", "low"))
            .put("include", JSONArray()
                .put("reasoning.encrypted_content")
                .put("reasoning.summary_text"))
            .put("prompt_cache_key", promptCacheKey)
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)

        val input = body.getJSONArray("input")
        val pendingBridgeImages = mutableListOf<ToolResultMessage>()
        fun flushBridgeImages() {
            pendingBridgeImages.forEach { result ->
                result.bridgeImageAttachment()?.let { attachment ->
                    input.put(codexImageMessage(result.bridgeVisionPrompt(), attachment))
                }
            }
            pendingBridgeImages.clear()
        }
        request.messages.forEachIndexed { index, msg ->
            if (msg.responseItems.isNotEmpty()) {
                msg.responseItems.forEach { raw ->
                    runCatching { JSONObject(raw) }.getOrNull()?.let(input::put)
                }
                return@forEachIndexed
            }
            if (msg.role != MessageRole.TOOL) flushBridgeImages()
            when (msg.role) {
                MessageRole.USER -> {
                    if (msg.images.isEmpty()) input.put(codexMessage("user", msg.content.orEmpty(), "input_text"))
                    else input.put(JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", JSONArray().apply {
                            msg.content?.takeIf { it.isNotBlank() }?.let {
                                put(JSONObject().put("type", "input_text").put("text", it))
                            }
                            msg.images.forEach { image ->
                                put(JSONObject()
                                    .put("type", "input_image")
                                    .put("image_url", "data:${image.mediaType};base64,${image.base64}"))
                            }
                        }))
                }
                MessageRole.ASSISTANT -> {
                    msg.content?.takeIf { it.isNotBlank() }?.let { input.put(codexMessage("assistant", it, "output_text")) }
                    msg.toolCalls?.forEach { call ->
                        input.put(JSONObject()
                            .put("type", "function_call")
                            .put("call_id", call.id)
                            .put("name", call.name)
                            .put("arguments", moshi.adapter(Map::class.java).toJson(call.arguments))
                        )
                    }
                }
                MessageRole.TOOL -> msg.toolResult?.let { result ->
                    input.put(JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", result.toolCallId)
                        .put("output", result.content)
                    )
                    if (result.bridgeImageAttachment() != null) pendingBridgeImages.add(result)
                    if (request.messages.getOrNull(index + 1)?.role != MessageRole.TOOL) flushBridgeImages()
                }
                MessageRole.SYSTEM -> msg.content?.takeIf { it.isNotBlank() }?.let { input.put(codexMessage("developer", it, "input_text")) }
            }
        }
        flushBridgeImages()

        if (request.tools.isNotEmpty()) {
            body.put("tools", JSONArray(request.tools.map(OpenAiRequestCodec::responsesTool)))
        }

        return body
    }

    private fun codexMessage(role: String, text: String, contentType: String): JSONObject = JSONObject()
        .put("type", "message")
        .put("role", role)
        .put("content", JSONArray().put(JSONObject().put("type", contentType).put("text", text)))

    private fun codexImageMessage(text: String, attachment: BridgeImageAttachment): JSONObject = JSONObject()
        .put("type", "message")
        .put("role", "user")
        .put("content", JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", text))
            .put(JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:${attachment.mediaType};base64,${attachment.base64}")
            )
        )

    private class CodexStreamState {
        var emittedText: Boolean = false
        var completed: Boolean = false
        val emittedToolCalls: MutableSet<String> = mutableSetOf()
        val emittedResponseItems: MutableSet<String> = mutableSetOf()
        val argumentDeltas: MutableMap<String, StringBuilder> = mutableMapOf()
    }

    private fun processCodexSseData(
        data: String,
        state: CodexStreamState,
        onResponse: (ChatResponse) -> Unit,
        onComplete: () -> Unit
    ) {
        if (state.completed) return
        if (data == "[DONE]") {
            // Responses API completion is authoritative only via response.completed.
            return
        }
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (json.optString("type")) {
            "response.output_text.delta" -> {
                val delta = json.optString("delta", "")
                if (delta.isNotEmpty()) {
                    state.emittedText = true
                    onResponse(ChatResponse.TextDelta(delta))
                }
            }
            "response.function_call_arguments.delta" -> {
                val callId = json.optString("call_id", json.optString("item_id", ""))
                if (callId.isNotBlank()) state.argumentDeltas.getOrPut(callId) { StringBuilder() }.append(json.optString("delta", ""))
            }
            "response.function_call_arguments.done" -> {
                val callId = json.optString("call_id", json.optString("item_id", ""))
                if (callId.isNotBlank() && json.has("arguments")) {
                    state.argumentDeltas[callId] = StringBuilder(json.optString("arguments"))
                }
            }
            "response.output_item.done" -> json.optJSONObject("item")?.let { item ->
                emitResponseItem(item, state, onResponse)
            }
            "response.completed" -> {
                json.optJSONObject("response")?.let { response ->
                    if (!state.emittedText) emitCodexResponseText(response, onResponse)
                    emitCodexResponseItems(response, state, onResponse)
                    if (!state.completed) {
                        onResponse(ChatResponse.Done(usage = parseCodexUsage(response), finishReason = "completed"))
                    }
                } ?: onResponse(ChatResponse.Error("Completed event missing response", retryable = false))
                state.completed = true
                onComplete()
            }
            "response.incomplete" -> {
                val response = json.optJSONObject("response")
                state.completed = true
                onResponse(ChatResponse.Incomplete(
                    "OpenAI response incomplete: ${response?.optJSONObject("incomplete_details") ?: "unknown reason"}"
                ))
                onComplete()
            }
            "error", "response.failed" -> {
                val error = json.optJSONObject("error") ?: json.optJSONObject("response")?.optJSONObject("error")
                state.completed = true
                onResponse(ChatResponse.Error(
                    friendlyCodexError(null, error?.toString() ?: json.toString(), null, error?.optString("message")),
                    code = error?.optString("code"),
                    retryable = error?.optString("code") in setOf("rate_limit_exceeded", "server_error")
                ))
                onComplete()
            }
        }
    }

    private fun emitResponseItem(item: JSONObject, state: CodexStreamState, onResponse: (ChatResponse) -> Unit) {
        val identity = item.optString("id").ifBlank { item.toString() }
        if (state.emittedResponseItems.add(identity)) onResponse(ChatResponse.ResponseItem(item.toString()))
        // OpenAI Responses reasoning items → ThinkingDelta (summary text).
        ReasoningStreamParser.parseResponsesReasoning(item)?.let { onResponse(ChatResponse.ThinkingDelta(it)) }
        emitCodexOutputItem(item, state, onResponse)
    }

    private fun emitCodexOutputItem(item: JSONObject, state: CodexStreamState, onResponse: (ChatResponse) -> Unit) {
        if (item.optString("type") != "function_call") return
        val callId = item.optString("call_id", item.optString("id", ""))
        if (callId.isBlank() || !state.emittedToolCalls.add(callId)) return
        val args = item.optString("arguments", state.argumentDeltas[callId]?.toString().orEmpty())
        val parsed = parseJsonArgs(args).getOrElse { error ->
            onResponse(ChatResponse.Error("Invalid tool arguments: ${error.message}", retryable = false))
            state.completed = true
            return
        }
        onResponse(ChatResponse.ToolCall(
            id = callId,
            name = item.optString("name", ""),
            arguments = parsed
        ))
    }

    private fun emitCodexResponseText(response: JSONObject, onResponse: (ChatResponse) -> Unit) {
        val output = response.optJSONArray("output") ?: return
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) onResponse(ChatResponse.TextDelta(text))
            }
        }
    }

    private fun parseCodexUsage(response: JSONObject): TokenUsage? {
        val usage = response.optJSONObject("usage") ?: return null
        val inputTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
        val outputTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
        return if (inputTokens > 0 || outputTokens > 0) TokenUsage(inputTokens, outputTokens) else null
    }

    private fun friendlyCodexError(code: Int?, body: String?, model: String?, fallback: String?): String {
        val raw = body.orEmpty()
        val parsedMessage = runCatching {
            val obj = JSONObject(raw)
            obj.optJSONObject("error")?.optString("message")
                ?: obj.optJSONObject("response")?.optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val message = parsedMessage ?: fallback ?: raw.ifBlank { "Unknown OpenAI error" }
        val modelHint = model?.takeIf { it.isNotBlank() }?.let { " Selected model: $it." }.orEmpty()
        val prefix = code?.let { "OpenAI API error $it: " } ?: "OpenAI API error: "
        return "$prefix$message$modelHint"
    }

    private fun buildOpenAiRequest(request: ChatRequest): OpenAiRequest {
        val messages = mutableListOf<OpenAiMessage>()

        // Detect if model supports role="tool" for tool results.
        // Some providers (StepFun, older models) only support role="user"/"assistant"/"system".
        // Models that don't support role="tool": stepfun, moonshot, baichuan, yi, etc.
        val modelLower = request.model.lowercase()
        val useToolRole = !modelLower.contains("stepfun") &&
            !modelLower.contains("step-") &&
            !modelLower.contains("moonshot") &&
            !modelLower.contains("baichuan") &&
            !modelLower.contains("yi-")

        // Add system prompt
        request.systemPrompt?.let { systemPrompt ->
            messages.add(OpenAiMessage(
                role = "system",
                content = systemPrompt
            ))
        }

        val pendingBridgeImages = mutableListOf<ToolResultMessage>()
        fun flushBridgeImages() {
            pendingBridgeImages.forEach { result ->
                result.bridgeImageAttachment()?.let { attachment ->
                    messages.add(OpenAiMessage(
                        role = "user",
                        content = openAiVisionContent(result.bridgeVisionPrompt(), attachment)
                    ))
                }
            }
            pendingBridgeImages.clear()
        }

        // Add conversation messages
        request.messages.forEachIndexed { index, msg ->
            if (msg.role != MessageRole.TOOL) flushBridgeImages()
            when (msg.role) {
                MessageRole.USER -> {
                    val content = if (msg.images.isEmpty()) msg.content else {
                        MULTIPART_PREFIX + JSONArray().apply {
                            msg.content?.takeIf { it.isNotBlank() }?.let {
                                put(JSONObject().put("type", "text").put("text", it))
                            }
                            msg.images.forEach { image ->
                                put(JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject()
                                        .put("url", "data:${image.mediaType};base64,${image.base64}")
                                        .put("detail", "high")))
                            }
                        }.toString()
                    }
                    messages.add(OpenAiMessage(role = "user", content = content))
                }
                MessageRole.ASSISTANT -> {
                    val aiToolCalls = msg.toolCalls?.takeIf { it.isNotEmpty() }?.map { call ->
                        OpenAiToolCall(
                            id = call.id,
                            type = "function",
                            function = OpenAiFunction(
                                name = call.name,
                                arguments = moshi.adapter(Map::class.java).toJson(call.arguments)
                            )
                        )
                    }
                    if (aiToolCalls != null && useToolRole) {
                        // Standard OpenAI format: assistant with tool_calls array
                        messages.add(OpenAiMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = aiToolCalls
                        ))
                    } else if (aiToolCalls != null && !useToolRole) {
                        // Fallback for models that don't support tool_calls format
                        // Represent tool calls as plain text in assistant message
                        val toolCallText = aiToolCalls.joinToString("\n") { tc ->
                            "<tool_call name=\"${tc.function.name}\">\n${tc.function.arguments}\n</tool_call>"
                        }
                        messages.add(OpenAiMessage(
                            role = "assistant",
                            content = if (msg.content.isNullOrBlank()) toolCallText
                                      else "${msg.content}\n\n$toolCallText"
                        ))
                    } else {
                        // Regular text message
                        messages.add(OpenAiMessage(
                            role = "assistant",
                            content = msg.content,
                            toolCalls = null
                        ))
                    }
                }
                MessageRole.TOOL -> {
                    msg.toolResult?.let { result ->
                        // Standard OpenAI tool result format
                        // Some providers (StepFun via OpenRouter) reject role="tool"
                        // Detect by model name and use appropriate format
                        if (useToolRole) {
                            messages.add(OpenAiMessage(
                                role = "tool",
                                content = result.content,
                                toolCallId = result.toolCallId
                            ))
                            if (result.bridgeImageAttachment() != null) pendingBridgeImages.add(result)
                            if (request.messages.getOrNull(index + 1)?.role != MessageRole.TOOL) flushBridgeImages()
                        } else {
                            // Fallback for providers that don't support role="tool"
                            // Wrap as assistant+user pair to maintain context
                            messages.add(OpenAiMessage(
                                role = "user",
                                content = "<tool_result tool=\"${result.toolCallId}\">\n${result.content}\n</tool_result>"
                            ))
                            if (result.bridgeImageAttachment() != null) pendingBridgeImages.add(result)
                            if (request.messages.getOrNull(index + 1)?.role != MessageRole.TOOL) flushBridgeImages()
                        }
                    }
                }
                MessageRole.SYSTEM -> { /* Already handled above */ }
            }
        }

        flushBridgeImages()

        val tools = request.tools.map { tool ->
            OpenAiToolDef(
                type = "function",
                function = OpenAiFunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parametersJson = tool.schemaJson()
                )
            )
        }

        return OpenAiRequest(
            model = request.model,
            messages = messages,
            tools = tools.takeIf { it.isNotEmpty() },
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            stream = request.stream,
            streamOptions = if (request.stream) OpenAiStreamOptions(includeUsage = true) else null,
            reasoning = resolveReasoningAttachment(request)
        )
    }

    /** Resolve (cap, effort) → attachment using the per-model catalog + provider fallback. */
    private fun resolveReasoningAttachment(request: ChatRequest): ReasoningAttachment? {
        val effort = request.effort ?: return null
        val providerId = request.providerId.ifBlank {
            settingsProvider().connections.find { it.id == request.connectionId }?.providerId.orEmpty()
        }
        val cap = ReasoningCatalog.cap(providerId, request.model)
        return ReasoningRequestBuilder.build(cap, effort)
    }

    // FIX 4.1: Replaced with shared extension moshi.parseJsonArgs() from AiProvider.kt
    private fun parseJsonArgs(json: String): Result<Map<String, Any?>> = moshi.parseJsonArgs(json)

    private fun openAiVisionContent(text: String, attachment: BridgeImageAttachment): String =
        MULTIPART_PREFIX + JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject()
                    .put("url", "data:${attachment.mediaType};base64,${attachment.base64}")
                    .put("detail", "high")
                )
            )
            .toString()

    /**
     * Serialize an [OpenAiRequest] to a JSON string.
     *
     * Moshi cannot serialize a message `content` field as a JSON *array* (needed for
     * multipart vision messages) because the data class declares it as `String?`.
     * We therefore build the messages array manually with [JSONArray]/[JSONObject] so
     * that any message whose content starts with `[` is emitted as a raw JSON array
     * rather than an escaped string.  All other fields are still serialized by Moshi.
     */
    private fun buildOpenAiJsonBody(req: OpenAiRequest): String {
        val root = OpenAiRequestCodec.compatibleChatBase(
            model = req.model,
            maxCompletionTokens = req.maxTokens,
            stream = req.stream,
            temperature = req.temperature
        )
        if (req.streamOptions != null) {
            root.put("stream_options", JSONObject().put("include_usage", req.streamOptions.includeUsage))
        }

        // Messages — handle multipart content (vision image blocks) correctly
        val messagesArr = JSONArray()
        for (msg in req.messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            if (msg.toolCallId != null) msgObj.put("tool_call_id", msg.toolCallId)
            when {
                msg.content != null && msg.content.startsWith(MULTIPART_PREFIX) -> {
                    msgObj.put("content", org.json.JSONArray(msg.content.removePrefix(MULTIPART_PREFIX)))
                }
                msg.content != null -> msgObj.put("content", msg.content)
                else -> msgObj.put("content", JSONObject.NULL)
            }
            if (msg.toolCalls != null) {
                val tcArr = JSONArray()
                for (tc in msg.toolCalls) {
                    tcArr.put(JSONObject()
                        .put("id", tc.id)
                        .put("type", tc.type)
                        .put("function", JSONObject()
                            .put("name", tc.function.name)
                            .put("arguments", tc.function.arguments)
                        )
                        .apply { if (tc.index != null) put("index", tc.index) }
                    )
                }
                msgObj.put("tool_calls", tcArr)
            }
            messagesArr.put(msgObj)
        }
        root.put("messages", messagesArr)

        // Reasoning attachment (vLLM toggle, GLM/Kimi thinking, MiniMax split, OpenAI effort).
        // null → strip entirely (non-reasoning models avoid HTTP 400; always-on send nothing).
        req.reasoning?.let { att ->
            when (val v = att.value) {
                is JSONObject -> root.put(att.key, v)
                is Boolean -> root.put(att.key, v)
            }
        }

        // Tools
        if (req.tools != null) {
            val toolsArr = JSONArray()
            for (tool in req.tools) {
                toolsArr.put(JSONObject()
                    .put("type", tool.type)
                    .put("function", JSONObject()
                        .put("name", tool.function.name)
                        .put("description", tool.function.description)
                        .put("parameters", JSONObject(tool.function.parametersJson))
                    )
                )
            }
            root.put("tools", toolsArr)
        }

        return root.toString()
    }

}
