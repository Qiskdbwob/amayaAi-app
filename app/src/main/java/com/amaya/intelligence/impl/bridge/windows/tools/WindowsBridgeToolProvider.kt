package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.tools.ToolDefinition
import com.amaya.intelligence.tools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade consumed by the agent tool loop. Keeps the public surface small so the
 * existing `ToolExecutor`/`McpToolExecutor` only learn about four methods when they
 * merge bridge tools into the model prompt.
 *
 * Phase 3B integration: `AiRepository.buildToolDefinitions()` appends the result of
 * [getAvailableBridgeTools] to its list; `McpToolExecutor.execute` routes any tool
 * name where [isBridgeTool] is true through [executeBridgeTool].
 */
@Singleton
class WindowsBridgeToolProvider @Inject constructor(
    private val controller: WindowsBridgeController
) {

    /**
     * Tool definitions to advertise to the model right now. Returns an empty list
     * when the bridge is not connected or when the relevant tools are gated behind
     * Agent Control and the user hasn't enabled it.
     */
    fun getAvailableBridgeTools(): List<ToolDefinition> {
        val visible = controller.visibleToolNames()
        if (visible.isEmpty()) return emptyList()
        return controller.registry().enabledDefinitions().filter { it.name in visible }
    }

    /** Execute a bridge tool. Always returns a [ToolResult]; never throws. */
    suspend fun executeBridgeTool(
        name: String,
        arguments: Map<String, Any?>
    ): ToolResult {
        if (!controller.isBridgeTool(name)) {
            return WindowsBridgeToolResultMapper.unknown(name)
        }
        if (!controller.isToolVisible(name)) {
            val reason = if (!controller.isActive()) {
                "Windows Bridge is not connected. Connect to a Windows Bridge session first."
            } else {
                "Windows Bridge tool '$name' is disabled until Agent Control is enabled."
            }
            return WindowsBridgeToolResultMapper.unavailable(name, reason)
        }
        val executor = controller.executor()
            ?: return WindowsBridgeToolResultMapper.unavailable(
                name,
                "Windows Bridge is not initialized."
            )
        return executor.execute(
            toolName = name,
            arguments = arguments,
            sessionId = controller.currentSessionId()
        )
    }

    /** Current availability snapshot. Useful for UI and debug surfaces. */
    fun availability(): WindowsBridgeToolAvailability = controller.availability()

    /** True when [toolName] is a known bridge tool name (enabled or not). */
    fun isBridgeTool(toolName: String): Boolean = controller.isBridgeTool(toolName)
}
