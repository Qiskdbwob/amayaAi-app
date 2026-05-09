package com.amaya.intelligence.data.remote.mcp

import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import com.amaya.intelligence.tools.resolveBridgeToolWireName
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
        agentConfig: com.amaya.intelligence.data.remote.api.AgentConfig? = null
    ): ToolResult {
        // Reverse-map sanitized bridge tool names (e.g. "screen_capture" → "screen.capture").
        // The model receives sanitized names (dots replaced with underscores) because OpenAI
        // rejects names that don't match ^[a-zA-Z0-9_-]+$. We restore the wire name here
        // before routing so the bridge registry lookup always uses the canonical dot form.
        val wireName = resolveBridgeToolWireName(toolName)

        // FIX 9: Use McpClientManager.TOOL_PREFIX constant — no hardcoded "mcp__" string here
        return when {
            wireName.startsWith(McpClientManager.TOOL_PREFIX) ->
                mcpClientManager.callTool(wireName, arguments)
            windowsBridgeToolProvider.isBridgeTool(wireName) ->
                windowsBridgeToolProvider.executeBridgeTool(wireName, arguments)
            else ->
                toolExecutor.execute(wireName, arguments, workspacePath, toolCallId, onEvent, onConfirmationRequired, agentConfig)
        }
    }
}
