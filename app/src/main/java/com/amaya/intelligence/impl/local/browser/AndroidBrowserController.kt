package com.amaya.intelligence.impl.local.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

class AndroidBrowserController(
    internal val geckoView: GeckoView,
    val session: GeckoSession,
    internal val onNavigationChanged: (url: String, title: String, progress: Float, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    internal val onError: (String) -> Unit,
    internal val onScrollChanged: (x: Int, y: Int) -> Unit = { _, _ -> },
    internal val onAgentTouch: (x: Float, y: Float) -> Unit = { _, _ -> },
    internal val capturePixels: () -> GeckoResult<Bitmap> = geckoView::capturePixels,
    internal val onDownload: (response: WebResponse) -> Unit = {},
    internal val onFileChooser: (acceptTypes: Array<String>, multiple: Boolean, callback: (Array<Uri>?) -> Unit) -> Unit = { _, _, callback -> callback(null) },
    internal val onProcessGone: (lastUrl: String) -> Unit = {}
) {
    @Volatile var isDispatchingAgentInput: Boolean = false
        internal set
    @Volatile internal var pageFinished = false
    @Volatile internal var pageLoadSucceeded = true
    @Volatile internal var navigationGeneration = 0L
    @Volatile internal var cancelled = false
    @Volatile internal var currentUrlValue = "about:blank"
    @Volatile internal var currentTitleValue = "AI Browser Operator"
    @Volatile internal var canGoBackValue = false
    @Volatile internal var canGoForwardValue = false
    @Volatile internal var progressValue = 0f
    @Volatile internal var externalResponseVersion = 0L
    @Volatile internal var filePromptVersion = 0L
    @Volatile internal var popupVersion = 0L
    @Volatile internal var visibleFileChooserHost = false

    init {
        configureGecko()
    }

    fun setVisibleFileChooserHost(visible: Boolean) { visibleFileChooserHost = visible }

    internal fun handleContentProcessGone(session: GeckoSession, reason: String) {
        Log.w("AmayaBrowser", "content process $reason session=${session.hashCode()} urlHost=${Uri.parse(currentUrlValue).host.orEmpty()}")
        pageFinished = true
        pageLoadSucceeded = false
        GeckoBrowserRuntime.markProcessGone(session)
        onProcessGone(currentUrlValue)
    }

    internal fun configureGecko() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                popupVersion++
                onError("Popup blocked: $uri. Use the current page or Browser Operator for manual confirmation.")
                return null
            }

            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                currentUrlValue = url ?: "about:blank"
                emitNavigation()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { canGoBackValue = canGoBack; emitNavigation() }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { canGoForwardValue = canGoForward; emitNavigation() }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                // Publish loading state before failing pending JS. The failure resumes
                // click/search coroutines synchronously; they must not observe the old
                // document as already finished.
                pageFinished = false
                pageLoadSucceeded = true
                navigationGeneration++
                currentUrlValue = url
                progressValue = 0f
                emitNavigation()
                GeckoBrowserRuntime.navigationStarted(session)
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { progressValue = progress / 100f; emitNavigation() }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                pageLoadSucceeded = success
                pageFinished = true
                progressValue = 1f
                if (!success) onError("Network/browser error while loading $currentUrlValue")
                emitNavigation()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { currentTitleValue = title ?: currentUrlValue; emitNavigation() }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                externalResponseVersion++
                onDownload(response)
            }
            // Android reclaims Gecko's content process within seconds of the app leaving the
            // foreground. The session object stays usable-looking but can never run script
            // again, so publish the death instead of letting later actions wait it out.
            override fun onKill(session: GeckoSession) = handleContentProcessGone(session, "killed")
            override fun onCrash(session: GeckoSession) = handleContentProcessGone(session, "crashed")
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { onScrollChanged(scrollX, scrollY) }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                filePromptVersion++
                val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                onFileChooser(prompt.mimeTypes ?: emptyArray(), multiple) { uris ->
                    val response = if (uris.isNullOrEmpty()) prompt.dismiss()
                    else if (uris.size == 1) prompt.confirm(geckoView.context, uris[0])
                    else prompt.confirm(geckoView.context, uris)
                    geckoView.post { result.complete(response) }
                }
                return result
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback) {
                callback.reject()
                onError("Website requested Android permission (${permissions.orEmpty().joinToString()}). Permission was blocked until the user allows it manually.")
            }
            override fun onContentPermissionRequest(session: GeckoSession, permission: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
    }

    suspend fun openUrl(rawUrl: String, timeoutMs: Long = 30_000): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val url = normalizeUrl(rawUrl)
        cancelled = false
        pageFinished = false
        session.loadUri(url)
        if (url == "about:blank") BrowserToolResponse.Success("Opened new tab", currentMetadata()) else waitForPage(timeoutMs)
    }

    suspend fun newPage(rawUrl: String?): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val url = normalizeUrl(rawUrl?.takeIf { it.isNotBlank() } ?: "about:blank")
        cancelled = false
        pageFinished = false
        session.loadUri(url)
        if (url == "about:blank") BrowserToolResponse.Success("Opened new tab", currentMetadata()) else waitForPage(30_000)
    }

    suspend fun closePage(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        session.loadUri("about:blank")
        BrowserToolResponse.Success("Closed active page", currentMetadata())
    }

    fun cancel() {
        cancelled = true
        suppressSoftKeyboard()
        session.stop()
    }

    fun resetToBlank() {
        cancelled = true
        pageFinished = true
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            session.stop()
            session.loadUri("about:blank")
        } else {
            geckoView.post {
                session.stop()
                session.loadUri("about:blank")
            }
        }
    }

    fun hideSoftKeyboard() {
        suppressSoftKeyboard()
    }

    fun currentUrl(): String = currentUrlValue
    fun currentTitle(): String = currentTitleValue
    fun currentMetadata(): Map<String, Any> = mapOf(
        "url" to currentUrl(),
        "title" to currentTitle(),
        "can_go_back" to canGoBackValue,
        "can_go_forward" to canGoForwardValue
    )

    internal suspend fun waitForHistoryNavigation(beforeUrl: String, beforeNavigation: Long): BrowserToolResponse = try {
        withTimeout(15_000) {
            var changedAt = 0L
            while (!cancelled) {
                val changed = currentUrl() != beforeUrl
                if (changed && changedAt == 0L) changedAt = SystemClock.uptimeMillis()
                // Full document navigation: wait for onPageStop. SPA history has no
                // page-start callback; give its state/title callbacks one settle turn.
                if (navigationGeneration != beforeNavigation) {
                    if (pageFinished) break
                } else if (changed && SystemClock.uptimeMillis() - changedAt >= 300L) {
                    break
                }
                delay(50)
            }
        }
        when {
            cancelled -> BrowserToolResponse.Failure("Action cancelled", recoverable = false)
            currentUrl() == beforeUrl -> BrowserToolResponse.Failure("History navigation timed out. You can retry or inspect the current page.")
            !pageFinished && navigationGeneration != beforeNavigation -> BrowserToolResponse.Failure("History navigation timed out. You can retry or inspect the partially loaded page.")
            !pageLoadSucceeded -> BrowserToolResponse.Failure("Network/browser error while loading ${currentUrl()}")
            else -> {
                refreshDocumentTitle()
                BrowserToolResponse.Success("Loaded ${currentUrl()}", currentMetadata())
            }
        }
    } catch (_: TimeoutCancellationException) {
        BrowserToolResponse.Failure("History navigation timed out. You can retry or inspect the current page.")
    }

    internal suspend fun waitForPage(timeoutMs: Long): BrowserToolResponse {
        return try {
            withTimeout(timeoutMs) {
                while (!pageFinished && !cancelled) delay(75)
            }
            when {
                cancelled -> BrowserToolResponse.Failure("Action cancelled", recoverable = false)
                !pageLoadSucceeded -> BrowserToolResponse.Failure("Network/browser error while loading ${currentUrl()}")
                else -> {
                    GeckoBrowserRuntime.awaitReady(session, minOf(8_000L, timeoutMs / 2))
                    refreshDocumentTitle()
                    BrowserToolResponse.Success("Loaded ${currentUrl()}", currentMetadata())
                }
            }
        } catch (_: TimeoutCancellationException) {
            BrowserToolResponse.Failure("Page load timed out. You can retry, reload, or inspect the partially loaded page.")
        }
    }

    internal suspend fun refreshDocumentTitle() {
        repeat(3) { attempt ->
            val title = runCatching { evaluateString("document.title", 3_000) }.getOrNull().orEmpty()
            if (title.isNotBlank()) {
                currentTitleValue = title
                emitNavigation()
                return
            }
            if (attempt < 2) delay(250L * (attempt + 1))
        }
    }

    internal fun isTransientBridgeError(error: Throwable): Boolean = error is TimeoutCancellationException ||
        error.message.orEmpty().let { message ->
            message.contains("bridge", ignoreCase = true) ||
                message.contains("document navigated", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true)
        }

    internal suspend fun resolveCenterPoint(selector: String): Pair<Float, Float>? {
        val obj = runCatching { org.json.JSONObject(evaluateJson(DomInspector.boundsScript(selector))) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) return null
        // getBoundingClientRect() returns CSS viewport pixels; convert to Android view
        // pixels by multiplying with the GeckoView's display density.
        @Suppress("DEPRECATION")
        val scale = geckoView.resources.displayMetrics.density
        return (obj.optDouble("center_x").toFloat() * scale) to (obj.optDouble("center_y").toFloat() * scale)
    }

    internal fun isPointInsideView(x: Float, y: Float): Boolean {
        val width = geckoView.width.toFloat().coerceAtLeast(1f)
        val height = geckoView.height.toFloat().coerceAtLeast(1f)
        return x in 0f..width && y in 0f..height
    }

    internal fun hasClickEffect(before: JSONObject?, after: JSONObject?): Boolean {
        if (before == null || after == null) return false
        return before.optBoolean("focused") != after.optBoolean("focused") ||
            before.optBoolean("checked") != after.optBoolean("checked") ||
            before.optBoolean("expanded") != after.optBoolean("expanded") ||
            before.optLong("mutation_version") != after.optLong("mutation_version") ||
            before.optString("value") != after.optString("value") ||
            before.optString("text") != after.optString("text")
    }

    internal suspend fun directOpenHref(href: String, beforeUrl: String, beforeTitle: String, strategy: String): BrowserToolResponse {
        if (isEmbeddedAppRoute(href)) {
            return BrowserToolResponse.Failure("Refusing direct navigation to an embedded app route; retry the native click", recoverable = true)
        }
        cancelled = false
        pageFinished = false
        session.loadUri(href)
        return when (val result = waitForPage(15_000)) {
            is BrowserToolResponse.Success -> result.copy(
                output = "Opened target URL directly; page_changed=${beforeUrl != currentUrl() || beforeTitle != currentTitle()}",
                metadata = result.metadata + mapOf(
                    "page_changed" to (beforeUrl != currentUrl() || beforeTitle != currentTitle()),
                    "before_url" to beforeUrl,
                    "before_title" to beforeTitle,
                    "after_url" to currentUrl(),
                    "after_title" to currentTitle(),
                    "opened_url" to href,
                    "strategy" to strategy
                )
            )
            is BrowserToolResponse.Failure -> result.copy(
                metadata = result.metadata + mapOf(
                    "before_url" to beforeUrl,
                    "before_title" to beforeTitle,
                    "attempted_url" to href,
                    "strategy" to strategy
                )
            )
            else -> result
        }
    }

    internal fun dispatchTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(90L)
        onAgentTouch(x, y)
        isDispatchingAgentInput = true
        try {
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).also { event ->
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                geckoView.onTouchEvent(event)
                geckoView.onTouchEventForDetailResult(event).accept { detail ->
                    Log.d("AmayaBrowser", "tap down handled=${detail?.handledResult()}")
                }
                event.recycle()
            }
            MotionEvent.obtain(downTime, downTime + tapTimeout, MotionEvent.ACTION_UP, x, y, 0).also { event ->
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                geckoView.onTouchEvent(event)
                geckoView.onTouchEventForDetailResult(event).accept { detail ->
                    Log.d("AmayaBrowser", "tap up handled=${detail?.handledResult()}")
                }
                event.recycle()
            }
        } finally {
            isDispatchingAgentInput = false
        }
    }

    internal fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val safeDuration = durationMs.coerceIn(120, 2200)
        val distance = kotlin.math.hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
        val steps = (distance / 80f).toInt().coerceIn(6, 18)
        onAgentTouch(startX, startY)
        isDispatchingAgentInput = true
        try {
            geckoView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0))
            for (i in 1 until steps) {
                val linearT = i.toFloat() / steps.toFloat()
                val easedT = (1f - kotlin.math.cos(linearT * Math.PI).toFloat()) / 2f
                val eventTime = downTime + (safeDuration * linearT).toLong()
                val x = startX + (endX - startX) * easedT
                val y = startY + (endY - startY) * easedT
                onAgentTouch(x, y)
                geckoView.dispatchTouchEvent(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0))
            }
            geckoView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + safeDuration, MotionEvent.ACTION_UP, endX, endY, 0))
            onAgentTouch(endX, endY)
        } finally {
            isDispatchingAgentInput = false
        }
    }

    internal suspend fun evaluateJson(script: String, timeoutMs: Long = 10_000): String = evaluateString(script, timeoutMs).ifBlank { "{}" }

    internal suspend fun evaluateString(script: String, timeoutMs: Long = 10_000): String = withContext(Dispatchers.Main.immediate) {
        GeckoBrowserRuntime.evaluate(session, script, timeoutMs)
    }

    internal suspend fun nativeTypeText(selector: String?, text: String, append: Boolean): BrowserToolResponse {
        if (selector != null) {
            val focused = jsOkResponse(evaluateJson(DomInspector.focusScript(selector)), "Focused input")
            if (focused is BrowserToolResponse.Failure) return focused
        }
        if (!append && selector != null) evaluateJson(DomInspector.clearScript(selector))
        geckoView.requestFocus()
        val imm = geckoView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(geckoView)
        val connection = geckoView.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ?: return BrowserToolResponse.Failure("GeckoView input connection unavailable")
        connection.beginBatchEdit()
        val committed = connection.commitText(text, 1)
        connection.endBatchEdit()
        return if (committed) BrowserToolResponse.Success("Typed ${text.length} characters", currentMetadata() + mapOf("strategy" to "geckoview_input_connection"))
        else BrowserToolResponse.Failure("GeckoView rejected text input")
    }

    internal fun suppressSoftKeyboard() {
        val imm = geckoView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        geckoView.post {
            imm?.hideSoftInputFromWindow(geckoView.windowToken, 0)
        }
    }

    internal suspend fun readElementState(selector: String?): JSONObject? {
        val json = evaluateJson(DomInspector.elementStateScript(selector))
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return obj.takeIf { it.optBoolean("ok", false) }
    }

    internal fun textApplied(before: JSONObject?, after: JSONObject?, text: String, append: Boolean): Boolean {
        val afterValue = after?.optString("value")?.trim().orEmpty()
        val afterText = after?.optString("text")?.trim().orEmpty()
        if (afterValue.isBlank() && afterText.isBlank()) return false
        if (!append) return afterValue == text || afterText.contains(text)
        val beforeValue = before?.optString("value")?.trim().orEmpty()
        return afterValue.length >= beforeValue.length + text.length || afterValue.endsWith(text) || afterText.contains(text)
    }

    internal fun isEffectivelyEmpty(state: JSONObject?): Boolean {
        if (state == null) return false
        return state.optString("value").isBlank() && state.optString("text").isBlank()
    }

    internal fun jsOkResponse(json: String, successMessage: String): BrowserToolResponse {
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return BrowserToolResponse.Failure("Unexpected browser response: $json")
        return if (obj.optBoolean("ok", false)) {
            BrowserToolResponse.Success(successMessage, currentMetadata() + ("element" to obj.optString("element", json)))
        } else {
            val metadata = buildMap<String, Any> {
                obj.opt("element")?.takeIf { it != org.json.JSONObject.NULL }?.let { put("element", it.toString()) }
                obj.opt("blocker")?.takeIf { it != org.json.JSONObject.NULL }?.let { put("blocker", it.toString()) }
            }
            BrowserToolResponse.Failure(obj.optString("error", "Browser action failed"), metadata = metadata)
        }
    }

    internal suspend fun <T> geckoResult(result: GeckoResult<T>): T = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        result.then<Void>({ value: T? ->
            if (value == null) continuation.cancel(IllegalStateException("Gecko operation returned null"))
            else if (continuation.isActive) continuation.resume(value) { _, _, _ -> }
            null
        }, { error: Throwable ->
            if (continuation.isActive) continuation.cancel(error)
            null
        })
    }

    internal suspend fun waitForDomReady(timeoutMs: Long) {
        val started = SystemClock.uptimeMillis()
        var stableCount = 0
        while (SystemClock.uptimeMillis() - started < timeoutMs && !cancelled) {
            val ready = evaluateString("document.readyState")
            if (ready == "complete" || ready == "interactive") {
                stableCount += 1
                if (stableCount >= 2) return
            } else {
                stableCount = 0
            }
            delay(90)
        }
    }

    internal suspend fun waitForInteractionSettle(beforeUrl: String, beforeTitle: String, timeoutMs: Long = 1_400): Boolean {
        val started = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - started < timeoutMs && !cancelled) {
            val changed = currentUrl() != beforeUrl || currentTitle() != beforeTitle
            if (changed) {
                waitForDomReady(1_200)
                return true
            }
            val ready = evaluateString("document.readyState")
            if (ready == "complete" || ready == "interactive") break
            delay(90)
        }
        return currentUrl() != beforeUrl || currentTitle() != beforeTitle
    }

    internal suspend fun currentScrollSnapshot(): String = evaluateString("JSON.stringify({x:window.scrollX,y:window.scrollY,h:(document.body?document.body.scrollHeight:0),ready:document.readyState})")

    internal suspend fun viewportSnapshot(): String = evaluateString("JSON.stringify({innerWidth:innerWidth,innerHeight:innerHeight,outerWidth:outerWidth,outerHeight:outerHeight,devicePixelRatio:devicePixelRatio,scrollX:scrollX,scrollY:scrollY,visualViewport:visualViewport?{width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale,offsetTop:visualViewport.offsetTop,offsetLeft:visualViewport.offsetLeft}:null,documentWidth:document.documentElement?document.documentElement.clientWidth:0,documentHeight:document.documentElement?document.documentElement.clientHeight:0,bodyHeight:document.body?document.body.scrollHeight:0})")

    internal fun logViewport(stage: String) { Log.d("AmayaBrowser", "$stage urlHost=${Uri.parse(currentUrlValue).host.orEmpty()} view=${geckoView.width}x${geckoView.height}") }

    internal suspend fun waitForScrollSettled(timeoutMs: Long) {
        val started = SystemClock.uptimeMillis()
        var last = ""
        var stableCount = 0
        while (SystemClock.uptimeMillis() - started < timeoutMs && !cancelled) {
            val current = currentScrollSnapshot()
            if (current == last) {
                stableCount += 1
                if (stableCount >= 2) return
            } else {
                stableCount = 0
                last = current
            }
            delay(90)
        }
    }

    internal fun emitNavigation() { onNavigationChanged(currentUrlValue, currentTitleValue, progressValue, canGoBackValue, canGoForwardValue) }

    internal fun clickOutcomeMetadata(
        beforeUrl: String,
        beforeTitle: String,
        pageChanged: Boolean,
        beforePopup: Long,
        afterPopup: Long,
        eventObserved: Boolean
    ): Map<String, Any> = mapOf(
        "outcome" to if (pageChanged) "navigation" else if (eventObserved) "event_or_state_change" else "no_observable_effect",
        "event_observed" to eventObserved,
        "popup_blocked" to (afterPopup != beforePopup),
        "page_changed" to pageChanged,
        "before_url" to beforeUrl,
        "before_title" to beforeTitle,
        "after_url" to currentUrl(),
        "after_title" to currentTitle(),
        "requires_postcondition_check" to true
    )

    internal fun isEmbeddedAppRoute(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        uri.host.equals("x.com", true) && uri.path.orEmpty().startsWith("/i/jf/")
    }.getOrDefault(false)

    internal fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "about:blank") return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
