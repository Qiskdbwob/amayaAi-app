package com.amaya.intelligence.data.remote.mcp

import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider
import com.amaya.intelligence.tools.ConfirmationRequest
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
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
        // FIX 9: Use McpClientManager.TOOL_PREFIX constant — no hardcoded "mcp__" string here
        return when {
            toolName.startsWith(McpClientManager.TOOL_PREFIX) ->
                mcpClientManager.callTool(toolName, arguments)
            windowsBridgeToolProvider.isBridgeTool(toolName) ->
                windowsBridgeToolProvider.executeBridgeTool(toolName, arguments)
            else ->
                toolExecutor.execute(toolName, arguments, workspacePath, toolCallId, onEvent, onConfirmationRequired, agentConfig)
        }
    }
}
