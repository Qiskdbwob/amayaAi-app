package com.amaya.intelligence.tools

import com.amaya.intelligence.impl.local.browser.BrowserActionCatalog
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import org.json.JSONObject
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
            description = "Browser is the only browser tool. JavaScript is action=evaluate, never a separate execute_javascript/run_javascript tool. Example: {action:'evaluate',params:{expression:'document.title'}}. Use evaluate for inspection or explicit postcondition checks; use type_text then click for normal React forms. Prefer batching related actions in steps[].",
            parameters = listOf(
                ToolParameter("task", "string", "Short user-facing task summary. Keep the same task while continuing the same browser job.", required = false),
                ToolParameter(
                    "action",
                    "string",
                    "Browser action. For JavaScript use evaluate with params.expression; do not call a separate JavaScript tool.",
                    required = false,
                    enum = BrowserActionCatalog.names
                ),
                ToolParameter("params", "object", "Parameters for action. Common fields: url, page_id, element_id, selector, query, text, key, submit, append, path/paths, direction, amount, timeout_ms.", required = false),
                ToolParameter("url", "string", "Top-level shortcut for open_url/new_tab", required = false),
                ToolParameter("element_id", "string", "Top-level shortcut for click/type_text/clear_input target. Omit for type_text to use the currently focused external-keyboard field.", required = false),
                ToolParameter("query", "string", "Top-level shortcut for find_element/find_text/wait_for_element. Use a short visible label/text.", required = false),
                ToolParameter("text", "string", "Top-level shortcut for type_text content. Supports any user-provided text.", required = false),
                ToolParameter("expression", "string", "JavaScript source for action=evaluate. Example: document.querySelector('button')?.disabled. This is not a separate tool.", required = false),
                ToolParameter("path", "string", "Workspace-relative file path for action=upload_file. Use only a file the user explicitly provided or requested.", required = false),
                ToolParameter("paths", "array", "Workspace-relative file paths for action=upload_file; web input accept/multiple rules still apply.", required = false, items = "string"),
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
            val parsed = runCatching { JSONObject(json) }.getOrNull()
            val approval = parsed?.findBrowserApproval()
            if (approval != null && !context.confirmed) return ToolResult.RequiresConfirmation(
                reason = approval.optString("confirmation_reason", "Confirm consequential browser action"),
                details = approval.optString("confirmation_details", parsed.optString("active_url"))
            )
            val screenshot = browserSessionManager.takeLastScreenshotAttachment(context)
            val metadata = buildMap<String, Any> {
                put("tool_family", "browser")
                put("parent_tool", true)
                screenshot?.let {
                    put("bridge_image_base64", it)
                    put("bridge_image_format", "jpeg")
                }
            }
            return ToolResult.Success(json, metadata)
        }

        private fun JSONObject.findBrowserApproval(): JSONObject? {
            val subtools = optJSONArray("sub_toolcalls") ?: return null
            for (index in 0 until subtools.length()) {
                val response = subtools.optJSONObject(index)?.optJSONObject("response") ?: continue
                val error = response.optJSONObject("error") ?: continue
                if (error.optString("code") != "USER_APPROVAL_REQUIRED") continue
                val details = error.optJSONObject("details") ?: JSONObject()
                val params = details.optJSONObject("params") ?: JSONObject()
                return JSONObject().apply {
                    put("confirmation_reason", "Select workspace file(s) for this web form?")
                    put("confirmation_details", "${response.optString("tool")} on ${details.optString("page_url")}; target=${params.optString("element_id", params.optString("selector", "file input"))}")
                }
            }
            return null
        }
    }
}
