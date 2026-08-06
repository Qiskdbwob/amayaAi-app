package com.amaya.intelligence.data.remote.mcp

import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import com.amaya.intelligence.tools.resolveBridgeToolWireName
import com.amaya.intelligence.tools.sanitizeModelArguments
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpToolExecutor @Inject constructor(
    private val toolExecutor: ToolExecutor,
    private val mcpClientManager: McpClientManager,
    private val windowsBridgeToolProvider: WindowsBridgeToolProvider
) {
    suspend fun execute(
        toolName: String,
        arguments: Map<String, Any?>,
        workspacePath: String?,
        toolCallId: String? = null,
        onEvent: (suspend (Any) -> Unit)? = null,
        onConfirmationRequired: suspend (ConfirmationRequest) -> Boolean,
        providerConnection: com.amaya.intelligence.data.remote.api.ProviderConnection? = null,
        selectedModelId: String? = null,
        conversationId: String? = null,
        ownerId: String? = null,
        agentId: Long? = null,
        assistantMode: AssistantMode = AssistantMode.PROJECT,
        agentCapabilityProfile: com.amaya.intelligence.domain.models.AgentCapabilityProfile? = null
    ): ToolResult {
        // Reverse-map sanitized bridge tool names (e.g. "screen_capture" → "screen.capture").
        // The model receives sanitized names (dots replaced with underscores) because OpenAI
        // rejects names that don't match ^[a-zA-Z0-9_-]+$. We restore the wire name here
        // before routing so the bridge registry lookup always uses the canonical dot form.
        val modelArguments = arguments.toMutableMap().apply {
            remove("cwd")
            remove("working_dir")
        }
        val safeArguments = sanitizeModelArguments(modelArguments).getOrElse { error ->
            return ToolResult.Error(error.message.orEmpty(), com.amaya.intelligence.tools.ErrorType.VALIDATION_ERROR)
        }
        val wireName = resolveBridgeToolWireName(toolName)

        // FIX 9: Use McpClientManager.TOOL_PREFIX constant — no hardcoded "mcp__" string here
        return when {
            wireName.startsWith(McpClientManager.TOOL_PREFIX) -> {
                val identity = toolCallId ?: return ToolResult.Error(
                    "MCP tool call is missing a call ID; approval cannot be bound safely.",
                    com.amaya.intelligence.tools.ErrorType.VALIDATION_ERROR
                )
                val approved = onConfirmationRequired(
                    ConfirmationRequest(
                        toolName = wireName,
                        reason = "External MCP servers are untrusted and may access or modify data",
                        details = safeArguments.toString(),
                        riskLevel = com.amaya.intelligence.domain.security.RiskLevel.MEDIUM,
                        toolCallId = identity
                    )
                )
                if (!approved) return ToolResult.Error(
                    "User declined external MCP tool call",
                    com.amaya.intelligence.tools.ErrorType.PERMISSION_ERROR
                )
                mcpClientManager.callTool(wireName, safeArguments)
            }
            windowsBridgeToolProvider.isBridgeTool(wireName) ->
                windowsBridgeToolProvider.executeBridgeTool(wireName, safeArguments)
            else ->
                toolExecutor.execute(
                    wireName,
                    safeArguments,
                    workspacePath,
                    toolCallId,
                    onEvent,
                    onConfirmationRequired,
                    providerConnection,
                    selectedModelId,
                    conversationId = conversationId,
                    ownerId = ownerId,
                    agentId = agentId,
                    assistantMode = assistantMode,
                    agentCapabilityProfile = agentCapabilityProfile
                )
        }
    }
}
