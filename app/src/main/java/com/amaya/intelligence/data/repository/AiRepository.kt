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
                    } catch (e: Exception) {
                        errorLog("AiRepository", "Failed to refresh MCP tools", e)
                    }
                }
            }
        }
    }
    
    // FIX 1.4: Removed getProviders() — dead code, no ViewModel/UI calls it (pre-agent era).
    // FIX 1.5/2.1: Removed getActiveProvider() — it read stale activeProvider DataStore field.
    //   Provider is now resolved from AgentConfig.providerType inline in chat().

    /**
     * Resolve the AiProvider from an AgentConfig, falling back to DataStore activeProvider.
     */
    private fun resolveProvider(agentConfig: AgentConfig): AiProvider {
        val type = AmayaProviderRegistry.legacyProviderType(agentConfig.providerId)
            .takeIf { agentConfig.providerId.isNotBlank() }
            ?: runCatching { ProviderType.valueOf(agentConfig.providerType) }
                .getOrElse { ProviderType.OPENAI }
        return when (type) {
            ProviderType.ANTHROPIC -> anthropicProvider
            ProviderType.OPENAI,
            ProviderType.CUSTOM_OPENAI_COMPATIBLE -> openAiProvider
            ProviderType.GEMINI    -> geminiProvider
        }
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
        conversationHistory: List<ChatMessage> = emptyList(),
        projectId: Long? = null,
        workspacePath: String? = null,
        conversationId: Long? = null,
        activeAgentId: String? = null,
        selectedModel: String? = null,
        runtimeTarget: AgentRuntimeTarget = AgentRuntimeTarget.LOCAL,
        onConfirmation: suspend (ConfirmationRequest) -> Boolean = { false }
    ): Flow<AgentEvent> = channelFlow {
        
        val settings = settingsManager.getSettings()

        // Resolve agent config — use activeAgentId from UI (most up-to-date)
        val agentId = activeAgentId ?: settings.activeAgentId
        // Only use enabled agents — if selected agent is disabled, find first enabled one
        val agentConfig = settings.agentConfigs.find { it.id == agentId && it.enabled }
            ?: settings.agentConfigs.firstOrNull { it.enabled }

        // Block chat if no enabled agent exists
        if (agentConfig == null) {
            send(AgentEvent.Error("No AI agent configured. Please add and enable an agent in Settings → Agents.", retryable = false))
            send(AgentEvent.Done)
            return@channelFlow
        }

        // Block chat if model ID is blank
        if (agentConfig.modelId.isBlank() && selectedModel.isNullOrBlank()) {
            send(AgentEvent.Error("No model ID configured for agent \"${agentConfig.name}\". Please edit the agent in Settings → Agents and add a Model ID.", retryable = false))
            send(AgentEvent.Done)
            return@channelFlow
        }

        // FIX 2.1: Resolve provider from agentConfig (guaranteed non-null here)
        val provider = resolveProvider(agentConfig)
        // Priority: selectedModel from UI (always up-to-date) > agentConfig.modelId > activeModel in DataStore
        // Never fall through to agentConfig if selectedModel is explicitly set from UI
        val model = when {
            !selectedModel.isNullOrBlank()        -> selectedModel
            !agentConfig?.modelId.isNullOrBlank() -> agentConfig!!.modelId
            settings.activeModel.isNotBlank()     -> settings.activeModel
            else                                  -> provider.supportedModels.firstOrNull() ?: ""
        }
        debugLog("AiRepository") { "chat() resolved model=$model (from UI: $selectedModel, agent: ${agentConfig?.modelId}, datastore: ${settings.activeModel})" }
        
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
        val contextRequest = ContextBuildRequest(
            userMessage = message,
            conversationHistory = conversationHistory,
            workspacePath = workspacePath,
            conversationId = conversationId,
            maxOutputTokens = agentConfig.maxTokens
        )
        val managedContext = if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            contextManager.buildWindowsBridgeContext(contextRequest)
        } else {
            contextManager.buildContext(contextRequest)
        }
        val systemPrompt = managedContext.systemPrompt
        
        // Build tool definitions. New provider capability override: when tool calling is
        // disabled for the selected agent, keep the model loop text-only.
        val tools = if (agentConfig.toolCalling) buildToolDefinitions(runtimeTarget) else emptyList()
        val allowedToolNames = tools.map { it.name }.toSet()
        
        // Start conversation loop
        var messages = managedContext.messages
        
        var continueLoop = true
        var iterations = 0
        val maxIterations = agentConfig.maxIterations.coerceIn(1, 50) // Prevent infinite loops
        val browserTaskId = "browser_task_turn_${UUID.randomUUID().toString().take(8)}"
        var browserTaskStarted = false
        var lastBrowserErrorSignature: String? = null
        var repeatedBrowserErrors = 0
        
        while (continueLoop && iterations < maxIterations) {
            iterations++
            
            // Emit NewIteration for subsequent iterations (after tool results)
            if (iterations > 1) {
                send(AgentEvent.NewIteration)
            }
            
            val request = ChatRequest(
                model        = model,
                messages     = messages,
                systemPrompt = systemPrompt,
                tools        = tools,
                maxTokens    = agentConfig.maxTokens,
                stream       = true,
                // Pass resolved agentId so providers use the correct API key
                agentId      = agentConfig.id,
                sessionId    = sessionId
            )
            
            var textBuffer = StringBuilder()
            val toolCalls = mutableListOf<ToolCallMessage>()
            var hasToolCalls = false
            
            provider.chat(request).collect { response ->
                when (response) {
                    is ChatResponse.TextDelta -> {
                        textBuffer.append(response.text)
                        send(AgentEvent.TextDelta(response.text))
                    }
                    
                    is ChatResponse.ToolCall -> {
                        hasToolCalls = true
                        send(AgentEvent.ToolCallStart(response.id, response.name, response.arguments))
                        
                        toolCalls.add(ToolCallMessage(
                            id = response.id,
                            name = response.name,
                            arguments = response.arguments,
                            metadata = response.metadata
                        ))
                    }
                    
                    is ChatResponse.Done -> {
                        response.usage?.let { usage ->
                            send(AgentEvent.Usage(usage.inputTokens, usage.outputTokens))
                        }
                    }
                    
                    is ChatResponse.Error -> {
                        send(AgentEvent.Error(response.message, response.retryable))
                        continueLoop = false
                    }
                }
            }
            
            // If no native tool calls received, try to parse tool calls from text response.
            // This handles models that don't support native function calling (e.g. StepFun)
            // and instead emit <tool_call> XML or JSON blocks in their text output.
            if (agentConfig.toolCalling && !hasToolCalls && textBuffer.isNotEmpty()) {
                val parsed = parseToolCallsFromText(textBuffer.toString(), allowedToolNames)
                if (parsed.isNotEmpty()) {
                    hasToolCalls = true
                    // Remove tool call markup from displayed text
                    val cleanText = stripToolCallMarkup(textBuffer.toString())
                    // If clean text differs, we need to signal UI to replace text
                    // For now we just proceed — text already streamed, tool calls will execute
                    parsed.forEach { tc ->
                        send(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
                        toolCalls.add(tc)
                    }
                }
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
                    toolCalls = toolCalls
                )
                
                // Execute each tool call
                for (toolCall in toolCalls) {
                    val channel = this
                    val executionArguments = if (toolCall.name == "browser") {
                        val shouldResetBrowserTask = !browserTaskStarted
                        browserTaskStarted = true
                        buildMap<String, Any?> {
                            putAll(toolCall.arguments)
                            put("parent_call_id", browserTaskId)
                            if (shouldResetBrowserTask && toolCall.arguments["reset_task"] == null) {
                                put("reset_task", true)
                            }
                        }
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
                            // Pass resolved agentConfig so SubagentRunner uses the SAME provider/model
                            // as the main chat loop — not a stale DataStore snapshot.
                            agentConfig = agentConfig
                        )
                    }
                    
                    val resultContent = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                        is ToolResult.RequiresConfirmation -> "Confirmation required: ${result.reason}"
                    }
                    
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
                        isError = result is ToolResult.Error
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
                            isError = result is ToolResult.Error,
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
        
        if (iterations >= maxIterations) {
            send(AgentEvent.Error("Maximum iterations reached", retryable = false))
        }

        val reflectionContext = CompletedInteractionContext(
            sessionId = sessionId,
            userMessages = completedUserMessages.toList(),
            assistantMessages = completedAssistantMessages.toList(),
            toolCalls = completedToolCalls.toList(),
            toolResults = completedToolResults.toList(),
            timestamp = System.currentTimeMillis()
        )

        send(AgentEvent.Done)

        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            repoScope.launch {
                runCatching {
                    selfImprovementPipeline.analyzeAndImprove(reflectionContext)
                }.onFailure { errorLog("AiRepository", "Post-chat reflection failed", it) }
            }
        }
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
     * Parse tool calls from plain text response.
     * Handles models that don't support native function calling.
     *
     * Supported formats:
     * 1. XML: <tool_call name="write_file">{"path": "...", "content": "..."}</tool_call>
     * 2. XML: <tool_call>{"name": "write_file", "arguments": {...}}</tool_call>
     * 3. JSON block: {"tool": "write_file", "arguments": {...}}
     *
     * Only parses if the tool name exists in the known tool registry.
     * This prevents false positives from AI examples or explanations.
     */
    private fun parseToolCallsFromText(text: String, knownTools: Set<String>): List<ToolCallMessage> {
        val results = mutableListOf<ToolCallMessage>()
        var callIndex = 0
        
        // Format 1: <tool_call name="tool_name">JSON</tool_call>
        val xmlWithAttr = Regex(
            """<tool_call\s+name="([^"]+)"\s*>(.*?)</tool_call>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        xmlWithAttr.findAll(text).forEach { match ->
            val name = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (name in knownTools) {
                val args = parseJsonToMap(body)
                results.add(ToolCallMessage(
                    id = "text_tc_${callIndex++}",
                    name = name,
                    arguments = args
                ))
            }
        }
        
        // Format 2: <tool_call>{"name": "tool_name", "arguments": {...}}</tool_call>
        // Only if format 1 found nothing to avoid double-parsing
        if (results.isEmpty()) {
            val xmlNoAttr = Regex(
                """<tool_call>(.*?)</tool_call>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            xmlNoAttr.findAll(text).forEach { match ->
                val body = match.groupValues[1].trim()
                val parsed = parseJsonToMap(body)
                val name = (parsed["name"] as? String)?.trim() ?: return@forEach
                if (name in knownTools) {
                    @Suppress("UNCHECKED_CAST")
                    val args = (parsed["arguments"] as? Map<String, Any?>) ?: parsed
                    results.add(ToolCallMessage(
                        id = "text_tc_${callIndex++}",
                        name = name,
                        arguments = args
                    ))
                }
            }
        }
        
        // Format 3: {"tool": "tool_name", "arguments": {...}} as standalone JSON block
        // Only as last resort — very conservative. Use brace-counting to handle nested JSON.
        if (results.isEmpty()) {
            val toolKeyRegex = Regex(
                """\{\s*"tool"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*\{""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            toolKeyRegex.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                if (name !in knownTools) return@forEach
                // match.range.last points to the opening '{' of arguments value
                val argsStart = match.range.last
                var depth = 1
                var idx = argsStart + 1
                while (idx < text.length && depth > 0) {
                    when (text[idx]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    idx++
                }
                if (depth == 0) {
                    val argsJson = text.substring(argsStart, idx)
                    val args = parseJsonToMap(argsJson)
                    results.add(ToolCallMessage(
                        id = "text_tc_${callIndex++}",
                        name = name,
                        arguments = args
                    ))
                }
            }
        }
        
        return results
    }
    
    /**
     * Strip tool call markup from text so it doesn't render in the chat UI.
     */
    private fun stripToolCallMarkup(text: String): String {
        return text
            .replace(Regex("""<tool_call[^>]*>.*?</tool_call>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""\{\s*"tool"\s*:\s*"[^"]+"\s*,\s*"arguments"\s*:\s*\{.*?\}\s*\}""", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
    }
    
    /**
     * Safely parse JSON string to Map<String, Any?>.
     * Returns empty map on failure instead of throwing.
     */
    private fun parseJsonToMap(json: String): Map<String, Any?> {
        return try {
            @Suppress("UNCHECKED_CAST")
            com.squareup.moshi.Moshi.Builder().build()
                .adapter(Map::class.java)
                .fromJson(json) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
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
        // Keep LOCAL chat tool schema strictly local/MCP. Windows Bridge tools are
        // advertised only in WINDOWS_BRIDGE mode so the local system prompt never
        // carries a Windows tool-call catalog.
        return localTools + mcpTools
    }

    /**
     * Generate a short conversation title (max 3 sentences) from the user's first message.
     * Uses the same AI provider/model as the active conversation.
     */
    suspend fun generateTitle(
        userMessage: String,
        agentConfig: AgentConfig,
        selectedModel: String?
    ): String {
        return try {
            val provider = resolveProvider(agentConfig)
            val model = when {
                !selectedModel.isNullOrBlank() -> selectedModel
                agentConfig.modelId.isNotBlank() -> agentConfig.modelId
                else -> provider.supportedModels.firstOrNull() ?: return "New Chat"
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
                agentId = agentConfig.id
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
    data class ToolCallStart(val toolCallId: String, val name: String, val arguments: Map<String, Any?>) : AgentEvent()
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String, val isError: Boolean) : AgentEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent()
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
