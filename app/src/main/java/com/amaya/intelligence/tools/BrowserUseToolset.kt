package com.amaya.intelligence.tools

import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserUseToolset @Inject constructor(
    private val browserSessionManager: BrowserSessionManager
) {
    private val legacyToolNames = listOf(
        "open_url",
        "new_page",
        "close_page",
        "click_element",
        "type_text",
        "clear_input",
        "scroll_page",
        "get_dom",
        "get_visible_text",
        "get_screenshot",
        "find_element",
        "wait_for_element",
        "go_back",
        "go_forward",
        "reload_page",
        "cancel_action",
        "pause_session",
        "resume_session"
    )

    /**
     * Only `browser` is advertised to the model. Legacy names remain executable for
     * backwards compatibility with older conversations or manual debug calls.
     */
    val tools: List<Tool> = listOf(BrowserTool("browser")) + legacyToolNames.map { BrowserTool(it) }

    fun isBrowserTool(toolName: String): Boolean = toolName == "browser" || toolName in legacyToolNames

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
                        "evaluate_script", "go_back", "reload"
                    )
                ),
                ToolParameter("params", "object", "Parameters for action. Common fields: url, element_id, selector, query, text, script, key, submit, append, direction, amount, timeout_ms. Use element_id from agent.interactive_elements when possible. Never pass password/OTP/token text unless user allowed once.", required = false),
                ToolParameter("url", "string", "Top-level shortcut for open_url/new_tab", required = false),
                ToolParameter("element_id", "string", "Top-level shortcut for click/type_text/clear_input target. Prefer this over selector.", required = false),
                ToolParameter("query", "string", "Top-level shortcut for find_element/wait_for_element. Use a short visible label/text.", required = false),
                ToolParameter("text", "string", "Top-level shortcut for type_text content. Do not send sensitive data unless user allowed once.", required = false),
                ToolParameter("steps", "array", "Batch related browser steps. Each item: {action:string, params:{url|element_id|query|text|...}}. Strongly preferred for browsing tasks: open_url -> get_dom -> find_element/click/read.", required = false, items = "object"),
                ToolParameter("reset_task", "boolean", "Start a fresh parent Browser task instead of appending to the current one", required = false)
            )
        )
    )

    private inner class BrowserTool(override val name: String) : Tool {
        override val description: String = if (name == "browser") {
            "Parent Local AI Browser Operator tool with nested browser sub-toolcalls."
        } else {
            "Legacy Local Browser Use alias for $name. Prefer parent tool `browser`."
        }

        override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
            if (name == "browser") {
                val json = browserSessionManager.executeBrowserTask(arguments)
                return ToolResult.Success(json, mapOf("tool_family" to "browser", "parent_tool" to true))
            }

            val action = legacyNameToAction(name)
            val wrapped = mapOf(
                "task" to "Browser action",
                "action" to action,
                "params" to normalizeLegacyArguments(name, arguments),
                "__toolCallId" to (arguments["__toolCallId"] ?: arguments["parent_call_id"])
            )
            val json = browserSessionManager.executeBrowserTask(wrapped)
            return ToolResult.Success(json, mapOf("tool_family" to "browser", "legacy_alias" to name))
        }
    }

    private fun legacyNameToAction(name: String): String = when (name) {
        "new_page" -> "new_tab"
        "close_page" -> "close_tab"
        "click_element" -> "click"
        "scroll_page" -> "scroll"
        "get_screenshot" -> "screenshot"
        "reload_page" -> "reload"
        else -> name
    }

    private fun normalizeLegacyArguments(name: String, args: Map<String, Any?>): Map<String, Any?> {
        return when (name) {
            "new_page" -> args + ("url" to args["url"])
            "click_element", "clear_input" -> args + ("target" to (args["element_id"] ?: args["selector"] ?: args["query"]))
            "type_text" -> args + ("target" to (args["element_id"] ?: args["selector"] ?: args["query"]))
            "scroll_page" -> args + mapOf("delta_y" to (args["delta_y"] ?: 800), "delta_x" to (args["delta_x"] ?: 0))
            else -> args
        }.filterKeys { !it.startsWith("__") }
    }
}
