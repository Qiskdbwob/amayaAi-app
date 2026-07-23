package com.amaya.intelligence.impl.local.browser

object BrowserActionCatalog {
    // Keep aliases accepted by BrowserSessionManager in the schema. Models commonly use
    // the canonical internal names after seeing prior browser results.
    val names = listOf(
        "new_page", "new_tab", "close_page", "close_tab", "list_pages", "get_status", "switch_page", "switch_tab",
        "open_url", "observe", "analyze_page", "get_snapshot", "get_dom", "get_html", "get_content", "get_visible_text",
        "find_element", "find_text", "wait_for_selector", "wait_for_element", "wait_for_nav", "wait_for_navigation",
        "click", "click_element", "tap", "type", "type_text", "clear_input", "focus", "hover", "select_option", "press_key",
        "scroll", "scroll_page", "swipe", "search", "go_back", "go_forward", "reload", "reload_page",
        "screenshot", "get_screenshot", "evaluate", "expression", "pause_session", "resume_session", "cancel_action"
    )
}
