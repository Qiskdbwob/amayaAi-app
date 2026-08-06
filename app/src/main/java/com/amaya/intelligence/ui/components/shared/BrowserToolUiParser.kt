package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolCategory
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.ToolUiMetadata
import org.json.JSONObject

internal fun synthesizeBrowserGroup(parentExecution: ToolExecution, stepIndex: Int): ToolExecutionGroup? {
    val resultJsonStr = parentExecution.result ?: return null
    val parentJson = runCatching { JSONObject(resultJsonStr) }.getOrNull() ?: return null
    
    if (parentJson.optString("tool") != "browser") return null
    
    val subcalls = parentJson.optJSONArray("sub_toolcalls") ?: return null

    val syntheticExecutions = mutableListOf<ToolExecution>()
    
    for (i in 0 until subcalls.length()) {
        val item = subcalls.optJSONObject(i) ?: continue
        val response = item.optJSONObject("response") ?: JSONObject()
        val rawTool = item.optString("tool", response.optString("tool", "browser.step"))
        val normalizedToolName = rawTool.removePrefix("browser.").lowercase()
        val statusStr = item.optString("status", response.optString("status"))
        
        val status = when (statusStr.lowercase()) {
            "success", "completed" -> ToolStatus.SUCCESS
            "running", "browsing" -> ToolStatus.RUNNING
            "error", "cancelled", "timeout" -> ToolStatus.ERROR
            "paused", "waiting_input" -> ToolStatus.PENDING
            else -> ToolStatus.PENDING
        }
        
        val label = browserToolDisplayName(rawTool)
        val markdownResult = buildBrowserMarkdownResponse(response)

        val args = mutableMapOf<String, Any?>()
        response.optJSONObject("request")?.optJSONObject("params")?.let { p ->
            p.keys().forEach { k -> args[k] = p.get(k) }
        }
        item.optJSONObject("params")?.let { p ->
            p.keys().forEach { k -> args[k] = p.get(k) }
        }
        
        val uiMeta = ToolUiMetadata(
            category = ToolCategory.WEB,
            label = label,
            actionIcon = browserToolIcon(rawTool),
            targetIcon = ToolInfoIcon.BROWSER
        )
        
        val childId = item.optString("id", "${parentExecution.toolCallId}_sub_$i")
        
        syntheticExecutions += ToolExecution(
            toolCallId = childId,
            name = normalizedToolName,
            arguments = args,
            result = markdownResult,
            status = status,
            metadata = parentExecution.metadata + mapOf(
                "source" to "local",
                "syntheticBrowserStep" to "true",
                "isTerminal" to "false"
            ),
            uiMetadata = uiMeta
        )
    }
    
    if (syntheticExecutions.isEmpty()) return null
    
    return ToolExecutionGroup(
        key = "browser_${parentExecution.toolCallId}",
        startIndex = stepIndex,
        endIndex = stepIndex,
        executions = syntheticExecutions,
        isActive = syntheticExecutions.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING },
        parentToolCallId = parentExecution.toolCallId
    )
}

private fun browserToolDisplayName(tool: String): String {
    val normalized = tool.removePrefix("browser.").lowercase()
    return when (normalized) {
        "new_page", "new_tab" -> "Open new tab"
        "open_url" -> "Open page"
        "get_dom", "analyze_page", "observe" -> "Observe page"
        "get_visible_text" -> "Read visible text"
        "click", "click_element" -> "Click element"
        "tap" -> "Tap screen"
        "type", "type_text" -> "Type text"
        "clear_input" -> "Clear field"
        "press_key" -> "Press key"
        "scroll", "scroll_page", "swipe" -> "Scroll page"
        "search" -> "Search page"
        "find_element" -> "Find element"
        "find_text" -> "Find text"
        "wait_for_element" -> "Wait for element"
        "evaluate", "expression", "run_js", "run_javascript" -> "Run expression"
        "get_screenshot", "screenshot" -> "Capture screenshot"
        "go_back" -> "Go back"
        "go_forward" -> "Go forward"
        "reload_page", "reload" -> "Reload page"
        else -> normalized.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

private fun browserToolIcon(tool: String): ToolInfoIcon {
    val normalized = tool.removePrefix("browser.").lowercase()
    return when (normalized) {
        "open_url", "new_page", "new_tab", "reload", "reload_page", "go_back", "go_forward" -> ToolInfoIcon.WORLD
        "get_dom", "analyze_page", "observe", "get_visible_text" -> ToolInfoIcon.READ
        "click", "click_element", "tap", "press_key" -> ToolInfoIcon.MOUSE
        "type", "type_text", "clear_input" -> ToolInfoIcon.EDIT
        "scroll", "scroll_page", "swipe" -> ToolInfoIcon.MOUSE
        "search", "find_element", "find_text", "wait_for_element" -> ToolInfoIcon.SEARCH
        "evaluate", "expression", "run_js", "run_javascript" -> ToolInfoIcon.BROWSER
        "get_screenshot", "screenshot" -> ToolInfoIcon.IMAGE
        else -> ToolInfoIcon.BROWSER
    }
}

private fun buildBrowserMarkdownResponse(response: JSONObject): String? {
    if (response.length() == 0) return null

    val result = response.optJSONObject("result")
    val error = response.optJSONObject("error")
    val output = result?.opt("value")?.takeUnless { it == JSONObject.NULL }
        ?: result?.opt("output")?.takeUnless { it == JSONObject.NULL }
    val sb = StringBuilder()

    if (error != null && !error.isNull("message")) {
        sb.append("## Error\n")
        sb.append("**${error.optString("code", "BROWSER_ACTION_FAILED")}**\n")
        sb.append(error.optString("message").trim())
        error.optString("suggested_action").takeIf { it.isNotBlank() }?.let {
            sb.append("\n\n## Next step\n${it.replace('_', ' ')}")
        }
    } else if (output != null && output != JSONObject.NULL) {
        sb.append("## Output\n```javascript\n$output\n```")
    } else if (result != null) {
        result.optJSONObject("page")?.let { page ->
            val title = page.optString("title").ifBlank { page.optString("url") }
            val url = page.optString("url")
            if (title.isNotBlank() || url.isNotBlank()) {
                sb.append("## Page state\n")
                if (title.isNotBlank()) sb.append("$title\n")
                if (url.isNotBlank() && url != title) sb.append(url)
            }
        }
        if (result.optJSONObject("dom") != null || result.has("text") || result.has("element") || result.has("matches")) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("## Page state\n```json\n${result.toString(2)}\n```")
        }
    }

    return sb.toString().trim().takeIf { it.isNotEmpty() }
}
