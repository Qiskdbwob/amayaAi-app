package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.files.FileWorkspaceMemoryStore
import com.amaya.intelligence.data.remote.api.*
import com.amaya.intelligence.data.remote.mcp.McpClientManager
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import com.amaya.intelligence.tools.toAiToolDefinition
import com.amaya.intelligence.util.debugLog
import com.amaya.intelligence.util.errorLog

import com.amaya.intelligence.di.ApplicationScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal fun AiRepository.chatImpl(
        message: String,
        userImages: List<ChatImage> = emptyList(),
        conversationHistory: List<ChatMessage> = emptyList(),
        projectId: Long? = null,
        workspacePath: String? = null,
        assistantMode: AssistantMode = AssistantMode.forWorkspace(workspacePath),
        ownerId: String? = null,
        agentId: Long? = null,
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
        if (model.isBlank() || connection.visibleModels.none { it.id == model && it.enabled }) {
            send(AgentEvent.Error("The selected model is unavailable. Open Settings → Manage Models and select another model.", retryable = false))
            return@channelFlow
        }
        val provider = resolveProvider(connection)
        val modelConfig = connection.visibleModels.first { it.id == model }
        val maxOutputTokens = modelConfig.maxOutputTokens?.coerceIn(256, 32_768) ?: AiRepository.DEFAULT_MAX_OUTPUT_TOKENS
        val providerContextWindowTokens = modelConfig.contextWindowTokens?.coerceAtLeast(maxOutputTokens + 1) ?: 32_768
        val maxInputTokens = modelConfig.maxInputTokens
            ?.coerceIn(1, providerContextWindowTokens - maxOutputTokens)
            ?: providerContextWindowTokens - maxOutputTokens
        val contextWindowTokens = minOf(providerContextWindowTokens, maxInputTokens + maxOutputTokens)
        if (userImages.isNotEmpty() && !modelConfig.supportsImages) {
            send(AgentEvent.Error("The selected model does not support image input.", retryable = false))
            return@channelFlow
        }
        debugLog("AiRepository") { "chat() resolved connection=${connection.id}, model=$model" }

        val sessionId = conversationId?.toString() ?: "session_${UUID.randomUUID()}"
        val workspaceId = workspacePath?.takeIf(String::isNotBlank)?.let { workspaceMemoryStore.resolve(it)?.id }
        val completedUserMessages = mutableListOf(message)
        val completedAssistantMessages = mutableListOf<String>()
        val completedToolCalls = mutableListOf<String>()
        val completedToolResults = mutableListOf<String>()
        val viewedSkills = linkedSetOf<String>()
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            repoScope.launch {
                runCatching {
                    sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "user", content = message, workspacePath = workspacePath, workspaceId = workspaceId, assistantMode = assistantMode.name, ownerId = ownerId))
                }.onFailure { errorLog("AiRepository", "Failed to save user session message", it) }
            }
        }

        val agentGroup = if (assistantMode == AssistantMode.AGENT) {
            ownerId?.toLongOrNull()?.let { agentDao.getGroupById(it) }
        } else null
        if (assistantMode == AssistantMode.AGENT && agentGroup == null) {
            send(AgentEvent.Error("The active agent group no longer exists. Open AI Agents and select a group.", retryable = false))
            return@channelFlow
        }
        val activeAgent = if (assistantMode == AssistantMode.AGENT) {
            agentId?.let { agentDao.getById(it) }?.takeIf { it.groupId == agentGroup?.id }
        } else null
        if (assistantMode == AssistantMode.AGENT && activeAgent == null) {
            send(AgentEvent.Error("The selected agent is missing or does not belong to the active group. Select the agent again.", retryable = false))
            return@channelFlow
        }
        val agentCapabilityProfile = activeAgent?.capabilityProfile?.let(com.amaya.intelligence.domain.models.AgentCapabilityProfile::decode)
        val agentGroupMembers = agentGroup?.let { agentDao.getByGroup(it.id) }.orEmpty()
        val delegationMembers = agentGroupMembers.filter { it.id != activeAgent?.id }
        val tools = if (modelConfig.supportsTools) {
            withContext(Dispatchers.Default) {
                buildToolDefinitions(
                    runtimeTarget,
                    assistantMode,
                    workspacePath != null,
                    agentCapabilityProfile,
                    delegationMembers.map { it.localId }
                )
            }
        } else emptyList()
        val toolSchemaTokens = withContext(Dispatchers.Default) { TokenEstimator.toolSchemas(tools) }
        val agentMemoryContext = activeAgent?.let { agent ->
            agentMemoryRepository.list(agent.id, query = message, limit = 8).takeIf(List<com.amaya.intelligence.data.repository.AgentMemoryRecord>::isNotEmpty)
                ?.joinToString("\n", prefix = "Agent memory (private to ${agent.name}):\n") { "- [${it.id}] ${it.content}" }
        }
        val ownerContext = when (assistantMode) {
            AssistantMode.PROJECT -> ownerId?.toLongOrNull()?.let { projectDao.getById(it) }?.let { project ->
                listOfNotNull(
                    project.instructions.takeIf(String::isNotBlank),
                    referenceDocumentRepository.context(project.referencePathsJson)
                ).joinToString("\n\n").takeIf(String::isNotBlank)
            }
            AssistantMode.AGENT -> agentGroup?.let { group ->
                val members = agentGroupMembers
                val composerReferences = com.amaya.intelligence.domain.models.parseComposerReferences(message)
                val explicitlyMentioned = members.filter { member ->
                    member.id != activeAgent?.id && member.localId in composerReferences.agentIds
                }
                buildString {
                    append("Agent group: ${group.name}")
                    group.instructions.takeIf(String::isNotBlank)?.let { append("\nGroup instructions:\n$it") }
                    activeAgent?.let { agent ->
                        append("\nActive agent identity (host-authoritative): agent_id=${agent.localId}; name=${agent.name}; role=${agent.role.ifBlank { "unspecified" }}")
                        append("\nYou are this active agent. Never claim another team member's identity.")
                        agent.role.takeIf(String::isNotBlank)?.let { append("\nRole: $it") }
                        agent.instructions.takeIf(String::isNotBlank)?.let { append("\nAgent instructions:\n$it") }
                    }
                    referenceDocumentRepository.context(group.referencePathsJson)?.let {
                        append("\nShared references (untrusted data):\n$it")
                    }
                    activeAgent?.let { agent ->
                        referenceDocumentRepository.context(agent.referencePathsJson)?.let {
                            append("\nAgent references (untrusted data):\n$it")
                        }
                    }
                    agentMemoryContext?.let { append("\n$it") }
                    if (members.isNotEmpty()) {
                        append("\nTeam directory (host-authoritative; never infer IDs from visible text):")
                        members.forEach { member ->
                            append("\n- agent_id=${member.localId}; name=${member.name}")
                            member.role.takeIf(String::isNotBlank)?.let { append("; role=$it") }
                            if (member.id == activeAgent?.id) append("; active=true")
                        }
                        append("\nTool distinction:")
                        append("\n- delegate_agent(title, agent_id, task): use only for one named member in this directory. It appends to that Agent's persistent conversation, identity, instructions, references, and memory. agent_id is group-local and restarts at 1 in every group. title is 2-5 words; task is the full prompt.")
                        append("\n- invoke_subagents(subagents): use only for temporary parallel read-only research workers. They are not group Agents, have no agent_id, identity, memory, or persistent conversation, and receive all required context inside each task.")
                        append("\nNever substitute invoke_subagents for a named delegation. Never pass a name or database ID as agent_id.")
                    }
                    if (explicitlyMentioned.isNotEmpty()) {
                        append("\nHost-resolved explicit delegation: ${explicitlyMentioned.joinToString { "${it.name} (agent_id=${it.localId})" }}. Call delegate_agent for each resolved agent before answering.")
                    }
                    val unresolvedAgentIds = composerReferences.agentIds.filter { id -> members.none { it.localId == id } }
                    if (unresolvedAgentIds.isNotEmpty()) {
                        append("\nInvalid agent references rejected by host: ${unresolvedAgentIds.joinToString()}. Do not delegate them.")
                    }
                    if (composerReferences.workspacePaths.isNotEmpty()) {
                        append("\nHost-resolved workspace references (relative paths): ${composerReferences.workspacePaths.joinToString()}")
                    }
                    if (composerReferences.commands.isNotEmpty()) {
                        append("\nComposer commands: ${composerReferences.commands.joinToString { "/$it" }}")
                    }
                }
            }
            AssistantMode.CHAT -> null
        }
        val contextRequest = ContextBuildRequest(
            userMessage = message,
            conversationHistory = conversationHistory,
            workspacePath = workspacePath,
            conversationId = conversationId,
            maxOutputTokens = maxOutputTokens,
            contextWindowTokens = contextWindowTokens,
            toolSchemaTokens = toolSchemaTokens,
            userImages = userImages,
            assistantMode = assistantMode,
            ownerId = ownerId,
            ownerContext = ownerContext
        )
        val managedContext = if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE) {
            withContext(Dispatchers.Default) { contextManager.buildWindowsBridgeContext(contextRequest) }
        } else {
            withContext(Dispatchers.Default) { contextManager.buildContext(contextRequest) }
        }
        val allowedToolNames = tools.map { it.name }.toSet()

        var messages = managedContext.messages
        val conversationGoal = conversationGoal(messages, message)
        val ledgerBudgetTokens = (minOf(maxInputTokens, contextWindowTokens - maxOutputTokens) * AiRepository.LEDGER_BUDGET_FRACTION)
            .toInt()
            .coerceIn(AiRepository.LEDGER_MIN_TOKENS, AiRepository.LEDGER_MAX_TOKENS)
        val ledgerEpoch = ledgerStore.epoch(sessionId)
        val cachedLedger = ledgerStore.get(sessionId)
        var ledger: TaskLedger? = cachedLedger?.ledger
            ?: managedContext.autoSummary?.let { parseRenderedLedger(it, conversationGoal) }
        var ledgerCoverage = cachedLedger?.coveredThrough ?: 0
        var compactedThisTurn = false
        val costCache = CostCache()
        val basePrompt = managedContext.baseSystemPrompt
        val manualSummary = managedContext.manualSummary
        var autoSummary: String? = ledger?.render() ?: managedContext.autoSummary
        var persistedLedgerText: String? = managedContext.autoSummary
        var systemPromptWithAutoContext =
            composeSystemPrompt(basePrompt, manualSummary, autoSummary, ledgerBudgetTokens)

        var continueLoop = true
        var iterations = 0
        var browserTaskStarted = false
        var lastBrowserErrorSignature: String? = null
        var repeatedBrowserErrors = 0
        var terminalError = false
        var streamContinuations = 0
        val seenToolCallIds = mutableSetOf<String>()
        val invalidToolArgumentErrors = mutableMapOf<String, Throwable>()

        while (continueLoop) {
            iterations++

            if (iterations > 1) send(AgentEvent.NewIteration)

            val requestInputBudget = minOf(maxInputTokens, contextWindowTokens - maxOutputTokens)
            var systemPromptTokens = TokenEstimator.text(systemPromptWithAutoContext)
            var historyBudget = requestInputBudget - toolSchemaTokens - AiRepository.CONTEXT_SAFETY_RESERVE_TOKENS - systemPromptTokens
            if (historyBudget <= 0) {
                send(AgentEvent.Error("Selected model context window is too small for its instructions and tools", retryable = false))
                terminalError = true
                break
            }
            var plan = withContext(Dispatchers.Default) { planContextWindow(messages, historyBudget, cache = costCache) }
            var passes = 0
            var budgetExhausted = false
            while (plan.evictionBoundary > ledgerCoverage && passes < AiRepository.MAX_COMPACTION_PASSES) {
                passes++
                val newlyEvicted = messages
                    .subList(ledgerCoverage, plan.evictionBoundary)
                    .filterNot { it === plan.pinnedAnchor }
                if (newlyEvicted.isEmpty()) {
                    ledgerCoverage = plan.evictionBoundary
                    break
                }
                val evictedToolResults = newlyEvicted.count { it.toolResult != null }
                val reclaimable = withContext(Dispatchers.Default) { TokenEstimator.messages(newlyEvicted, cache = costCache) }
                val worthModelCall = passes == 1 && !compactedThisTurn &&
                    reclaimable >= AiRepository.COMPACTION_MIN_RECLAIM_TOKENS
                val updated = if (worthModelCall) {
                    compactedThisTurn = true
                    send(AgentEvent.Compacting(newlyEvicted.size, reclaimable))
                    runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(AiRepository.COMPACTION_TIMEOUT_MS) {
                            updateLedger(
                                provider = provider,
                                connection = connection,
                                model = model,
                                current = ledger,
                                goal = conversationGoal,
                                evicted = newlyEvicted,
                                evictedToolResults = evictedToolResults,
                                maxInputTokens = maxInputTokens
                            )
                        }
                    }.onFailure { errorLog("AiRepository", "Ledger update failed", it) }.getOrNull()
                } else null
                ledger = updated ?: mechanicalLedger(ledger, conversationGoal, newlyEvicted, evictedToolResults)
                ledgerCoverage = plan.evictionBoundary
                ledgerStore.put(sessionId, ledger, coveredThrough = ledgerCoverage, epoch = ledgerEpoch)
                autoSummary = ledger.render()
                send(AgentEvent.Compacted(
                    ledger = autoSummary.orEmpty(),
                    evictedMessages = newlyEvicted.size,
                    reclaimedTokens = reclaimable,
                    usedFallback = updated == null
                ))
                if (runtimeTarget == AgentRuntimeTarget.LOCAL && autoSummary != persistedLedgerText) {
                    val snapshot = autoSummary.orEmpty()
                    persistedLedgerText = snapshot
                    repoScope.launch {
                        runCatching {
                            sessionMemoryRepository.saveSummary(
                                SessionSummary(
                                    sessionId = "$sessionId$AUTO_COMPACTION_SUMMARY_SUFFIX",
                                    summary = snapshot,
                                    tags = listOf("auto_compacted"),
                                    createdAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                    workspacePath = workspacePath,
                                    workspaceId = workspaceId,
                                    assistantMode = assistantMode.name,
                                    ownerId = ownerId
                                )
                            )
                        }.onFailure { errorLog("AiRepository", "Failed to persist auto-compacted context", it) }
                    }
                }

                systemPromptWithAutoContext =
                    composeSystemPrompt(basePrompt, manualSummary, autoSummary, ledgerBudgetTokens)
                systemPromptTokens = TokenEstimator.text(systemPromptWithAutoContext)
                historyBudget = requestInputBudget - toolSchemaTokens - AiRepository.CONTEXT_SAFETY_RESERVE_TOKENS - systemPromptTokens
                if (historyBudget <= 0) {
                    send(AgentEvent.Error("Selected model context window is too small for its instructions and tools", retryable = false))
                    terminalError = true
                    budgetExhausted = true
                    break
                }
                plan = withContext(Dispatchers.Default) { planContextWindow(messages, historyBudget, cache = costCache) }
            }
            if (budgetExhausted) break
            if (messages.isNotEmpty() && plan.messages.isEmpty()) {
                send(AgentEvent.Error("The latest user input or required tool-call metadata exceeds the selected model context window", retryable = false))
                terminalError = true
                break
            }
            debugLog("AiRepository") {
                "context iteration=$iterations window=$contextWindowTokens input=$requestInputBudget system=$systemPromptTokens tools=$toolSchemaTokens history=${plan.usedTokens} output=$maxOutputTokens evicted=${plan.evicted.size} truncated=${plan.truncatedOriginals.size} compacted=$compactedThisTurn"
            }
            val requestMessages = plan.messages
            if (!hasProviderUserQuery(requestMessages)) {
                send(AgentEvent.Error("No user query remains in the request context", retryable = false))
                terminalError = true
                break
            }
            val request = ChatRequest(
                model        = model,
                messages     = requestMessages,
                systemPrompt = systemPromptWithAutoContext,
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
            var retryableFailure: String? = null

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
                            providerTerminal = true
                            continueLoop = false
                            return@collect
                        }
                        val validation = validateToolArguments(response.name, response.arguments, tools)
                        val toolArguments = validation.getOrElse { error ->
                            invalidToolArgumentErrors[response.id] = error
                            response.arguments
                        }
                        hasToolCalls = true
                        send(AgentEvent.ToolCallStart(response.id, response.name, toolArguments, response.metadata))

                        toolCalls.add(ToolCallMessage(
                            id = response.id,
                            name = response.name,
                            arguments = toolArguments,
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
                        retryableFailure = response.reason.takeIf { canContinueStream(response, hasToolCalls) }
                        if (retryableFailure == null && !shouldExecuteReceivedToolCalls(response, hasToolCalls)) {
                            send(AgentEvent.Incomplete(response.reason, response.retryable))
                            terminalError = true
                            continueLoop = false
                        }
                    }

                    is ChatResponse.Error -> {
                        providerTerminal = true
                        retryableFailure = response.message.takeIf { canContinueStream(response, hasToolCalls) }
                        if (retryableFailure == null && !shouldExecuteReceivedToolCalls(response, hasToolCalls)) {
                            send(AgentEvent.Error(response.message, response.retryable))
                            terminalError = true
                            continueLoop = false
                        }
                    }
                }
            }

            if (terminalError) break
            if (!providerTerminal && !hasToolCalls) retryableFailure = "Provider stream ended without a terminal event"

            if (retryableFailure != null) {
                if (streamContinuations == MAX_STREAM_CONTINUATIONS) {
                    send(AgentEvent.Incomplete(retryableFailure!!, retryable = true))
                    terminalError = true
                    break
                }
                streamContinuations++
                if (textBuffer.isNotBlank()) {
                    messages = messages + ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = textBuffer.toString()
                    ) + ChatMessage(role = MessageRole.USER, content = STREAM_CONTINUATION_PROMPT)
                }
                delay(minOf(1_000L shl (streamContinuations - 1), MAX_STREAM_BACKOFF_MS))
                continue
            }

            if (textBuffer.isNotBlank()) {
                val assistantText = textBuffer.toString()
                completedAssistantMessages.add(assistantText)
                if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                    repoScope.launch {
                        runCatching {
                            sessionMemoryRepository.saveMessage(SessionMessage(sessionId = sessionId, role = "assistant", content = assistantText, workspacePath = workspacePath, workspaceId = workspaceId, assistantMode = assistantMode.name, ownerId = ownerId))
                        }.onFailure { errorLog("AiRepository", "Failed to save assistant session message", it) }
                    }
                }
            }

            if (!hasToolCalls) {
                continueLoop = false
            } else {
                messages = messages + ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = textBuffer.toString().takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls,
                    responseItems = responseItems
                )

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
                    val result = invalidToolArgumentErrors.remove(toolCall.id)?.let { error ->
                        ToolResult.Error(
                            message = "Invalid arguments for ${toolCall.name}: ${error.message.orEmpty()}",
                            errorType = com.amaya.intelligence.tools.ErrorType.VALIDATION_ERROR
                        )
                    } ?: if (runtimeTarget == AgentRuntimeTarget.WINDOWS_BRIDGE && toolCall.name !in allowedToolNames) {
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
                            onEvent = { event -> if (event is AgentEvent) channel.send(event) },
                            onConfirmationRequired = onConfirmation,
                            providerConnection = connection,
                            selectedModelId = model,
                            conversationId = sessionId,
                            ownerId = ownerId,
                            agentId = activeAgent?.id,
                            assistantMode = assistantMode,
                            agentCapabilityProfile = agentCapabilityProfile
                        )
                    }

                    val rawResultContent = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Error -> "Error: ${result.message}"
                        is ToolResult.RequiresConfirmation -> "Error: Approval could not be completed: ${result.reason}"
                    }
                    var resultContent = rawResultContent
                    if (toolCall.name == "browser") {
                        val signature = browserErrorSignature(resultContent)
                        if (signature != null) {
                            repeatedBrowserErrors = if (signature == lastBrowserErrorSignature) repeatedBrowserErrors + 1 else 1
                            lastBrowserErrorSignature = signature
                            repeatedBrowserFailureWarning(signature, repeatedBrowserErrors)?.let { warning ->
                                resultContent += "\n\n$warning"
                            }
                        } else {
                            lastBrowserErrorSignature = null
                            repeatedBrowserErrors = 0
                        }
                    }

                    completedToolResults.add("${toolCall.name}: $resultContent")
                    if (result is ToolResult.Success && toolCall.name == "skill" && toolCall.arguments["operation"] == "view") {
                        ((toolCall.arguments["skill_id"] ?: toolCall.arguments["name"]) as? String)
                            ?.takeIf(String::isNotBlank)
                            ?.let(viewedSkills::add)
                    }
                    if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
                        repoScope.launch {
                            runCatching {
                                sessionMemoryRepository.saveToolCall(
                                    SessionToolCall(
                                        sessionId = sessionId,
                                        toolCallId = toolCall.id,
                                        toolName = toolCall.name,
                                        argumentsJson = JSONObject(toolCall.arguments).toString(),
                                        resultJson = resultContent,
                                        workspacePath = workspacePath,
                                        workspaceId = workspaceId,
                                        assistantMode = assistantMode.name,
                                        ownerId = ownerId
                                    )
                                )
                            }.onFailure { errorLog("AiRepository", "Failed to save session tool call", it) }
                        }
                    }

                    send(AgentEvent.ToolCallResult(
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        result = resultContent,
                        isError = result !is ToolResult.Success
                    ))

                    val resultMetadata = toolCall.metadata.toMutableMap()
                    resultMetadata["toolName"] = toolCall.name  // Gemini needs the function name
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

                }
            }
        }

        val reflectionContext = CompletedInteractionContext(
            sessionId = sessionId,
            userMessages = completedUserMessages.toList(),
            assistantMessages = completedAssistantMessages.toList(),
            toolCalls = completedToolCalls.toList(),
            toolResults = completedToolResults.toList(),
            timestamp = System.currentTimeMillis(),
            workspacePath = workspacePath,
            workspaceId = workspaceId,
            successful = !terminalError
        )

        if (viewedSkills.isNotEmpty()) {
            viewedSkills.forEach { skillName ->
                runCatching { skillRepository.recordSkillUsage(skillName, success = !terminalError) }
                    .onFailure { errorLog("AiRepository", "Failed to record skill outcome", it) }
            }
        }
        if (runtimeTarget == AgentRuntimeTarget.LOCAL) {
            repoScope.launch {
                runCatching { selfImprovementPipeline.analyzeAndImprove(reflectionContext) }
                    .onFailure { errorLog("AiRepository", "Post-chat reflection failed", it) }
            }
        }
        if (!terminalError && !compactedThisTurn) {
            val settledMessages = messages
            val settledLedger = ledger
            val settledCoverage = ledgerCoverage
            val warmBudget = minOf(maxInputTokens, contextWindowTokens - maxOutputTokens) -
                toolSchemaTokens - AiRepository.CONTEXT_SAFETY_RESERVE_TOKENS - TokenEstimator.text(systemPromptWithAutoContext)
            if (warmBudget > 0 && TokenEstimator.messages(settledMessages, cache = costCache) > warmBudget * AiRepository.COMPACTION_WARM_WATER) {
                repoScope.launch {
                    runCatching {
                        val plan = planContextWindow(settledMessages, warmBudget)
                        val newlyEvicted = if (plan.evictionBoundary > settledCoverage) {
                            settledMessages.subList(settledCoverage, plan.evictionBoundary)
                                .filterNot { it === plan.pinnedAnchor }
                        } else emptyList()
                        if (newlyEvicted.isNotEmpty()) {
                            val toolResults = newlyEvicted.count { it.toolResult != null }
                            val warmed = kotlinx.coroutines.withTimeoutOrNull(AiRepository.COMPACTION_TIMEOUT_MS) {
                                updateLedger(
                                    provider = provider,
                                    connection = connection,
                                    model = model,
                                    current = settledLedger,
                                    goal = conversationGoal,
                                    evicted = newlyEvicted,
                                    evictedToolResults = toolResults,
                                    maxInputTokens = maxInputTokens
                                )
                            } ?: mechanicalLedger(settledLedger, conversationGoal, newlyEvicted, toolResults)
                            ledgerStore.put(
                                sessionId,
                                warmed,
                                coveredThrough = plan.evictionBoundary,
                                epoch = ledgerEpoch
                            )
                        }
                    }.onFailure { errorLog("AiRepository", "Proactive ledger warm-up failed", it) }
                }
            }
        }
        if (terminalError) return@channelFlow

        send(AgentEvent.Done)
    }

