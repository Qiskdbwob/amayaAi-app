package com.amaya.intelligence.tools

import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserUseToolset @Inject constructor(
    private val browserSessionManager: BrowserSessionManager
) {
    companion object {
        internal val MODEL_TOOL_NAMES = setOf("browser")
    }

    val tools: List<Tool> = listOf(BrowserTool())

    fun isBrowserTool(toolName: String): Boolean = toolName == "browser"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "browser",
            description = "Playwright-style local visible browser. Use this ONE parent tool for all browser work. Prefer batching related work in steps[] so open_url/get_dom/find/click become nested sub-toolcalls under one Browser card. Returns compact JSON observations; get_dom defaults to interactive_summary and never raw HTML.",
            parameters = listOf(
                ToolParameter("task", "string", "Short user-facing task summary. Keep the same task while continuing the same browser job.", required = false),
                ToolParameter(
                    "action",
                    "string",
                    "Single browser action to run",
                    required = false,
                    enum = listOf(
                        "open_url", "observe", "click", "type", "press_key", "scroll", "search",
                        "go_back", "reload"
                    )
                ),
                ToolParameter("params", "object", "Parameters for action. Common fields: url, element_id, selector, query, text, key, submit, append, direction, amount, timeout_ms. Use element_id from agent.interactive_elements when possible. Never pass password/OTP/token text unless user allowed once.", required = false),
                ToolParameter("url", "string", "Top-level shortcut for open_url/new_tab", required = false),
                ToolParameter("element_id", "string", "Top-level shortcut for click/type_text/clear_input target. Prefer this over selector.", required = false),
                ToolParameter("query", "string", "Top-level shortcut for find_element/wait_for_element. Use a short visible label/text.", required = false),
                ToolParameter("text", "string", "Top-level shortcut for type_text content. Do not send sensitive data unless user allowed once.", required = false),
                ToolParameter("steps", "array", "Batch related browser steps. Each item: {action:string, params:{url|element_id|query|text|...}}. Strongly preferred for browsing tasks: open_url -> get_dom -> find_element/click/read.", required = false, items = "object"),
                ToolParameter("reset_task", "boolean", "Start a fresh parent Browser task instead of appending to the current one", required = false)
            )
        )
    )

    private inner class BrowserTool : Tool, ContextAwareTool {
        override val name: String = "browser"
        override val description: String = "Parent Local AI Browser Operator tool with nested browser sub-toolcalls."

        override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
            execute(arguments, ToolExecutionContext())

        override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
            val json = browserSessionManager.executeBrowserTask(arguments, context)
            return ToolResult.Success(json, mapOf("tool_family" to "browser", "parent_tool" to true))
        }
    }
}
