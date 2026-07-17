package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.*
import com.amaya.intelligence.data.remote.mcp.McpClientManager
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import com.amaya.intelligence.tools.toAiToolDefinition
import com.amaya.intelligence.util.debugLog
import com.amaya.intelligence.util.errorLog

import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.memory.MemoryClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentRuntimeTarget {
    LOCAL,
    WINDOWS_BRIDGE
}

/**
 * Repository for AI interactions.
 *
 * Coordinates between:
 * - Multiple AI providers (Anthropic, OpenAI, Gemini)
 * - Tool execution engine
 * - Project context (file index)
 *
 * Implements the agentic loop:
 * 1. Send user message + context to AI
 * 2. AI responds with text and/or tool calls
 * 3. Execute tools and send results back
 * 4. Repeat until AI is done
 */
@Singleton
class AiRepository @Inject constructor(
    private val anthropicProvider: AnthropicProvider,
    private val openAiProvider: OpenAiProvider,
    private val geminiProvider: GeminiProvider,
    private val settingsManager: AiSettingsManager,
    private val toolExecutor: ToolExecutor,
    private val mcpToolExecutor: com.amaya.intelligence.data.remote.mcp.McpToolExecutor,
    private val windowsBridgeToolProvider: com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider,
    private val fileIndexRepository: FileIndexRepository,
    private val personaRepository: PersonaRepository,
    private val memoryRepository: MemoryRepository,
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val selfImprovementPipeline: SelfImprovementPipeline,
    private val contextManager: ContextManager,
    private val memoryClassifier: MemoryClassifier,
    private val mcpClientManager: McpClientManager,
    // FIX 5.11: Inject application-scoped coroutine scope — no more manual SupervisorJob leak
    @ApplicationScope private val repoScope: CoroutineScope
) {
    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192
        const val MAX_TOOL_ITERATIONS = 10
    }

    // FIX 5.11: Removed manual repoJob/repoScope and close() — lifecycle managed by Hilt ApplicationScope

    init {
        // Watch for MCP config changes and refresh tools automatically
        repoScope.launch {
            var lastMcpJson = ""
            settingsManager.settingsFlow.collect { settings ->
                if (settings.mcpConfigJson != lastMcpJson) {
                    lastMcpJson = settings.mcpConfigJson
                    debugLog("AiRepository") { "MCP config changed, refreshing tools..." }
                    try {
                        val tools = mcpClientManager.refreshTools()
                        debugLog("AiRepository") { "MCP tools refreshed: ${tools.size} tools" }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        errorLog("AiRepository", "Failed to refresh MCP tools", e)
                    }
                }
            }
        }
    }


    private fun resolveProvider(connection: ProviderConnection): AiProvider =
        when (AmayaProviderRegistry.require(connection.providerId).adapter) {
            ProviderAdapter.ANTHROPIC -> anthropicProvider
            ProviderAdapter.GEMINI -> geminiProvider
            ProviderAdapter.OPENAI_RESPONSES, ProviderAdapter.OPENAI_COMPATIBLE, ProviderAdapter.CODEX -> openAiProvider
        }

    /**
     * Send a message and receive streaming responses.
     *
     * This handles the full agentic loop:
     * - Sends message to AI with project context and tools
     * - Executes tool calls as needed
     * - Returns final response
     *
     * @param message User's message
     * @param conversationHistory Previous messages in conversation
     * @param projectId Active project for context
     * @param onConfirmation Callback for tool confirmation
     * @return Flow of agent events (text, tool calls, results)
     */
    // FIX: Use channelFlow instead of flow to support concurrent emissions from subagent
    // async{} coroutines. flow{} is NOT thread-safe — concurrent emit() from different
    // coroutines (e.g. SubagentUpdate events from parallel async{}) causes:
    // "Flow invariant is violated: Emission from another coroutine is detected"
    // → IllegalStateException → FATAL EXCEPTION → app force close.
    // channelFlow uses a Channel internally which IS thread-safe for concurrent senders.
    fun chat(
        message: String,
        userImages: List<ChatImage> = emptyList(),
        conversationHistory: List<ChatMessage> = emptyList(),
        projectId: Long? = null,
        workspacePath: String? = null,
        conversationId: Long? = null,
        connectionId: String? = null,
        selectedModel: String? = null,
        effort: ThinkingEffort? = null,
        runtimeTarget: AgentRuntimeTarget = AgentRuntimeTarget.LOCAL,
        onConfirmation: suspend (ConfirmationRequest) -> Boolean = { false }
    ): Flow<AgentEvent> = channelFlow {

        val settings = settingsManager.getSettings()

        val activeSelection = settings.activeSelection
        val resolvedConnectionId = connectionId ?: activeSelection?.connectionId
        val connection = settings.connections.firstOrNull { it.id == resolvedConnectionId }

        if (connection == null) {
            send(AgentEvent.Error("No model selected. Open Settings → Manage Models and select a model.", retryable = false))
            return@channelFlow
        }

        val model = selectedModel?.takeIf { it.isNotBlank() }
            ?: activeSelection?.takeIf { it.connectionId == connection.id }?.modelId
            ?: ""
        if (model.isBlank() || connection.visibleModels.none { it.id == model }) {
            send(AgentEvent.Error("The selected model is unavailable. Open Settings → Manage Models and select another model.", retryable = false))
            return@channelFlow
        }
        val provider = resolveProvider(connection)
        val modelConfig = connection.visibleModels.first { it.id == model }
        val maxOutputTokens = modelConfig.maxOutputTokens?.coerceIn(256, 32_768) ?: DEFAULT_MAX_OUTPUT_TOKENS
        val contextWindowTokens = modelConfig.contextWindowTokens?.coerceAtLeast(maxOutputTokens + 2_048) ?: 32_768
        if (userImages.isNotEmpty() && !modelConfig.supportsImages) {
            send(AgentEvent.Error("The selected model does not support image input.", retryable = false))
            return@channelFlow
        }
        debugLog("AiRepository") { "chat() resolved connection=${connection.id}, model=$model" }

        val sessionId = conversationId?.toString() ?: "session_${UUID.randomUUID()}"
        val completedUserMessages = mutableListOf(message)
        val completedAssistantMessages = mutableListOf<String>()
        val completedToolCalls = mutableListOf<String>()
        val completedToolResults = mutableListOf<String>()
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            runCatching {
                sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "user", content = message))
            }.onFailure { errorLog("AiRepository", "Failed to save user session message", it) }
        }

        // Build final prompt with runtime-specific context policy.
        // Windows Bridge deliberately excludes persona, memory, skills, local tool rules,
        // workspace hints, browser rules, and self-improvement instructions from the
        // system instruction. The model receives only the bridge-safe operating prompt
        // plus recent conversation history and bridge tool schemas.
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            migrateLegacyPersonaFactsIfNeeded()
        }
        val tools = if (modelConfig.supportsTools) buildToolDefinitions(runtimeTarget) else emptyList()
        if (modelConfig.supportsTools && tools.isEmpty()) {
            send(AgentEvent.Error("No tools are available for this request. Select a workspace or ask for a memory, web, browser, reminder, or todo action.", retryable = false))
            return@channelFlow
        }
        val toolSchemaTokens = estimateToolSchemaTokens(tools)
        val contextRequest = ContextBuildRequest(
            userMessage = message,
            conversationHistory = conversationHistory,
            workspacePath = workspacePath,
            conversationId = conversationId,
            maxOutputTokens = maxOutputTokens,
            contextWindowTokens = contextWindowTokens,
            toolSchemaTokens = toolSchemaTokens,
            userImages = userImages
        )
        val managedContext = if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            contextManager.buildWindowsBridgeContext(contextRequest)
        } else {
            contextManager.buildContext(contextRequest)
        }
        val systemPrompt = managedContext.systemPrompt
        val allowedToolNames = tools.map { it.name }.toSet()

        // Start conversation loop
        var messages = managedContext.messages

        var continueLoop = true
        var iterations = 0
        val maxIterations = MAX_TOOL_ITERATIONS
        var browserTaskStarted = false
        var lastBrowserErrorSignature: String? = null
        var repeatedBrowserErrors = 0
        var terminalError = false
        val seenToolCallIds = mutableSetOf<String>()

        while (continueLoop && iterations < maxIterations) {
            iterations++

            // Emit NewIteration for subsequent iterations (after tool results)
            if (iterations > 1) {
                send(AgentEvent.NewIteration)
            }

            val iterationInputBudget = contextWindowTokens - maxOutputTokens - toolSchemaTokens - 1_024
            if (iterationInputBudget <= 0) {
                send(AgentEvent.Error("Selected model context window is too small for this request", retryable = false))
                terminalError = true
                break
            }
            val fittedMessages = fitMessagesToBudget(messages, iterationInputBudget)
            if (messages.isNotEmpty() && fittedMessages.isEmpty()) {
                send(AgentEvent.Error("The latest message or tool result exceeds the selected model context window", retryable = false))
                terminalError = true
                break
            }
            messages = fittedMessages
            val request = ChatRequest(
                model        = model,
                messages     = messages,
                systemPrompt = systemPrompt,
                tools        = tools,
                maxTokens    = maxOutputTokens,
                stream       = true,
                connectionId = connection.id,
                sessionId    = sessionId,
                providerId   = connection.providerId,
                effort       = effort
            )

            var textBuffer = StringBuilder()
            val toolCalls = mutableListOf<ToolCallMessage>()
            val responseItems = mutableListOf<String>()
            var hasToolCalls = false
            var providerTerminal = false

            provider.chat(request).collect { response ->
                if (providerTerminal) {
                    send(AgentEvent.Error("Provider emitted an event after its terminal event", retryable = false))
                    terminalError = true
                    continueLoop = false
                    return@collect
                }
                when (response) {
                    is ChatResponse.TextDelta -> {
                        textBuffer.append(response.text)
                        send(AgentEvent.TextDelta(response.text))
                    }

                    is ChatResponse.ThinkingDelta -> {
                        send(AgentEvent.ThinkingDelta(response.text))
                    }

                    is ChatResponse.ToolCall -> {
                        if (response.id.isBlank() || response.name !in allowedToolNames || !seenToolCallIds.add(response.id)) {
                            send(AgentEvent.Error("Invalid, duplicate, or unadvertised tool call: ${response.name}", retryable = false))
                            terminalError = true
                            continueLoop = false
                            return@collect
                        }
                        val validatedArguments = validateToolArguments(response.name, response.arguments, tools)
                            .getOrElse { error ->
                                send(AgentEvent.Error("Invalid arguments for ${response.name}: ${error.message}", retryable = false))
                                terminalError = true
                                continueLoop = false
                                return@collect
                            }
                        hasToolCalls = true
                        send(AgentEvent.ToolCallStart(response.id, response.name, validatedArguments, response.metadata))

                        toolCalls.add(ToolCallMessage(
                            id = response.id,
                            name = response.name,
                            arguments = validatedArguments,
                            metadata = response.metadata
                        ))
                    }

                    is ChatResponse.ResponseItem -> {
                        responseItems.add(response.json)
                        send(AgentEvent.ResponseItem(response.json))
                    }

                    is ChatResponse.Done -> {
                        providerTerminal = true
                        response.usage?.let { usage ->
                            send(AgentEvent.Usage(usage.inputTokens, usage.outputTokens))
                        }
                    }

                    is ChatResponse.Incomplete -> {
                        providerTerminal = true
                        send(AgentEvent.Incomplete(response.reason, response.retryable))
                        terminalError = true
                        continueLoop = false
                    }

                    is ChatResponse.Error -> {
                        providerTerminal = true
                        send(AgentEvent.Error(response.message, response.retryable))
                        terminalError = true
                        continueLoop = false
                    }
                }
            }

            if (terminalError) break
            if (!providerTerminal) {
                send(AgentEvent.Incomplete("Provider stream ended without a terminal event", retryable = true))
                terminalError = true
                break
            }

            if (textBuffer.isNotBlank()) {
                val assistantText = textBuffer.toString()
                completedAssistantMessages.add(assistantText)
                if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                    runCatching {
                        sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "assistant", content = assistantText))
                    }.onFailure { errorLog("AiRepository", "Failed to save assistant session message", it) }
                }
            }

            if (!hasToolCalls) {
                // No tool calls, we're done
                continueLoop = false
            } else {
                // Add assistant message with tool calls
                messages = messages + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = textBuffer.toString().takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls,
                    responseItems = responseItems
                )

                // Execute each tool call
                for (toolCall in toolCalls) {
                    val channel = this
                    val executionArguments = if (toolCall.name == "browser") {
                        val shouldResetBrowserTask = !browserTaskStarted
                        browserTaskStarted = true
                        if (shouldResetBrowserTask && toolCall.arguments["reset_task"] == null) {
                            toolCall.arguments + ("reset_task" to true)
                        } else toolCall.arguments
                    } else {
                        toolCall.arguments
                    }

                    completedToolCalls.add("${toolCall.name}: ${toolCall.arguments}")
                    val result = if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE && toolCall.name !in allowedToolNames) {
                        ToolResult.Error(
                            message = "Tool '${toolCall.name}' is not available in Windows Bridge chat.",
                            errorType = com.amaya.intelligence.tools.ErrorType.PERMISSION_ERROR,
                            recoverable = false
                        )
                    } else {
                        mcpToolExecutor.execute(
                            toolName = toolCall.name,
                            arguments = executionArguments,
                            workspacePath = workspacePath,
                            toolCallId = toolCall.id,
                            // FIX: Use channel.send() — channelFlow's ProducerScope is thread-safe,
                            // unlike flow{}'s emit() which panics on concurrent coroutine access.
                            onEvent = { event -> if (event is AgentEvent) channel.send(event) },
                            onConfirmationRequired = onConfirmation,
                            providerConnection = connection,
                            selectedModelId = model
                        )
                    }

                    val rawResultContent = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                        is ToolResult.RequiresConfirmation -> "Error: Approval could not be completed: ${result.reason}"
                    }
                    val resultContent = limitToolResult(rawResultContent)

                    completedToolResults.add("${toolCall.name}: $resultContent")
                    if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                        runCatching {
                            sessionMemoryRepository.saveToolCall(
                                SessionToolCall(
                                    sessionId = sessionId,
                                    toolCallId = toolCall.id,
                                    toolName = toolCall.name,
                                    argumentsJson = JSONObject(toolCall.arguments).toString(),
                                    resultJson = resultContent
                                )
                            )
                        }.onFailure { errorLog("AiRepository", "Failed to save session tool call", it) }
                    }

                    send(AgentEvent.ToolCallResult(
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        result = resultContent,
                        isError = result !is ToolResult.Success
                    ))

                    // Add tool result to conversation
                    // Store both ID (for OpenAI) and name (for Gemini) in metadata
                    val resultMetadata = toolCall.metadata.toMutableMap()
                    resultMetadata["toolName"] = toolCall.name  // Gemini needs the function name
                    // Propagate image metadata from ToolResult.Success so providers can
                    // attach the screenshot as a vision content block instead of embedding
                    // the raw base64 string inside the tool-result text.
                    if (result is ToolResult.Success) {
                        (result.metadata["bridge_image_base64"] as? String)?.let { resultMetadata["bridge_image_base64"] = it }
                        (result.metadata["bridge_image_format"] as? String)?.let { resultMetadata["bridge_image_format"] = it }
                    }

                    messages = messages + ChatMessage(
                        role = MessageRole.TOOL,
                        toolResult = ToolResultMessage(
                            toolCallId = toolCall.id, // OpenAI requires original tool_call_id
                            content = resultContent,
                            isError = result !is ToolResult.Success,
                            metadata = resultMetadata
                        )
                    )

                    if (toolCall.name == "browser") {
                        val signature = browserErrorSignature(resultContent)
                        if (signature != null) {
                            repeatedBrowserErrors = if (signature == lastBrowserErrorSignature) repeatedBrowserErrors + 1 else 1
                            lastBrowserErrorSignature = signature
                            if (repeatedBrowserErrors >= 2) {
                                send(AgentEvent.Error("Browser repeated the same failure ($signature). Stopping to avoid a tool loop; inspect agent.interactive_elements or ask the user for the target.", retryable = true))
                                continueLoop = false
                                break
                            }
                        } else {
                            lastBrowserErrorSignature = null
                            repeatedBrowserErrors = 0
                        }
                    }
                }
            }
        }

        if (continueLoop && iterations >= maxIterations) {
            send(AgentEvent.Error("Maximum iterations reached", retryable = false))
            terminalError = true
        }

        val reflectionContext = CompletedInteractionContext(
            sessionId = sessionId,
            userMessages = completedUserMessages.toList(),
            assistantMessages = completedAssistantMessages.toList(),
            toolCalls = completedToolCalls.toList(),
            toolResults = completedToolResults.toList(),
            timestamp = System.currentTimeMillis()
        )

        if (terminalError) return@channelFlow

        send(AgentEvent.Done)

        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            repoScope.launch {
                runCatching {
                    selfImprovementPipeline.analyzeAndImprove(reflectionContext)
                }.onFailure { errorLog("AiRepository", "Post-chat reflection failed", it) }
            }
        }
    }

    private fun validateToolArguments(
        name: String,
        arguments: Map<String, Any?>,
        tools: List<AiToolDefinition>
    ): Result<Map<String, Any?>> = runCatching {
        val definition = tools.firstOrNull { it.name == name } ?: error("Tool was not advertised")
        definition.rawParametersJson?.let { schema ->
            validateJsonSchema(JSONObject(schema), arguments, "arguments")
            return@runCatching arguments
        }
        val missing = definition.parameters.required.filter { it !in arguments || arguments[it] == null }
        require(missing.isEmpty()) { "Missing required properties: ${missing.joinToString()}" }
        if (!definition.parameters.additionalProperties) {
            val unknown = arguments.keys - definition.parameters.properties.keys
            require(unknown.isEmpty()) { "Unknown properties: ${unknown.joinToString()}" }
        }
        definition.parameters.properties.forEach { (key, property) ->
            val value = arguments[key] ?: return@forEach
            val validType = when (property.type.lowercase()) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "array" -> value is List<*>
                "object" -> value is Map<*, *>
                else -> true
            }
            require(validType) { "$key must be ${property.type}" }
            property.enum?.let { allowed -> require(value.toString() in allowed) { "$key is not an allowed value" } }
        }
        arguments
    }

    private fun validateJsonSchema(schema: JSONObject, value: Any?, path: String) {
        val types = buildList {
            schema.optString("type").takeIf { it.isNotBlank() }?.let(::add)
            schema.optJSONArray("type")?.let { array ->
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }
        if (value == null) {
            require("null" in types || schema.optBoolean("nullable")) { "$path cannot be null" }
            return
        }
        val matches = types.isEmpty() || types.any { type ->
            when (type.lowercase()) {
                "object" -> value is Map<*, *>
                "array" -> value is List<*>
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "null" -> false
                else -> true
            }
        }
        require(matches) { "$path must be ${types.joinToString(" or ")}" }
        schema.optJSONArray("enum")?.let { allowed ->
            require((0 until allowed.length()).any { allowed.opt(it) == value }) { "$path is not an allowed value" }
        }
        when (value) {
            is Map<*, *> -> {
                val properties = schema.optJSONObject("properties") ?: JSONObject()
                val required = schema.optJSONArray("required")
                if (required != null) for (index in 0 until required.length()) {
                    val key = required.optString(index)
                    require(value.containsKey(key) && value[key] != null) { "Missing required property: $path.$key" }
                }
                if (schema.has("additionalProperties") && schema.opt("additionalProperties") == false) {
                    require(value.keys.all { properties.has(it.toString()) }) { "Unknown properties in $path" }
                }
                value.forEach { (key, child) ->
                    properties.optJSONObject(key.toString())?.let { validateJsonSchema(it, child, "$path.$key") }
                }
            }
            is List<*> -> schema.optJSONObject("items")?.let { itemSchema ->
                value.forEachIndexed { index, child -> validateJsonSchema(itemSchema, child, "$path[$index]") }
            }
        }
    }

    private fun limitToolResult(content: String, maxChars: Int = 24_000): String =
        if (content.length <= maxChars) content
        else content.take(maxChars) + "\n… [tool output truncated; ${content.length - maxChars} chars omitted]"

    private fun estimateToolSchemaTokens(tools: List<AiToolDefinition>): Int =
        tools.sumOf { tool ->
            val schema = tool.rawParametersJson ?: JSONObject()
                .put("type", tool.parameters.type)
                .put("properties", JSONObject(tool.parameters.properties))
                .put("required", org.json.JSONArray(tool.parameters.required))
                .toString()
            (tool.name.length + tool.description.length + schema.length + 3) / 4
        }

    private fun fitMessagesToBudget(messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        if (maxTokens <= 0) return emptyList()
        fun cost(message: ChatMessage): Int = (
            message.content.orEmpty().length +
                message.toolResult?.content.orEmpty().length +
                message.toolCalls.orEmpty().sumOf { it.arguments.toString().length } +
                message.responseItems.sumOf(String::length)
            ) / 4 + 8
        var used = 0
        var cut = messages.size
        while (cut > 0) {
            val spanStart = if (messages[cut - 1].role == MessageRole.TOOL) {
                (cut - 2 downTo 0).firstOrNull { messages[it].role == MessageRole.ASSISTANT } ?: cut - 1
            } else cut - 1
            val spanCost = messages.subList(spanStart, cut).sumOf(::cost)
            if (used + spanCost > maxTokens) {
                if (cut == messages.size) {
                    // The newest atomic span cannot fit; fail instead of sending an oversized request.
                    return emptyList()
                }
                break
            }
            used += spanCost
            cut = spanStart
        }
        return messages.drop(cut)
    }

    private fun browserErrorSignature(resultContent: String): String? {
        val root = runCatching { JSONObject(resultContent) }.getOrNull() ?: return null
        val status = root.optString("status")
        if (status != "error" && status != "cancelled" && status != "timeout") return null
        val error = root.optJSONObject("agent")?.optJSONObject("error") ?: root.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: status
        val message = error?.optString("message")?.takeIf { it.isNotBlank() } ?: root.optJSONObject("agent")?.optString("latest_summary").orEmpty()
        return "$code:${message.take(120)}"
    }


    private suspend fun migrateLegacyPersonaFactsIfNeeded() {
        val facts = personaRepository.extractLegacyMemoryFacts()
        if (facts.isEmpty()) return
        facts.forEach { fact ->
            val proposal = memoryClassifier.classify(
                content = fact,
                requestedType = MemoryType.USER_PROFILE,
                reason = "Migrated legacy persona user fact into Memory.",
                confidence = 0.9,
                importance = 0.7
            )
            memoryRepository.applyProposal(proposal)
        }
        personaRepository.clearLegacyMemoryFacts()
    }


    /**
     * Build tool definitions for AI.
     * Uses cached MCP tools — refresh happens automatically via settingsFlow watcher in init.
     */
    private fun buildToolDefinitions(runtimeTarget: AgentRuntimeTarget = AgentRuntimeTarget.LOCAL): List<AiToolDefinition> {
        val bridgeTools = windowsBridgeToolProvider.getAvailableBridgeTools()
            .map { it.toAiToolDefinition(truncateDesc = true) }
        if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            if (bridgeTools.isNotEmpty()) {
                debugLog("AiRepository") { "Building Windows Bridge tool defs: bridge=${bridgeTools.size}" }
            }
            return bridgeTools
        }

        // FIX 4.3: Use shared toAiToolDefinition() extension (ToolExecutor.kt) — removes duplicate mapping
        val localTools = toolExecutor.getToolDefinitions()
            .map { it.toAiToolDefinition(truncateDesc = true) }
        // MCP tools come from external servers — truncate their descriptions too
        val mcpTools = mcpClientManager.getCachedToolDefinitions().map { tool ->
            tool.copy(
                description = tool.description.let { if (it.length > 1023) it.take(1023) + "…" else it },
                parameters = tool.parameters.copy(
                    properties = tool.parameters.properties.mapValues { (_, prop) ->
                        prop.copy(description = prop.description.let { if (it.length > 1023) it.take(1023) + "…" else it })
                    }
                )
            )
        }
        debugLog("AiRepository") { "Building tool defs: local=${localTools.size}, mcp=${mcpTools.size}" }
        return localTools + mcpTools
    }

    /**
     * Generate a short conversation title (max 3 sentences) from the user's first message.
     * Uses the same AI provider/model as the active conversation.
     */
    suspend fun generateTitle(
        userMessage: String,
        providerConnection: ProviderConnection,
        selectedModel: String?
    ): String {
        return try {
            val provider = resolveProvider(providerConnection)
            val model = when {
                !selectedModel.isNullOrBlank() -> selectedModel
                else -> return "New Chat"
            }

            val request = ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = """Summarize the user's main intent into a short, clear title.
Max 3 sentences, ideally 3-7 words.
Use the same language as the user.
Reply with the title only — no quotes, no explanation, no markdown.

User's first message:
$userMessage"""
                    )
                ),
                systemPrompt = "You are a session title generator. Produce concise, natural titles that capture the user's intent. Keep it short.",
                tools = emptyList(),
                maxTokens = 50,
                stream = false,
                connectionId = providerConnection.id
            )

            val result = StringBuilder()
            provider.chat(request).collect { response ->
                if (response is ChatResponse.TextDelta) {
                    result.append(response.text)
                }
            }
            sanitizeTitle(result.toString())
        } catch (e: Exception) {
            errorLog("AiRepository", "Failed to generate title", e)
            "New Chat"
        }
    }

    private fun sanitizeTitle(raw: String): String {
        return raw
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("^[\\s\\*\\#\\`\\>\\-_]+"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(60)
            ?.ifBlank { "New Chat" }
            ?: "New Chat"
    }

}

/**
 * Events emitted during AI chat.
 */
sealed class AgentEvent {
    data class TextDelta(val text: String) : AgentEvent()
    /** Reasoning/thinking chunk, rendered separately from the answer. */
    data class ThinkingDelta(val text: String) : AgentEvent()
    data class ToolCallStart(
        val toolCallId: String,
        val name: String,
        val arguments: Map<String, Any?>,
        val metadata: Map<String, String> = emptyMap()
    ) : AgentEvent()
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String, val isError: Boolean) : AgentEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent()
    data class ResponseItem(val json: String) : AgentEvent()
    data class Incomplete(val reason: String, val retryable: Boolean) : AgentEvent()
    data class Error(val message: String, val retryable: Boolean) : AgentEvent()
    data object NewIteration : AgentEvent()
    data object Done : AgentEvent()
    // Emitted by InvokeSubagentsTool as each subagent starts/completes
    data class SubagentUpdate(
        val parentToolCallId: String,
        val index: Int,
        val taskName: String,
        val prompt: String,
        val result: String? = null,
        val isComplete: Boolean = false,
        val isError: Boolean = false
    ) : AgentEvent()
}

// FIX 1.4: ProviderInfo fully removed — was only used by deleted getProviders() function.
