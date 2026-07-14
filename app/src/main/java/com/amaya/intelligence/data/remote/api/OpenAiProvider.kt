package com.amaya.intelligence.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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
    }
    
    override val name = "OpenAI Compatible"
    
    override suspend fun chat(request: ChatRequest): Flow<ChatResponse> = callbackFlow {
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
            trySend(ChatResponse.Error("OpenAI subscription is not signed in. Open Settings → Manage Models → OpenAI and sign in.", retryable = false))
            close()
            return@callbackFlow
        }
        if (isCodexSubscription) {
            val accountId = codexAuthManager.getChatGptAccountId()
            if (accountId.isNullOrBlank()) {
                trySend(ChatResponse.Error("OpenAI account ID was not found in the token. Sign out and sign in again.", retryable = false))
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
                    val response = codexCall.execute()
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        android.util.Log.e("OpenAiProvider", "Codex endpoint failed code=${response.code} url=$codexUrl")
                        trySend(ChatResponse.Error(friendlyCodexError(response.code, body, request.model, response.message), retryable = response.code != 401))
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
                                        processCodexSseData(data, state, onResponse = { trySend(it) }, onComplete = { close() })
                                    }
                                }
                                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
                            }
                        }
                    }
                    val tail = dataLines.joinToString("\n").trim()
                    if (tail.isNotBlank()) processCodexSseData(tail, state, onResponse = { trySend(it) }, onComplete = { close() })
                    if (!state.completed) {
                        trySend(ChatResponse.Done())
                        close()
                    }
                } catch (e: Exception) {
                    if (!codexCall.isCanceled()) {
                        trySend(ChatResponse.Error("OpenAI request failed: ${e.message}", retryable = true))
                    }
                    close()
                }
            }
            awaitClose {
                codexCall.cancel()
                codexJob.cancel()
            }
            return@callbackFlow
        }
        val baseUrl = connection?.let {
            providerModelService.validateConnectionUrl(it.providerId, it.baseUrl).getOrNull()
        } ?: run {
                trySend(ChatResponse.Error("Provider URL is invalid. Reconnect it in Settings → Manage Models.", retryable = false))
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
                private val toolCallBuilders = mutableMapOf<Int, OpenAiToolCallBuilder>()
                private var receivedDone = false
                // Queue-based approach: track which builders have received their args yet.
                // Server may send args sequentially (tool0-header, tool0-args, tool1-header, tool1-args)
                // or out-of-order (tool0-header, tool1-header, tool0-args, tool1-args).
                // argsReceived[idx] = true means this builder already got its args chunk.
                private val argsReceived = mutableSetOf<Int>()
                
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        receivedDone = true
                        // Emit any pending tool calls that weren't flushed by finish_reason
                        // This handles local models that send [DONE] without finish_reason
                        if (toolCallBuilders.isNotEmpty()) {
                            toolCallBuilders.keys.sorted().forEach { idx ->
                                val builder = toolCallBuilders[idx] ?: return@forEach
                                val rawArgs = builder.argumentsBuilder.toString()
                                if (builder.isComplete()) {
                                    trySend(ChatResponse.ToolCall(
                                        id = builder.id,
                                        name = builder.name,
                                        arguments = parseJsonArgs(rawArgs)
                                    ))
                                }
                            }
                            toolCallBuilders.clear()
                        }
                        trySend(ChatResponse.Done())
                        close()
                        return
                    }
                    
                    try {
                        val chunk = moshi.adapter(OpenAiStreamChunk::class.java)
                            .fromJson(data) ?: return
                        
                        chunk.choices.firstOrNull()?.let { choice ->
                            // Handle text delta
                            choice.delta.content?.let { content ->
                                trySend(ChatResponse.TextDelta(content))
                            }
                            
                            // Handle tool calls
                            choice.delta.toolCalls?.forEach { toolCall ->
                                val rawIndex = toolCall.index
                                val isNewToolCall = toolCall.id != null
                                val hasArgs = !toolCall.function?.arguments.isNullOrEmpty()
                                
                                if (isNewToolCall) {
                                    // New tool call — use explicit index if valid, else next slot
                                    val index = if (rawIndex != null && rawIndex >= 0) rawIndex else toolCallBuilders.size
                                    toolCallBuilders[index] = OpenAiToolCallBuilder(
                                        id = toolCall.id!!,
                                        name = toolCall.function?.name ?: ""
                                    )
                                    
                                    // Handle args in same chunk as header
                                    if (hasArgs) {
                                        toolCallBuilders[index]?.argumentsBuilder?.append(toolCall.function!!.arguments!!)
                                        argsReceived.add(index)
                                    }
                                } else if (hasArgs) {
                                    // Continuation args chunk — server often sends index=0 for ALL args
                                    // regardless of which tool they belong to. So we IGNORE the raw index
                                    // and use queue: find first builder without args yet.
                                    val targetIndex = toolCallBuilders.keys.sorted()
                                        .firstOrNull { it !in argsReceived }
                                        ?: toolCallBuilders.keys.maxOrNull() ?: 0
                                    
                                    val args = toolCall.function!!.arguments!!
                                    val targetBuilder = toolCallBuilders[targetIndex]
                                    if (targetBuilder != null) {
                                        targetBuilder.argumentsBuilder.append(args)
                                        argsReceived.add(targetIndex)
                                    }
                                }
                            }
                            
                            // Check finish reason — only flush tool calls here if finish_reason is explicit "tool_calls"
                            // For "stop", let [DONE] handler flush — some models send args after finish_reason
                            choice.finishReason?.let { reason ->
                                when (reason) {
                                    "tool_calls" -> {
                                        // Explicit tool_calls finish — safe to flush now
                                        toolCallBuilders.keys.sorted().forEach { idx ->
                                            val builder = toolCallBuilders[idx] ?: return@forEach
                                            val rawArgs = builder.argumentsBuilder.toString()
                                            if (builder.isComplete()) {
                                                trySend(ChatResponse.ToolCall(
                                                    id = builder.id,
                                                    name = builder.name,
                                                    arguments = parseJsonArgs(rawArgs)
                                                ))
                                            }
                                        }
                                        toolCallBuilders.clear()
                                        val usage = chunk.usage?.let { TokenUsage(it.promptTokens, it.completionTokens) }
                                        trySend(ChatResponse.Done(finishReason = reason, usage = usage))
                                        return
                                    }
                                    "stop" -> {
                                        // DO NOT flush tool calls here — args may still be streaming
                                        // Let [DONE] handler flush after all chunks received
                                        val usage = chunk.usage?.let { TokenUsage(it.promptTokens, it.completionTokens) }
                                        trySend(ChatResponse.Done(finishReason = reason, usage = usage))
                                        return
                                    }
                                }
                            }
                        }
                        
                        // Handle usage in final chunk (only reached if finish_reason != "stop")
                        chunk.usage?.let { usage ->
                            trySend(ChatResponse.Done(
                                usage = TokenUsage(usage.promptTokens, usage.completionTokens)
                            ))
                        }
                        
                    } catch (e: Exception) {
                        trySend(ChatResponse.Error("Failed to parse chunk: ${e.message}"))
                    }
                }
                
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    // If we already received [DONE], this is just the connection closing normally
                    if (receivedDone) {
                        close()
                        return
                    }
                    
                    // Flush any pending tool calls before checking error
                    if (toolCallBuilders.isNotEmpty()) {
                        toolCallBuilders.keys.sorted().forEach { idx ->
                            val builder = toolCallBuilders[idx] ?: return@forEach
                            if (builder.isComplete()) {
                                trySend(ChatResponse.ToolCall(
                                    id = builder.id,
                                    name = builder.name,
                                    arguments = parseJsonArgs(builder.argumentsBuilder.toString())
                                ))
                            }
                        }
                        toolCallBuilders.clear()
                        // Treat as done if we had tool calls — server closed after sending them
                        trySend(ChatResponse.Done())
                        close()
                        return
                    }
                    
                    // Socket closed / Connection reset / EOF after 200 = server closed stream normally
                    val errorMsg = t?.message ?: ""
                    val isNormalClose = response?.code == 200 || 
                        errorMsg.contains("Socket closed", ignoreCase = true) ||
                        errorMsg.contains("Connection reset", ignoreCase = true) ||
                        errorMsg.contains("EOF", ignoreCase = true) ||
                        errorMsg.contains("closed", ignoreCase = true)
                    
                    if (isNormalClose) {
                        trySend(ChatResponse.Done())
                        close()
                        return
                    }
                    
                    val responseBody = try { response?.body?.string() } catch (e: Exception) { null }
                    android.util.Log.e("OpenAiProvider", "Request FAILED - code: ${response?.code}, t=${t?.message}")
                    val message = responseBody ?: t?.message ?: response?.message ?: "Unknown error"
                    trySend(ChatResponse.Error(message, retryable = true))
                    close()
                }
                
                override fun onClosed(eventSource: EventSource) {
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
                val response = httpClient.newCall(httpRequest).execute()
                val body = response.body?.string()
                
                if (!response.isSuccessful) {
                    trySend(ChatResponse.Error("API error: ${response.code} - $body"))
                    close()
                    return@callbackFlow
                }
                
                val openaiResponse = moshi.adapter(OpenAiResponse::class.java)
                    .fromJson(body ?: "")
                
                openaiResponse?.choices?.firstOrNull()?.let { choice ->
                    choice.message.content?.let { content ->
                        trySend(ChatResponse.TextDelta(content))
                    }
                    
                    choice.message.toolCalls?.forEach { toolCall ->
                        trySend(ChatResponse.ToolCall(
                            id = toolCall.id,
                            name = toolCall.function.name,
                            arguments = parseJsonArgs(toolCall.function.arguments)
                        ))
                    }
                }
                
                trySend(ChatResponse.Done(
                    usage = openaiResponse?.usage?.let {
                        TokenUsage(it.promptTokens, it.completionTokens)
                    }
                ))
                
            } catch (e: Exception) {
                trySend(ChatResponse.Error("Request failed: ${e.message}"))
            }
            
            close()
        }
    }.flowOn(Dispatchers.IO)
    
    private fun buildCodexResponsesRequest(request: ChatRequest, promptCacheKey: String): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("store", false)
            .put("stream", true)
            .put("instructions", request.systemPrompt.orEmpty())
            .put("input", JSONArray())
            .put("text", JSONObject().put("verbosity", "low"))
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("prompt_cache_key", promptCacheKey)
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", true)

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
            if (msg.role != MessageRole.TOOL) flushBridgeImages()
            when (msg.role) {
                MessageRole.USER -> input.put(codexMessage("user", msg.content.orEmpty(), "input_text"))
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
            val tools = JSONArray()
            request.tools.forEach { tool ->
                tools.put(JSONObject()
                    .put("type", "function")
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", JSONObject()
                        .put("type", tool.parameters.type)
                        .put("properties", JSONObject(tool.parameters.properties.mapValues { (_, prop) ->
                            mutableMapOf<String, Any?>(
                                "type" to prop.type,
                                "description" to prop.description
                            ).apply {
                                prop.enum?.let { put("enum", JSONArray(it)) }
                                prop.items?.let { put("items", JSONObject().put("type", it.type)) }
                            }
                        }))
                        .put("required", JSONArray(tool.parameters.required))
                    )
                    .put("strict", JSONObject.NULL)
                )
            }
            body.put("tools", tools)
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
            state.completed = true
            onResponse(ChatResponse.Done())
            onComplete()
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
            "response.output_item.done" -> json.optJSONObject("item")?.let { emitCodexOutputItem(it, state, onResponse) }
            "response.completed", "response.done", "response.incomplete" -> {
                json.optJSONObject("response")?.let { response ->
                    if (!state.emittedText) emitCodexResponseText(response, onResponse)
                    emitCodexResponseToolCalls(response, state, onResponse)
                    onResponse(ChatResponse.Done(usage = parseCodexUsage(response), finishReason = response.optString("status", "").ifBlank { null }))
                } ?: onResponse(ChatResponse.Done())
                state.completed = true
                onComplete()
            }
            "error", "response.failed" -> {
                val error = json.optJSONObject("error") ?: json.optJSONObject("response")?.optJSONObject("error")
                state.completed = true
                onResponse(ChatResponse.Error(friendlyCodexError(null, error?.toString() ?: json.toString(), null, error?.optString("message")), retryable = true))
                onComplete()
            }
        }
    }

    private fun emitCodexOutputItem(item: JSONObject, state: CodexStreamState, onResponse: (ChatResponse) -> Unit) {
        if (item.optString("type") != "function_call") return
        val callId = item.optString("call_id", item.optString("id", ""))
        if (callId.isBlank() || !state.emittedToolCalls.add(callId)) return
        val args = item.optString("arguments", state.argumentDeltas[callId]?.toString().orEmpty())
        onResponse(ChatResponse.ToolCall(
            id = callId,
            name = item.optString("name", ""),
            arguments = parseJsonArgs(args)
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

    private fun emitCodexResponseToolCalls(response: JSONObject, state: CodexStreamState, onResponse: (ChatResponse) -> Unit) {
        val output = response.optJSONArray("output") ?: return
        for (i in 0 until output.length()) {
            output.optJSONObject(i)?.let { emitCodexOutputItem(it, state, onResponse) }
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
                    messages.add(OpenAiMessage(
                        role = "user",
                        content = msg.content
                    ))
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
                    parameters = OpenAiParameters(
                        type = "object",
                        properties = tool.parameters.properties.mapValues { (_, prop) ->
                            OpenAiPropertyDef(
                                type = prop.type,
                                description = prop.description,
                                enum = prop.enum,
                                items = prop.items?.let { OpenAiItemsDef(it.type) }
                            )
                        },
                        required = tool.parameters.required
                    )
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
            streamOptions = if (request.stream) OpenAiStreamOptions(includeUsage = true) else null
        )
    }
    
    // FIX 4.1: Replaced with shared extension moshi.parseJsonArgs() from AiProvider.kt
    private fun parseJsonArgs(json: String): Map<String, Any?> = moshi.parseJsonArgs(json)

    private fun openAiVisionContent(text: String, attachment: BridgeImageAttachment): String = JSONArray()
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
        val root = JSONObject()
        root.put("model", req.model)
        root.put("max_tokens", req.maxTokens)
        root.put("temperature", req.temperature.toDouble())
        root.put("stream", req.stream)
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
                msg.content != null && msg.content.trimStart().startsWith("[") -> {
                    // Raw JSON array — emit as-is so OpenAI receives a content-parts array
                    msgObj.put("content", org.json.JSONArray(msg.content))
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

        // Tools
        if (req.tools != null) {
            val toolsArr = JSONArray()
            for (tool in req.tools) {
                val propsObj = JSONObject()
                for ((k, prop) in tool.function.parameters.properties) {
                    val propObj = JSONObject()
                        .put("type", prop.type)
                        .put("description", prop.description)
                    if (prop.enum != null) propObj.put("enum", JSONArray(prop.enum))
                    if (prop.items != null) propObj.put("items", JSONObject().put("type", prop.items.type))
                    propsObj.put(k, propObj)
                }
                toolsArr.put(JSONObject()
                    .put("type", tool.type)
                    .put("function", JSONObject()
                        .put("name", tool.function.name)
                        .put("description", tool.function.description)
                        .put("parameters", JSONObject()
                            .put("type", tool.function.parameters.type)
                            .put("properties", propsObj)
                            .put("required", JSONArray(tool.function.parameters.required))
                        )
                    )
                )
            }
            root.put("tools", toolsArr)
        }

        return root.toString()
    }
    
    private class OpenAiToolCallBuilder(
        var id: String = "",
        var name: String = ""
    ) {
        val argumentsBuilder = StringBuilder()
        
        fun isComplete() = id.isNotEmpty() && name.isNotEmpty()
    }
}

// ============================================================================
// OPENAI API DATA CLASSES
// ============================================================================

@JsonClass(generateAdapter = true)
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiToolDef>? = null,
    @Json(name = "max_tokens") val maxTokens: Int = 8192,
    val temperature: Float = 0.7f,
    val stream: Boolean = false,
    @Json(name = "stream_options") val streamOptions: OpenAiStreamOptions? = null
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
    val parameters: OpenAiParameters
)

@JsonClass(generateAdapter = true)
data class OpenAiParameters(
    val type: String = "object",
    val properties: Map<String, OpenAiPropertyDef>,
    val required: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenAiPropertyDef(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: OpenAiItemsDef? = null  // For array types
)

@JsonClass(generateAdapter = true)
data class OpenAiItemsDef(
    val type: String = "string"
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
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<OpenAiStreamChoice>,
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
    @Json(name = "tool_calls") val toolCalls: List<OpenAiDeltaToolCall>? = null
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
