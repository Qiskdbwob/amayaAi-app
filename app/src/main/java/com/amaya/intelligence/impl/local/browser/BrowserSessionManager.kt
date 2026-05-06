package com.amaya.intelligence.impl.local.browser

import android.content.Context
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safetyGuard: SafetyGuard
) {
    private val initialTab = BrowserPageTab()
    private val _uiState = MutableStateFlow(BrowserUiState(activeTabId = initialTab.id, tabs = listOf(initialTab)))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    @Volatile private var controller: AndroidBrowserController? = null
    @Volatile private var sharedWebView: WebView? = null
    private val assistantStreamBuffer = StringBuilder()
    private var lastAssistantStreamUiEmitAt = 0L

    private val sessionId = "sess_android_${UUID.randomUUID().toString().take(8)}"
    private val browserId = "browser_local_001"
    private var parentTaskId = "browser_task_${UUID.randomUUID().toString().take(8)}"
    private var parentStartedAt = BrowserResponseFormatter.nowIso()
    private var parentSummary = "Browser task"
    private val parentSubToolcalls = mutableListOf<JSONObject>()
    private var conversationKey: String? = null

    fun resetForConversation(key: String) {
        if (conversationKey == key) return
        conversationKey = key
        controller?.cancel()
        controller?.resetToBlank()
        parentTaskId = "browser_task_${UUID.randomUUID().toString().take(8)}"
        parentStartedAt = BrowserResponseFormatter.nowIso()
        parentSummary = "Browser task"
        parentSubToolcalls.clear()
        val tab = BrowserPageTab()
        _uiState.value = BrowserUiState(activeTabId = tab.id, tabs = listOf(tab))
    }

    fun resetEphemeral() {
        conversationKey = null
        controller?.cancel()
        controller?.resetToBlank()
        parentSubToolcalls.clear()
        val tab = BrowserPageTab()
        _uiState.value = BrowserUiState(activeTabId = tab.id, tabs = listOf(tab))
    }

    fun acquireSharedWebView(): WebView {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Browser WebView must be acquired on the main thread"
        }
        return ensureSharedControllerOnMain().first
    }

    fun isDispatchingAgentInput(): Boolean = controller?.isDispatchingAgentInput == true

    fun hideSoftKeyboardForAgent() {
        controller?.hideSoftKeyboard()
    }

    fun attachController(controller: AndroidBrowserController) {
        this.controller = controller
        _uiState.update { state ->
            state.copy(
                status = if (state.isPaused) BrowserAgentStatus.PAUSED else BrowserAgentStatus.IDLE,
                currentAction = "Browser ready",
                lastError = null
            )
        }
    }

    fun detachController(controller: AndroidBrowserController) {
        // Keep the shared/headless controller alive so manual browser opens resume
        // the same page instead of showing a blank WebView.
        if (this.controller == null) this.controller = controller
    }

    private fun ensureSharedControllerOnMain(): Pair<WebView, AndroidBrowserController> {
        val existingWebView = sharedWebView
        val existingController = controller
        if (existingWebView != null && existingController != null) return existingWebView to existingController

        val webView = existingWebView ?: WebView(context).also { sharedWebView = it }
        val newController = existingController ?: AndroidBrowserController(
            webView = webView,
            onNavigationChanged = this::onNavigationChanged,
            onError = this::onBrowserError,
            onAgentTouch = this::onAgentTouch
        ).also { controller = it }
        _uiState.update { state ->
            state.copy(
                status = if (state.isPaused) BrowserAgentStatus.PAUSED else BrowserAgentStatus.IDLE,
                currentAction = "Browser ready",
                lastError = null
            )
        }
        return webView to newController
    }

    fun onNavigationChanged(url: String, title: String, progress: Float, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            val activeId = state.activeTabId ?: state.tabs.firstOrNull()?.id
            val tabs = state.tabs.map { tab ->
                if (tab.id == activeId) tab.copy(title = title.ifBlank { url }, url = url, canGoBack = canGoBack, canGoForward = canGoForward) else tab
            }
            state.copy(
                activeUrl = url,
                activeTitle = title.ifBlank { url },
                progress = progress,
                tabs = tabs,
                sessionHistory = (state.sessionHistory + url).distinct().takeLast(60)
            )
        }
    }

    @Synchronized
    fun onAssistantStreamingChanged(streaming: Boolean) {
        if (!streaming) flushAssistantStreamBuffer()
        if (streaming) {
            assistantStreamBuffer.clear()
            lastAssistantStreamUiEmitAt = 0L
            controller?.hideSoftKeyboard()
        }
        _uiState.update { state ->
            val finalizedLogs = if (!streaming) {
                state.logs.map { log ->
                    if (log.toolName.equals("thinking", ignoreCase = true) && log.status == "running") {
                        log.copy(status = "completed")
                    } else log
                }
            } else state.logs
            state.copy(
                logs = finalizedLogs,
                isAssistantStreaming = streaming,
                browserAccessActive = if (streaming) state.browserAccessActive else false,
                agentTouchX = if (streaming) state.agentTouchX else null,
                agentTouchY = if (streaming) state.agentTouchY else null,
                // Clear stream text when a new turn starts so the pill
                // only shows text from the current response, not old text.
                assistantStreamText = if (streaming) "" else state.assistantStreamText,
                assistantStreamUpdatedAt = if (streaming) 0L else state.assistantStreamUpdatedAt
            )
        }
    }

    @Synchronized
    fun onAssistantTextDelta(delta: String) {
        if (delta.isEmpty()) return
        assistantStreamBuffer.append(delta)
        flushAssistantStreamBuffer(System.currentTimeMillis())
    }

    @Synchronized
    private fun flushAssistantStreamBuffer(now: Long = System.currentTimeMillis()) {
        if (assistantStreamBuffer.isEmpty()) return
        val chunk = assistantStreamBuffer.toString()
        assistantStreamBuffer.clear()
        lastAssistantStreamUiEmitAt = now
        _uiState.update { state ->
            state.copy(
                assistantStreamText = (state.assistantStreamText + chunk).takeLast(4000),
                assistantStreamUpdatedAt = now,
                isAssistantStreaming = true
            )
        }
    }

    private fun hasOpenThinkingTag(text: String): Boolean {
        val open = Regex("<think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        val close = Regex("</think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        return open != null && (close == null || open > close)
    }

    fun onAgentTouch(x: Float, y: Float) {
        _uiState.update { state ->
            state.copy(agentTouchX = x, agentTouchY = y, agentTouchNonce = System.currentTimeMillis())
        }
    }

    fun onBrowserError(message: String) {
        _uiState.update { it.copy(status = BrowserAgentStatus.ERROR, lastError = message, currentAction = "Browser error") }
        appendLog("browser", "", "error", message)
    }

    suspend fun executeBrowserTask(arguments: Map<String, Any?>): String {
        _uiState.update { it.copy(browserAccessActive = true) }
        val reset = arguments["reset_task"] == true || parentSubToolcalls.isEmpty()
        if (reset) {
            parentTaskId = arguments["parent_call_id"]?.toString()
                ?: arguments["__toolCallId"]?.toString()
                ?: "browser_task_${UUID.randomUUID().toString().take(8)}"
            parentStartedAt = BrowserResponseFormatter.nowIso()
            parentSubToolcalls.clear()
        }
        arguments["task"]?.toString()?.takeIf { it.isNotBlank() }?.let { parentSummary = it.take(160) }

        val steps = parseSteps(arguments)
        val totalSteps = steps.size.coerceAtLeast(1)
        var lastLabel = "Preparing browser"
        steps.forEachIndexed { index, step ->
            val action = normalizeActionName(step.first)
            val params = step.second
            lastLabel = readableAction(action)
            val sub = executeNormalizedSubtool(
                parentCallId = parentTaskId,
                action = action,
                params = params,
                currentStep = index + 1,
                totalSteps = totalSteps
            )
            parentSubToolcalls += sub
            if (sub.optString("status") in setOf("paused", "cancelled", "error", "timeout")) {
                return parentSnapshot(lastLabel, index + 1, totalSteps).toString(2)
            }
        }
        return parentSnapshot(lastLabel, totalSteps, totalSteps).toString(2)
    }

    private suspend fun executeNormalizedSubtool(
        parentCallId: String,
        action: String,
        params: Map<String, Any?>,
        currentStep: Int,
        totalSteps: Int
    ): JSONObject {
        val started = System.currentTimeMillis()
        val internalName = actionToInternalTool(action)
        _uiState.update { it.copy(progress = currentStep.toFloat() / totalSteps.toFloat(), currentAction = readableAction(action)) }
        val response = when (action) {
            "get_status" -> BrowserToolResponse.Success("Browser status read", currentSessionMetadata())
            "analyze_page" -> execute("get_dom", params)
            else -> execute(internalName, params)
        }
        val duration = System.currentTimeMillis() - started
        return BrowserResponseFormatter.subToolResponse(
            id = BrowserResponseFormatter.newCallId(),
            parentCallId = parentCallId,
            tool = action,
            status = BrowserResponseFormatter.successStatus(response),
            durationMs = duration,
            session = sessionJson(),
            requestParams = params,
            result = BrowserResponseFormatter.resultFor(action, response),
            safety = BrowserResponseFormatter.safetyFor(response),
            summary = BrowserResponseFormatter.summaryFor(action, response),
            agentStatus = BrowserResponseFormatter.agentStatusFor(response),
            error = BrowserResponseFormatter.errorFor(action, response, _uiState.value.activeUrl, params)
        )
    }

    private fun parentSnapshot(label: String, currentStep: Int, totalSteps: Int): JSONObject {
        val lastStatus = parentSubToolcalls.lastOrNull()?.optString("status") ?: "running"
        val parentStatus = when (lastStatus) {
            "paused" -> "paused"
            "cancelled" -> "cancelled"
            "error", "timeout" -> "error"
            else -> if (currentStep >= totalSteps) "completed" else "running"
        }
        return BrowserResponseFormatter.parentTask(
            id = parentTaskId,
            summary = parentSummary,
            status = parentStatus,
            sessionId = sessionId,
            browserId = browserId,
            activePageId = _uiState.value.activeTabId.orEmpty(),
            activeUrl = _uiState.value.activeUrl,
            startedAt = parentStartedAt,
            updatedAt = BrowserResponseFormatter.nowIso(),
            currentStep = currentStep,
            totalSteps = totalSteps,
            label = label,
            subToolcalls = parentSubToolcalls.takeLast(12)
        )
    }

    private fun parseSteps(arguments: Map<String, Any?>): List<Pair<String, Map<String, Any?>>> {
        val rawSteps = arguments["steps"]
        if (rawSteps is Iterable<*>) {
            return rawSteps.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                val action = map["action"]?.toString() ?: return@mapNotNull null
                val params = (map["params"] as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value }
                    ?: map.filterKeys { it != "action" && it != "params" }.entries.associate { it.key.toString() to it.value }
                action to params
            }.ifEmpty { listOf("get_status" to emptyMap()) }
        }
        val action = normalizeActionName(arguments["action"]?.toString() ?: "get_status")
        val topLevelParams = arguments.filterKeys { it !in setOf("task", "action", "params", "steps", "reset_task", "debug", "__toolCallId", "parent_call_id") }
        val nestedParams = (arguments["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        return listOf(action to (topLevelParams + nestedParams))
    }

    private fun normalizeActionName(raw: String): String {
        val name = raw.removePrefix("browser.").trim()
        return when (name) {
            "observe", "analyze_page" -> "get_dom"
            "type" -> "type_text"
            "click_element" -> "click"
            "scroll_page", "swipe" -> "scroll"
            "get_screenshot" -> "screenshot"
            "eval", "evaluate", "execute_script" -> "evaluate_script"
            "reload_page" -> "reload"
            "new_page" -> "new_tab"
            "close_page" -> "close_tab"
            else -> name
        }
    }

    private fun actionToInternalTool(action: String): String = when (action) {
        "new_tab" -> "new_page"
        "close_tab" -> "close_page"
        "click" -> "click_element"
        "scroll" -> "scroll_page"
        "screenshot" -> "get_screenshot"
        "reload" -> "reload_page"
        "switch_tab" -> "switch_tab"
        else -> action
    }

    private fun sessionJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("browser_id", browserId)
        put("active_page_id", _uiState.value.activeTabId ?: "")
    }

    private fun currentSessionMetadata(): Map<String, Any> = mapOf(
        "url" to _uiState.value.activeUrl,
        "title" to _uiState.value.activeTitle,
        "session_id" to sessionId,
        "browser_id" to browserId,
        "active_page_id" to (_uiState.value.activeTabId ?: ""),
        "tabs" to _uiState.value.tabs.map { mapOf("id" to it.id, "title" to it.title, "url" to it.url) },
        "status" to _uiState.value.status.name.lowercase()
    )

    suspend fun execute(toolName: String, arguments: Map<String, Any?>): BrowserToolResponse {
        if (toolName != "resume_session" && toolName != "cancel_action" && _uiState.value.isPaused) {
            return BrowserToolResponse.Failure("Browser session is paused. Ask the user to resume_session or fill the form manually.")
        }

        val argsPreview = arguments
            .filterKeys { !it.startsWith("__") && it != "text" }
            .entries.joinToString { "${it.key}=${it.value}" }
            .take(180)
        appendLog(toolName, argsPreview, "running", "Starting")
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.BROWSING,
                currentAction = readableAction(toolName),
                inspectedElement = (arguments["selector"] ?: arguments["query"])?.toString(),
                lastError = null,
                isCancelled = false
            )
        }

        val result = try {
            when (toolName) {
                "cancel_action" -> cancelAction()
                "pause_session" -> pauseSession("Paused by user or agent")
                "resume_session" -> resumeSession()
                else -> executeBrowserTool(toolName, arguments)
            }
        } catch (e: Exception) {
            BrowserToolResponse.Failure("${toolName} failed: ${e.message ?: e::class.java.simpleName}")
        }

        when (result) {
            is BrowserToolResponse.Success -> {
                appendLog(toolName, argsPreview, "success", result.output.take(220))
                val image = result.metadata["image_base64"] as? String
                _uiState.update {
                    it.copy(
                        status = if (toolName == "pause_session") BrowserAgentStatus.PAUSED else BrowserAgentStatus.IDLE,
                        currentAction = "Completed ${readableAction(toolName)}",
                        screenshotBase64 = image ?: it.screenshotBase64,
                        lastError = null
                    )
                }
            }
            is BrowserToolResponse.Failure -> {
                appendLog(toolName, argsPreview, "error", result.message)
                _uiState.update {
                    it.copy(
                        status = if (result.recoverable) BrowserAgentStatus.ERROR else BrowserAgentStatus.CANCELLED,
                        currentAction = "Failed ${readableAction(toolName)}",
                        lastError = result.message
                    )
                }
            }
            is BrowserToolResponse.SafetyPause -> {
                appendLog(toolName, argsPreview, "paused", result.prompt.reason)
                _uiState.update {
                    it.copy(
                        status = BrowserAgentStatus.WAITING_INPUT,
                        currentAction = "Waiting for user decision",
                        safetyPrompt = result.prompt,
                        isPaused = true
                    )
                }
            }
        }
        return result
    }

    fun userManualInput() {
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.WAITING_INPUT,
                currentAction = "User is filling sensitive input manually",
                safetyPrompt = null,
                isPaused = true
            )
        }
        appendLog("human_input", "", "waiting", "User chose manual input")
    }

    fun skipSensitiveStep() {
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.PAUSED,
                currentAction = "Sensitive step skipped",
                safetyPrompt = null,
                isPaused = true
            )
        }
        appendLog("safety", "", "skipped", "User skipped sensitive step")
    }

    fun allowSensitiveAndResume() {
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.IDLE,
                currentAction = "Sensitive action allowed for this step",
                safetyPrompt = null,
                isPaused = false
            )
        }
        appendLog("safety", "", "allowed", "User allowed agent to continue")
    }

    fun cancelFromUser() {
        controller?.cancel()
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.CANCELLED,
                currentAction = "Cancelled by user",
                safetyPrompt = null,
                isPaused = false,
                isCancelled = true
            )
        }
        appendLog("cancel_action", "", "cancelled", "Cancelled by user")
    }

    private suspend fun executeBrowserTool(toolName: String, arguments: Map<String, Any?>): BrowserToolResponse {
        val controller = ensureController()
            ?: return BrowserToolResponse.Failure("Browser UI is not ready. Open AI Browser Operator and retry.")
        val forceSensitive = arguments["allow_sensitive"] == true || arguments["__confirmed"] == true
        if (forceSensitive) allowSensitiveAndResume()

        suspend fun guardSelector(selector: String): BrowserToolResponse? {
            val element = controller.inspectElement(selector)
            if (!forceSensitive && safetyGuard.isSensitiveField(element)) {
                return BrowserToolResponse.SafetyPause(safetyGuard.buildPrompt(toolName, element))
            }
            return null
        }

        return when (toolName) {
            "open_url" -> {
                val url = arguments["url"]?.toString() ?: return BrowserToolResponse.Failure("Missing url")
                controller.openUrl(url, longArg(arguments, "timeout_ms", 30_000)).also { updateTabAfterNavigation(controller) }
            }
            "new_page" -> {
                createTab(arguments["url"]?.toString())
                controller.newPage(arguments["url"]?.toString()).also { updateTabAfterNavigation(controller) }
            }
            "close_page" -> closeActiveTab(controller)
            "switch_tab" -> switchTab(controller, arguments["page_id"]?.toString() ?: arguments["tab_id"]?.toString())
            "click_element" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for click. Use element_id from interactive_elements.")
                guardSelector(selector) ?: run {
                    val clicked = controller.click(selector).also { updateTabAfterNavigation(controller) }
                    if (clicked is BrowserToolResponse.Failure && clicked.message.contains("not found", ignoreCase = true)) {
                        domBackedFailure(controller, "Element not found for click target: $selector")
                    } else clicked
                }
            }
            "tap" -> controller.tap(
                intArg(arguments, "x", -1),
                intArg(arguments, "y", -1),
                selectorArg(arguments)
            ).also { updateTabAfterNavigation(controller) }
            "swipe" -> controller.swipe(
                arguments["direction"]?.toString(),
                floatArg(arguments, "distance", 0.65f),
                intArg(arguments, "start_x", -1),
                intArg(arguments, "start_y", -1),
                intArg(arguments, "end_x", -1),
                intArg(arguments, "end_y", -1),
                longArg(arguments, "duration_ms", 420)
            )
            "focus" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for focus. Use element_id from interactive_elements.")
                guardSelector(selector) ?: controller.focus(selector)
            }
            "press_key" -> controller.pressKey(arguments["key"]?.toString() ?: arguments["text"]?.toString() ?: "ENTER").also { updateTabAfterNavigation(controller) }
            "search" -> {
                val text = arguments["query"]?.toString() ?: arguments["text"]?.toString()
                    ?: return domBackedFailure(controller, "Missing query/text for search.")
                val selector = selectorArg(arguments)
                if (selector != null) guardSelector(selector)?.let { return it }
                controller.search(text, selector).also { updateTabAfterNavigation(controller) }
            }
            "type_text" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for type_text. Use element_id from interactive_elements.")
                val text = arguments["text"]?.toString() ?: return BrowserToolResponse.Failure("Missing text for type_text")
                guardSelector(selector) ?: run {
                    val typed = controller.typeText(selector, text, boolArg(arguments, "append", true))
                    if (typed is BrowserToolResponse.Success && boolArg(arguments, "submit", false)) {
                        controller.submitFromContext(selector).also { updateTabAfterNavigation(controller) }
                    } else typed
                }
            }
            "clear_input" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for clear_input. Use element_id from interactive_elements.")
                guardSelector(selector) ?: controller.clearInput(selector)
            }
            "scroll_page" -> {
                val direction = arguments["direction"]?.toString()
                if (!direction.isNullOrBlank()) {
                    val distance = when (arguments["amount"]?.toString()?.lowercase()) {
                        "small" -> 0.18f
                        "large" -> 0.42f
                        else -> floatArg(arguments, "distance", 0.28f)
                    }
                    controller.swipe(direction, distance, -1, -1, -1, -1, longArg(arguments, "duration_ms", 360))
                } else {
                    controller.scrollPage(intArg(arguments, "delta_x", 0), intArg(arguments, "delta_y", 800))
                }
            }
            "get_dom" -> controller.getDom()
            "get_visible_text" -> controller.getVisibleText()
            "get_screenshot" -> controller.screenshot()
            "evaluate_script" -> {
                val script = arguments["script"]?.toString() ?: arguments["text"]?.toString()
                    ?: return BrowserToolResponse.Failure("Missing script for evaluate_script")
                controller.evaluateScript(script, intArg(arguments, "max_chars", 4000))
            }
            "find_element" -> {
                val query = queryArg(arguments)
                if (query == null) {
                    return domBackedFailure(controller, "Missing query for find_element. Call get_dom or provide query/text/label/target.")
                }
                val found = controller.findElement(query)
                if (found is BrowserToolResponse.Failure && found.message.contains("not found", ignoreCase = true)) {
                    domBackedFailure(controller, "Element not found for query: $query")
                } else found
            }
            "wait_for_element" -> {
                val query = queryArg(arguments)
                    ?: return domBackedFailure(controller, "Missing query for wait_for_element. Provide query/text/label/target.")
                val found = controller.waitForElement(query, longArg(arguments, "timeout_ms", 10_000))
                if (found is BrowserToolResponse.Failure && found.message.contains("Timed out", ignoreCase = true)) {
                    domBackedFailure(controller, "Timed out waiting for element: $query")
                } else found
            }
            "go_back" -> controller.goBack().also { updateTabAfterNavigation(controller) }
            "go_forward" -> controller.goForward().also { updateTabAfterNavigation(controller) }
            "reload_page" -> controller.reload().also { updateTabAfterNavigation(controller) }
            else -> BrowserToolResponse.Failure("Unknown browser tool: $toolName")
        }
    }

    private suspend fun ensureController(): AndroidBrowserController? {
        controller?.let { return it }
        return withContext(Dispatchers.Main.immediate) {
            ensureSharedControllerOnMain().second
        }
    }

    private fun createTab(url: String?) {
        val tab = BrowserPageTab(title = "New Page", url = url ?: "about:blank")
        _uiState.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
    }

    private suspend fun switchTab(controller: AndroidBrowserController, pageId: String?): BrowserToolResponse {
        val target = _uiState.value.tabs.firstOrNull { it.id == pageId }
            ?: return BrowserToolResponse.Failure("Tab not found: ${pageId ?: "missing page_id"}")
        _uiState.update { it.copy(activeTabId = target.id) }
        return controller.openUrl(target.url).also { updateTabAfterNavigation(controller) }
    }

    private suspend fun closeActiveTab(controller: AndroidBrowserController): BrowserToolResponse {
        val state = _uiState.value
        val active = state.activeTabId
        val remaining = state.tabs.filterNot { it.id == active }
        return if (remaining.isEmpty()) {
            val newTab = BrowserPageTab()
            _uiState.update { it.copy(tabs = listOf(newTab), activeTabId = newTab.id) }
            controller.closePage()
        } else {
            val next = remaining.last()
            _uiState.update { it.copy(tabs = remaining, activeTabId = next.id) }
            controller.openUrl(next.url).also { updateTabAfterNavigation(controller) }
        }
    }

    private suspend fun domBackedFailure(controller: AndroidBrowserController, message: String): BrowserToolResponse.Failure {
        val dom = controller.getDom()
        val metadata = if (dom is BrowserToolResponse.Success) mapOf("dom" to (dom.metadata["dom"] ?: dom.output)) else emptyMap()
        return BrowserToolResponse.Failure(message, recoverable = true, metadata = metadata)
    }

    private suspend fun updateTabAfterNavigation(controller: AndroidBrowserController) {
        withContext(Dispatchers.Main.immediate) {
            val metadata = controller.currentMetadata()
            onNavigationChanged(
                controller.currentUrl(),
                controller.currentTitle(),
                1f,
                metadata["can_go_back"] == true,
                metadata["can_go_forward"] == true
            )
        }
    }

    private fun pauseSession(message: String): BrowserToolResponse {
        _uiState.update { it.copy(status = BrowserAgentStatus.PAUSED, isPaused = true, currentAction = message) }
        return BrowserToolResponse.Success(message)
    }

    private fun resumeSession(): BrowserToolResponse {
        _uiState.update { it.copy(status = BrowserAgentStatus.IDLE, isPaused = false, safetyPrompt = null, currentAction = "Session resumed") }
        return BrowserToolResponse.Success("Browser session resumed")
    }

    private fun cancelAction(): BrowserToolResponse {
        cancelFromUser()
        return BrowserToolResponse.Success("Browser action cancelled")
    }

    private fun appendLog(toolName: String, args: String, status: String, message: String) {
        flushAssistantStreamBuffer()
        _uiState.update { state ->
            val existingRunningIndex = state.logs.indexOfFirst {
                it.toolName == toolName &&
                    it.argumentsPreview == args &&
                    it.status == "running" &&
                    status != "running"
            }

            // Snapshot accumulated assistant text as a separate log entry
            // before adding a new tool, creating natural text↔tool interleaving.
            val streamSnap = state.assistantStreamText.trim()
            val snapshotLog = if (streamSnap.isNotBlank() && existingRunningIndex < 0 && status == "running") {
                val isThinking = hasOpenThinkingTag(streamSnap)
                listOf(BrowserToolLog(
                    toolName = if (isThinking) "thinking" else "assistant",
                    argumentsPreview = "",
                    status = if (isThinking) "running" else "completed",
                    message = streamSnap,
                    timestamp = System.currentTimeMillis() - 1
                ))
            } else emptyList()

            val updatedLogs = if (existingRunningIndex >= 0) {
                state.logs.toMutableList().also { logs ->
                    val current = logs[existingRunningIndex]
                    logs[existingRunningIndex] = current.copy(status = status, message = message)
                }
            } else {
                listOf(BrowserToolLog(toolName = toolName, argumentsPreview = args, status = status, message = message)) + snapshotLog + state.logs
            }
            state.copy(
                logs = updatedLogs.take(120),
                // Clear the stream so subsequent text becomes a new segment
                assistantStreamText = if (snapshotLog.isNotEmpty()) "" else state.assistantStreamText,
                assistantStreamUpdatedAt = if (snapshotLog.isNotEmpty()) 0L else state.assistantStreamUpdatedAt
            )
        }
    }

    private fun readableAction(toolName: String): String = toolName.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    private fun selectorArg(arguments: Map<String, Any?>): String? = firstString(arguments, "element_id", "target", "selector", "query", "id")
    private fun queryArg(arguments: Map<String, Any?>): String? = firstString(arguments, "query", "text", "label", "name", "target", "selector", "element_id")
    private fun firstString(arguments: Map<String, Any?>, vararg keys: String): String? {
        keys.forEach { key ->
            val value = arguments[key]?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }
    private fun intArg(arguments: Map<String, Any?>, key: String, default: Int): Int = (arguments[key] as? Number)?.toInt() ?: arguments[key]?.toString()?.toIntOrNull() ?: default
    private fun longArg(arguments: Map<String, Any?>, key: String, default: Long): Long = (arguments[key] as? Number)?.toLong() ?: arguments[key]?.toString()?.toLongOrNull() ?: default
    private fun boolArg(arguments: Map<String, Any?>, key: String, default: Boolean): Boolean = arguments[key] as? Boolean ?: arguments[key]?.toString()?.toBooleanStrictOrNull() ?: default
    private fun floatArg(arguments: Map<String, Any?>, key: String, default: Float): Float = (arguments[key] as? Number)?.toFloat() ?: arguments[key]?.toString()?.toFloatOrNull() ?: default

}
