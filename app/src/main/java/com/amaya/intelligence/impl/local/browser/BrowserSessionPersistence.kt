package com.amaya.intelligence.impl.local.browser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class BrowserSessionPersistence(private val prefs: SharedPreferences) {
    fun activeTabId(sessionId: String): String? = prefs.getString(key(sessionId, "active_tab_id"), null)

    fun activeUrl(sessionId: String): String? = prefs.getString(key(sessionId, "active_url"), null)

    fun tabs(sessionId: String): List<BrowserPageTab> = runCatching {
        val array = JSONArray(prefs.getString(key(sessionId, "tabs"), "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            BrowserPageTab(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = item.optString("title", "New Page"),
                url = item.optString("url", "about:blank"),
                canGoBack = item.optBoolean("can_go_back"),
                canGoForward = item.optBoolean("can_go_forward"),
                scrollX = item.optInt("scroll_x"),
                scrollY = item.optInt("scroll_y")
            )
        }
    }.getOrDefault(emptyList())

    fun history(sessionId: String): List<BrowserHistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString(key(sessionId, "history"), "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.optString("url").takeIf(String::isNotBlank)?.let {
                BrowserHistoryEntry(it, item.optString("title", it), item.optLong("visited_at"))
            }
        }.takeLast(MAX_HISTORY_ENTRIES)
    }.getOrDefault(emptyList())

    fun save(state: BrowserUiState) {
        val active = state.tabs.firstOrNull { it.id == state.activeTabId } ?: return
        prefs.edit()
            .putString(key(state.sessionId, "active_url"), active.url)
            .putString(key(state.sessionId, "active_title"), active.title)
            .putString(key(state.sessionId, "active_tab_id"), state.activeTabId.orEmpty())
            .putString(key(state.sessionId, "tabs"), JSONArray().apply {
                state.tabs.forEach { tab -> put(JSONObject().apply {
                    put("id", tab.id); put("title", tab.title); put("url", tab.url)
                    put("can_go_back", tab.canGoBack); put("can_go_forward", tab.canGoForward)
                    put("scroll_x", tab.scrollX); put("scroll_y", tab.scrollY)
                }) }
            }.toString())
            .putString(key(state.sessionId, "history"), JSONArray().apply {
                state.sessionHistory.forEach { entry -> put(JSONObject().apply {
                    put("url", entry.url); put("title", entry.title); put("visited_at", entry.visitedAt)
                }) }
            }.toString())
            .apply()
    }

    fun clear(sessionId: String) {
        prefs.edit()
            .remove(key(sessionId, "tabs"))
            .remove(key(sessionId, "history"))
            .remove(key(sessionId, "active_url"))
            .remove(key(sessionId, "active_title"))
            .remove(key(sessionId, "active_tab_id"))
            .apply()
    }

    private fun key(sessionId: String, field: String) = "$sessionId.$field"

    private companion object {
        const val MAX_HISTORY_ENTRIES = 60
    }
}
