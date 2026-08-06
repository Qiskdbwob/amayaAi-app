package com.amaya.intelligence.ui.screens.browser

import androidx.compose.ui.graphics.Color
import com.amaya.intelligence.domain.models.ToolInfoIcon
import com.amaya.intelligence.impl.local.browser.BrowserAgentStatus
import com.amaya.intelligence.impl.local.browser.BrowserToolLog
import com.amaya.intelligence.impl.local.browser.BrowserUiState
import java.net.URI

internal fun browserAgentStatusColor(state: BrowserUiState): Color = when {
    state.isCancelled || state.lastError != null -> Color(0xFFFF453A)
    state.isAssistantStreaming && state.browserAccessActive -> Color(0xFF0A84FF)
    state.isAssistantStreaming -> Color(0xFF64D2FF)
    state.status == BrowserAgentStatus.BROWSING -> Color(0xFF0A84FF)
    state.status == BrowserAgentStatus.COMPLETED -> Color(0xFF34C759)
    else -> Color(0xFF8E8E93)
}

internal fun browserStatusColor(status: String): Color = when (status.lowercase()) {
    "success", "completed" -> Color(0xFF34C759)
    "running", "browsing" -> Color(0xFF0A84FF)
    "paused", "waiting", "waiting_input" -> Color(0xFFFFB340)
    "error", "cancelled", "timeout" -> Color(0xFFFF453A)
    else -> Color(0xFF8E8E93)
}

internal fun browserActionLabel(name: String): String {
    val normalized = name.removePrefix("browser.").lowercase()
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
        "browser" -> "Browser agent"
        "assistant" -> "Agent message"
        "thinking" -> "Thinking"
        else -> normalized.split('_', '-').filter(String::isNotBlank)
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

internal fun browserToolIcon(tool: String): ToolInfoIcon = when (tool.removePrefix("browser.").lowercase()) {
    "open_url", "new_page", "new_tab", "reload", "reload_page", "go_back", "go_forward" -> ToolInfoIcon.WORLD
    "get_dom", "analyze_page", "observe", "get_visible_text" -> ToolInfoIcon.READ
    "click", "click_element", "tap", "press_key", "scroll", "scroll_page", "swipe" -> ToolInfoIcon.MOUSE
    "type", "type_text", "clear_input" -> ToolInfoIcon.EDIT
    "search", "find_element", "find_text", "wait_for_element" -> ToolInfoIcon.SEARCH
    "get_screenshot", "screenshot" -> ToolInfoIcon.IMAGE
    else -> ToolInfoIcon.BROWSER
}

internal fun browserLogTarget(log: BrowserToolLog): String {
    val source = listOf(log.argumentsPreview, log.message).joinToString(" ")
    Regex("https?://[^\\s,]+", RegexOption.IGNORE_CASE).find(source)?.value?.let { url ->
        return runCatching { URI(url).host ?: url }.getOrDefault(url).removePrefix("www.")
    }
    Regex("url=([^,\\s]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("query=([^,]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it.trim() }
    Regex("text=([^,]+)", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.let { return it.trim() }
    return ""
}

internal fun hasOpenThinkingTag(text: String): Boolean {
    val open = Regex("<think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
    val close = Regex("</think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
    return open != null && (close == null || open > close)
}

internal fun cleanBrowserThinkText(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw.replace(Regex("<think>(.*?)</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) {
        it.groupValues.getOrNull(1).orEmpty()
    }
    text = text.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
    return text.replace(Regex("<[a-zA-Z/]*$"), "").trim()
}
