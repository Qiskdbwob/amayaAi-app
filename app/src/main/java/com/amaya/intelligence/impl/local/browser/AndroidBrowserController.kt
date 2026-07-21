package com.amaya.intelligence.impl.local.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.view.KeyEvent
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
import java.io.ByteArrayOutputStream

class AndroidBrowserController(
    private val geckoView: GeckoView,
    val session: GeckoSession,
    private val onNavigationChanged: (url: String, title: String, progress: Float, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onScrollChanged: (x: Int, y: Int) -> Unit = { _, _ -> },
    private val onAgentTouch: (x: Float, y: Float) -> Unit = { _, _ -> },
    private val onDownload: (response: WebResponse) -> Unit = {},
    private val onFileChooser: (acceptTypes: Array<String>, multiple: Boolean, callback: (Array<Uri>?) -> Unit) -> Unit = { _, _, callback -> callback(null) }
) {
    @Volatile var isDispatchingAgentInput: Boolean = false
        private set
    @Volatile private var pageFinished = false
    @Volatile private var pageLoadSucceeded = true
    @Volatile private var cancelled = false
    @Volatile private var currentUrlValue = "about:blank"
    @Volatile private var currentTitleValue = "AI Browser Operator"
    @Volatile private var canGoBackValue = false
    @Volatile private var canGoForwardValue = false
    @Volatile private var progressValue = 0f
    @Volatile private var externalResponseVersion = 0L

    init {
        configureGecko()
    }

    private fun configureGecko() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                currentUrlValue = url ?: "about:blank"
                emitNavigation()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { canGoBackValue = canGoBack; emitNavigation() }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { canGoForwardValue = canGoForward; emitNavigation() }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                GeckoBrowserRuntime.navigationStarted(session)
                pageFinished = false
                pageLoadSucceeded = true
                currentUrlValue = url
                progressValue = 0f
                emitNavigation()
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
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { onScrollChanged(scrollX, scrollY) }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
                onFileChooser(prompt.mimeTypes ?: emptyArray(), multiple) { uris ->
                    result.complete(if (uris.isNullOrEmpty()) prompt.dismiss() else prompt.confirm(geckoView.context, uris))
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

    suspend fun click(selector: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val beforeUrl = currentUrl()
        val beforeTitle = currentTitle()
        val beforeState = readElementState(selector)
        val beforeExternalResponse = externalResponseVersion
        val preflight = runCatching { JSONObject(evaluateJson(DomInspector.clickPreflightScript(selector))) }.getOrNull()
            ?: return@withContext BrowserToolResponse.Failure("Unexpected browser response during click preflight")

        if (!preflight.optBoolean("ok", false)) {
            val directHref = preflight.optString("href").takeIf { it.isNotBlank() && !isEmbeddedAppRoute(it) }
            return@withContext if (directHref != null) {
                directOpenHref(directHref, beforeUrl, beforeTitle, "preflight_direct_open")
            } else {
                val metadata = buildMap<String, Any> {
                    preflight.opt("element")?.takeIf { it != JSONObject.NULL }?.let { put("element", it.toString()) }
                    preflight.opt("blocker")?.takeIf { it != JSONObject.NULL }?.let { put("blocker", it.toString()) }
                }
                BrowserToolResponse.Failure(preflight.optString("error", "Click preflight failed"), metadata = metadata)
            }
        }

        val click = preflight.optJSONObject("click")
        val x = click?.optDouble("x")?.toFloat() ?: Float.NaN
        val y = click?.optDouble("y")?.toFloat() ?: Float.NaN
        val directHref = preflight.optString("href").takeIf { it.isNotBlank() && !isEmbeddedAppRoute(it) }
        val covered = preflight.optBoolean("covered", false)
        val inViewport = preflight.optBoolean("in_viewport", false)

        if (!covered && inViewport && x.isFinite() && y.isFinite()) {
            @Suppress("DEPRECATION")
            val scale = geckoView.resources.displayMetrics.density
            val tapX = x * scale
            val tapY = y * scale
            if (isPointInsideView(tapX, tapY)) {
                dispatchTap(tapX, tapY)
                val pageChanged = try {
                    waitForInteractionSettle(beforeUrl, beforeTitle)
                } catch (error: IllegalStateException) {
                    if (error.message == "Browser document navigated") true
                    else return@withContext BrowserToolResponse.Failure("Click failed: ${error.message ?: "unknown error"}")
                }
                delay(180)
                val effectObserved = pageChanged || externalResponseVersion != beforeExternalResponse || hasClickEffect(beforeState, readElementState(selector))
                if (effectObserved) {
                    suppressSoftKeyboard()
                    return@withContext BrowserToolResponse.Success(
                        "Clicked element; page_changed=$pageChanged",
                        currentMetadata() + mapOf(
                            "page_changed" to pageChanged,
                            "before_url" to beforeUrl,
                            "before_title" to beforeTitle,
                            "after_url" to currentUrl(),
                            "after_title" to currentTitle(),
                            "x" to tapX,
                            "y" to tapY,
                            "strategy" to "native_tap"
                        )
                    )
                }
            }
        }

        val json = evaluateJson(DomInspector.clickScript(selector))
        waitForInteractionSettle(beforeUrl, beforeTitle)
        when (val response = jsOkResponse(json, "Clicked element")) {
            is BrowserToolResponse.Success -> {
                val afterUrl = currentUrl()
                val afterTitle = currentTitle()
                val pageChanged = beforeUrl != afterUrl || beforeTitle != afterTitle
                val effectObserved = pageChanged || hasClickEffect(beforeState, readElementState(selector))
                if (effectObserved) {
                    suppressSoftKeyboard()
                    response.copy(
                        output = "Clicked element; page_changed=$pageChanged",
                        metadata = response.metadata + mapOf(
                            "page_changed" to pageChanged,
                            "before_url" to beforeUrl,
                            "before_title" to beforeTitle,
                            "after_url" to afterUrl,
                            "after_title" to afterTitle,
                            "strategy" to "dom_click_fallback"
                        )
                    )
                } else if (directHref != null) {
                    directOpenHref(directHref, beforeUrl, beforeTitle, "dom_click_direct_open")
                } else {
                    suppressSoftKeyboard()
                    BrowserToolResponse.Failure("Click completed but no visible effect was observed")
                }
            }
            is BrowserToolResponse.Failure -> {
                if (directHref != null) {
                    directOpenHref(directHref, beforeUrl, beforeTitle, if (covered) "covered_direct_open" else "fallback_direct_open")
                } else {
                    suppressSoftKeyboard()
                    response
                }
            }
            else -> response
        }
    }

    suspend fun focus(selector: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        resolveCenterPoint(selector)?.let { onAgentTouch(it.first, it.second) }
        val json = evaluateJson(DomInspector.focusScript(selector))
        suppressSoftKeyboard()
        jsOkResponse(json, "Focused element")
    }

    suspend fun typeText(selector: String, text: String, append: Boolean): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        resolveCenterPoint(selector)?.let { onAgentTouch(it.first, it.second) }
        val before = readElementState(selector)
        var json = evaluateJson(DomInspector.typeScript(selector, text, append))
        waitForDomReady(900)
        var response = jsOkResponse(json, "Typed ${text.length} characters")
        var after = readElementState(selector)
        var verified = textApplied(before, after, text, append)
        if (response is BrowserToolResponse.Success && !verified) {
            response = nativeTypeText(selector, text, append)
            waitForDomReady(900)
            after = readElementState(selector)
            verified = textApplied(before, after, text, append)
        }
        suppressSoftKeyboard()
        when (response) {
            is BrowserToolResponse.Success -> if (verified) response.copy(
                metadata = response.metadata + mapOf(
                    "verified" to true,
                    "before_state" to (before?.toString() ?: ""),
                    "after_state" to (after?.toString() ?: "")
                )
            ) else BrowserToolResponse.Failure(
                "Text input completed but the page did not reflect the change",
                recoverable = true,
                metadata = mapOf("before_state" to (before?.toString() ?: ""), "after_state" to (after?.toString() ?: ""))
            )
            else -> response
        }
    }

    suspend fun pressKey(key: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val normalized = key.trim().uppercase().ifBlank { "ENTER" }
        val keyCode = when (normalized) {
            "ENTER", "RETURN" -> KeyEvent.KEYCODE_ENTER
            "TAB" -> KeyEvent.KEYCODE_TAB
            "BACKSPACE", "DEL", "DELETE" -> KeyEvent.KEYCODE_DEL
            "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            "DPAD_CENTER" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> KeyEvent.KEYCODE_ENTER
        }
        val beforeUrl = currentUrl()
        val beforeTitle = currentTitle()
        geckoView.requestFocus()
        val downTime = SystemClock.uptimeMillis()
        geckoView.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        geckoView.dispatchKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        // Native GeckoView key dispatch is authoritative; avoid a second submit from JS.
        val pageChanged = waitForInteractionSettle(beforeUrl, beforeTitle)
        suppressSoftKeyboard()
        BrowserToolResponse.Success(
            "Pressed key $normalized; page_changed=$pageChanged",
            currentMetadata() + mapOf(
                "key" to normalized,
                "page_changed" to pageChanged,
                "before_url" to beforeUrl,
                "before_title" to beforeTitle,
                "after_url" to currentUrl(),
                "after_title" to currentTitle()
            )
        )
    }

    suspend fun tap(x: Int, y: Int, selector: String?): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        // Raw x/y from the AI are CSS viewport pixels (from DOM summary bounds/center).
        // resolveCenterPoint already applies scale, so only scale the raw-coordinate path.
        @Suppress("DEPRECATION")
        val scale = geckoView.resources.displayMetrics.density
        val point = if (x >= 0 && y >= 0) (x.toFloat() * scale) to (y.toFloat() * scale) else selector?.let { resolveCenterPoint(it) }
            ?: return@withContext BrowserToolResponse.Failure("Missing tap coordinates or resolvable element_id/selector")
        val beforeUrl = currentUrl()
        val beforeTitle = currentTitle()
        dispatchTap(point.first, point.second)
        val pageChanged = waitForInteractionSettle(beforeUrl, beforeTitle)
        suppressSoftKeyboard()
        BrowserToolResponse.Success(
            "Tapped at x=${point.first.toInt()} y=${point.second.toInt()}; page_changed=$pageChanged",
            currentMetadata() + mapOf(
                "x" to point.first,
                "y" to point.second,
                "page_changed" to pageChanged,
                "before_url" to beforeUrl,
                "before_title" to beforeTitle,
                "after_url" to currentUrl(),
                "after_title" to currentTitle()
            )
        )
    }

    suspend fun swipe(direction: String?, distance: Float, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val width = geckoView.width.coerceAtLeast(1).toFloat()
        val height = geckoView.height.coerceAtLeast(1).toFloat()
        val normalized = direction?.lowercase()?.trim()
        val clampedDistance = distance.coerceIn(0.16f, 0.55f)
        val verticalTravel = (height * clampedDistance).coerceIn(height * 0.14f, height * 0.42f)
        val horizontalTravel = (width * clampedDistance).coerceIn(width * 0.12f, width * 0.35f)

        val defaultStartX = when (normalized) {
            "left" -> width * 0.72f
            "right" -> width * 0.28f
            else -> width * 0.5f
        }
        val defaultStartY = when (normalized) {
            // direction = page movement, so finger moves opposite like a human swipe.
            "up" -> height * 0.36f
            "down", null, "" -> height * 0.64f
            else -> height * 0.5f
        }

        val sx = if (startX >= 0) startX.toFloat() else defaultStartX
        val sy = if (startY >= 0) startY.toFloat() else defaultStartY
        val ex = if (endX >= 0) endX.toFloat() else when (normalized) {
            "left" -> (sx + horizontalTravel).coerceIn(width * 0.16f, width * 0.84f)
            "right" -> (sx - horizontalTravel).coerceIn(width * 0.16f, width * 0.84f)
            else -> sx
        }
        val ey = if (endY >= 0) endY.toFloat() else when (normalized) {
            "up" -> (sy + verticalTravel).coerceIn(height * 0.18f, height * 0.82f)
            "down", null, "" -> (sy - verticalTravel).coerceIn(height * 0.18f, height * 0.82f)
            else -> sy
        }

        val before = currentScrollSnapshot()
        dispatchSwipe(sx, sy, ex, ey, durationMs.coerceIn(240, 700))
        waitForScrollSettled(900)
        val after = currentScrollSnapshot()
        BrowserToolResponse.Success(
            "Swiped ${normalized ?: "down"} from (${sx.toInt()},${sy.toInt()}) to (${ex.toInt()},${ey.toInt()})",
            currentMetadata() + mapOf(
                "direction" to (normalized ?: "down"),
                "before_scroll" to before,
                "after_scroll" to after,
                "scroll_changed" to (before != after)
            )
        )
    }

    suspend fun search(text: String, selector: String?): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val target = selector ?: evaluateJson(DomInspector.findSearchInputScript()).takeIf { it != "null" && it.isNotBlank() }
        if (target == null) return@withContext BrowserToolResponse.Failure("Search input not found")
        resolveCenterPoint(target)?.let { onAgentTouch(it.first, it.second) }
        val focus = jsOkResponse(evaluateJson(DomInspector.focusScript(target)), "Focused search input")
        if (focus is BrowserToolResponse.Failure) return@withContext focus
        val typed = typeText(target, text, false)
        if (typed is BrowserToolResponse.Failure) return@withContext typed
        val submitted = runCatching { submitFromContext(target) }.getOrElse { error ->
            if (error.message == "Browser document navigated") waitForPage(30_000)
            else BrowserToolResponse.Failure("Search submit failed: ${error.message ?: "unknown error"}")
        }
        if (submitted is BrowserToolResponse.Failure && submitted.message == "Browser document navigated") waitForPage(30_000) else submitted
    }

    suspend fun clearInput(selector: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        var json = evaluateJson(DomInspector.clearScript(selector))
        waitForDomReady(700)
        var response = jsOkResponse(json, "Cleared input")
        var after = readElementState(selector)
        var cleared = isEffectivelyEmpty(after)
        if (response is BrowserToolResponse.Success && !cleared) {
            json = evaluateJson(DomInspector.clearScript(selector))
            waitForDomReady(700)
            response = jsOkResponse(json, "Cleared input")
            after = readElementState(selector)
            cleared = isEffectivelyEmpty(after)
        }
        suppressSoftKeyboard()
        when (response) {
            is BrowserToolResponse.Success -> if (cleared) response.copy(
                metadata = response.metadata + mapOf(
                    "verified" to true,
                    "after_state" to (after?.toString() ?: "")
                )
            ) else BrowserToolResponse.Failure(
                "Clear input completed but the field is still populated",
                recoverable = true,
                metadata = mapOf("after_state" to (after?.toString() ?: ""))
            )
            else -> response
        }
    }

    suspend fun restoreScroll(x: Int, y: Int) = withContext(Dispatchers.Main.immediate) {
        evaluateString("window.scrollTo(${x.coerceIn(-100_000, 100_000)},${y.coerceIn(-100_000, 100_000)})")
    }

    suspend fun scrollPage(deltaX: Int, deltaY: Int): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val before = currentScrollSnapshot()
        val script = """
            (function() {
              var before = { x: window.scrollX, y: window.scrollY, h: document.body ? document.body.scrollHeight : 0 };
              var node = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
              var target = null;
              while (node && node !== document.body) {
                var style = getComputedStyle(node);
                if (/(auto|scroll)/.test((style.overflow || '') + (style.overflowX || '') + (style.overflowY || '')) && (node.scrollHeight > node.clientHeight || node.scrollWidth > node.clientWidth)) { target = node; break; }
                node = node.parentElement;
              }
              if (target) target.scrollBy(${deltaX}, ${deltaY}); else window.scrollBy(${deltaX}, ${deltaY});
              return JSON.stringify({
                ok:true,
                target:target ? (target.id || target.tagName || 'nested') : 'window',
                before: before,
                after: { x: window.scrollX, y: window.scrollY, h: document.body ? document.body.scrollHeight : 0 }
              });
            })();
        """.trimIndent()
        val json = evaluateJson(script)
        waitForScrollSettled(900)
        val after = currentScrollSnapshot()
        BrowserToolResponse.Success(
            "Scrolled page by x=$deltaX y=$deltaY",
            currentMetadata() + mapOf(
                "before_scroll" to before,
                "after_scroll" to after,
                "scroll_changed" to (before != after),
                "scroll_result" to json
            )
        )
    }

    suspend fun getHtml(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val html = evaluateString("document.documentElement ? document.documentElement.outerHTML : ''")
        BrowserToolResponse.Success(html.take(200_000), currentMetadata() + mapOf("length" to html.length, "truncated" to (html.length > 200_000)))
    }

    suspend fun getDom(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val json = evaluateJson(DomInspector.getDomScript())
        val viewport = viewportSnapshot()
        BrowserToolResponse.Success(
            output = json.take(50_000),
            metadata = currentMetadata() + mapOf("dom" to json, "viewport" to viewport)
        )
    }

    suspend fun hover(selector: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val json = evaluateJson(DomInspector.hoverScript(selector))
        jsOkResponse(json, "Hovered element")
    }

    suspend fun selectOption(selector: String, value: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val json = evaluateJson(DomInspector.selectOptionScript(selector, value))
        jsOkResponse(json, "Selected option")
    }

    suspend fun getVisibleText(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val text = evaluateString(DomInspector.getVisibleTextScript())
        BrowserToolResponse.Success(text, currentMetadata() + ("length" to text.length))
    }

    suspend fun findElement(query: String): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val json = evaluateJson(DomInspector.findElementScript(query))
        if (json == "null" || json.isBlank()) {
            BrowserToolResponse.Failure("Element not found for query: $query")
        } else {
            BrowserToolResponse.Success(json, currentMetadata() + ("element" to json))
        }
    }

    suspend fun waitForNavigation(timeoutMs: Long): BrowserToolResponse = waitForPage(timeoutMs)

    suspend fun waitForElement(query: String, timeoutMs: Long): BrowserToolResponse {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            if (cancelled) return BrowserToolResponse.Failure("Action cancelled", recoverable = false)
            val found = findElement(query)
            if (found is BrowserToolResponse.Success) return found.copy(output = "Element appeared: ${found.output}")
            delay(180)
        }
        return BrowserToolResponse.Failure("Timed out waiting for element: $query")
    }

    suspend fun submitFromContext(selector: String?): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val beforeUrl = currentUrl()
        val beforeTitle = currentTitle()
        val json = evaluateJson(DomInspector.submitFromContextScript(selector))
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return@withContext BrowserToolResponse.Failure("Unexpected browser response: $json")
        if (!obj.optBoolean("ok", false)) {
            return@withContext BrowserToolResponse.Failure(obj.optString("error", "Submit action failed"))
        }
        val pageChanged = waitForInteractionSettle(beforeUrl, beforeTitle)
        BrowserToolResponse.Success(
            output = "Submitted from context; page_changed=$pageChanged",
            metadata = currentMetadata() + buildMap<String, Any> {
                put("page_changed", pageChanged)
                put("before_url", beforeUrl)
                put("before_title", beforeTitle)
                put("after_url", currentUrl())
                put("after_title", currentTitle())
                put("strategy", obj.optString("strategy", "unknown"))
                put("purpose", obj.optString("purpose", "submit"))
                obj.opt("button")?.takeIf { it != JSONObject.NULL }?.let { put("button", it.toString()) }
                obj.opt("anchor")?.takeIf { it != JSONObject.NULL }?.let { put("anchor", it.toString()) }
            }
        )
    }

    suspend fun goBack(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        if (!canGoBackValue) return@withContext BrowserToolResponse.Failure("No back history available")
        val beforeUrl = currentUrl()
        cancelled = false
        pageFinished = false
        session.goBack()
        waitForHistoryNavigation(beforeUrl)
    }

    suspend fun goForward(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        if (!canGoForwardValue) return@withContext BrowserToolResponse.Failure("No forward history available")
        val beforeUrl = currentUrl()
        cancelled = false
        pageFinished = false
        session.goForward()
        waitForHistoryNavigation(beforeUrl)
    }

    suspend fun reload(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        cancelled = false
        pageFinished = false
        session.reload()
        waitForPage(20_000)
    }

    suspend fun evaluate(expression: String, timeoutMs: Long = 10_000): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val source = expression.trim()
        if (source.isBlank()) return@withContext BrowserToolResponse.Failure("Evaluate script is empty")
        val script = """
            (async function() {
              const source = ${JSONObject.quote(source)};
              const timeout = ${timeoutMs.coerceIn(250, 10_000)};
              const value = await Promise.race([
                Promise.resolve().then(() => (0, eval)(source)),
                new Promise((_, reject) => setTimeout(() => reject(new Error('evaluate timeout')), timeout))
              ]);
              if (value === undefined) return JSON.stringify({ok:true,value:null});
              return JSON.stringify({ok:true,value:value});
            })().catch(e => JSON.stringify({ok:false,error:String(e && e.message || e)}))
        """.trimIndent()
        val raw = withTimeout(timeoutMs.coerceIn(250, 10_000) + 500L) { evaluateString(script) }
        val result = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext BrowserToolResponse.Failure("Evaluate returned invalid JSON")
        if (!result.optBoolean("ok", false)) return@withContext BrowserToolResponse.Failure(result.optString("error", "Evaluate failed"))
        val value = result.opt("value")
        if (value is JSONObject || value is org.json.JSONArray) {
            val encoded = value.toString()
            if (encoded.length > 65_536) return@withContext BrowserToolResponse.Failure("Evaluate result exceeds 64KB", recoverable = true)
        }
        val output = JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL).toString()
        if (output.length > 65_536) return@withContext BrowserToolResponse.Failure("Evaluate result exceeds 64KB", recoverable = true)
        BrowserToolResponse.Success("Evaluate completed", currentMetadata() + mapOf("evaluate_result" to output))
    }

    suspend fun screenshot(): BrowserToolResponse = withContext(Dispatchers.Main.immediate) {
        val bitmap = geckoResult(geckoView.capturePixels())
        val width = bitmap.width
        val height = bitmap.height
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bitmap.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        BrowserToolResponse.Success(
            output = "Screenshot captured (${width}x${height})",
            metadata = currentMetadata() + mapOf("image_base64" to b64, "mime_type" to "image/jpeg", "width" to width, "height" to height)
        )
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

    private suspend fun waitForHistoryNavigation(beforeUrl: String): BrowserToolResponse = try {
        withTimeout(15_000) {
            while (!pageFinished && currentUrl() == beforeUrl && !cancelled) delay(50)
        }
        when {
            cancelled -> BrowserToolResponse.Failure("Action cancelled", recoverable = false)
            !pageFinished && currentUrl() == beforeUrl -> BrowserToolResponse.Failure("History navigation timed out. You can retry or inspect the current page.")
            !pageFinished -> BrowserToolResponse.Success("History changed to ${currentUrl()}", currentMetadata())
            !pageLoadSucceeded -> BrowserToolResponse.Failure("Network/browser error while loading ${currentUrl()}")
            else -> BrowserToolResponse.Success("Loaded ${currentUrl()}", currentMetadata())
        }
    } catch (_: TimeoutCancellationException) {
        BrowserToolResponse.Failure("History navigation timed out. You can retry or inspect the current page.")
    }

    private suspend fun waitForPage(timeoutMs: Long): BrowserToolResponse {
        return try {
            withTimeout(timeoutMs) {
                while (!pageFinished && !cancelled) delay(75)
            }
            when {
                cancelled -> BrowserToolResponse.Failure("Action cancelled", recoverable = false)
                !pageLoadSucceeded -> BrowserToolResponse.Failure("Network/browser error while loading ${currentUrl()}")
                else -> BrowserToolResponse.Success("Loaded ${currentUrl()}", currentMetadata())
            }
        } catch (_: TimeoutCancellationException) {
            BrowserToolResponse.Failure("Page load timed out. You can retry, reload, or inspect the partially loaded page.")
        }
    }

    private suspend fun resolveCenterPoint(selector: String): Pair<Float, Float>? {
        val obj = runCatching { org.json.JSONObject(evaluateJson(DomInspector.boundsScript(selector))) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) return null
        // getBoundingClientRect() returns CSS viewport pixels; convert to Android view
        // pixels by multiplying with the GeckoView's display density.
        @Suppress("DEPRECATION")
        val scale = geckoView.resources.displayMetrics.density
        return (obj.optDouble("center_x").toFloat() * scale) to (obj.optDouble("center_y").toFloat() * scale)
    }

    private fun isPointInsideView(x: Float, y: Float): Boolean {
        val width = geckoView.width.toFloat().coerceAtLeast(1f)
        val height = geckoView.height.toFloat().coerceAtLeast(1f)
        return x in 0f..width && y in 0f..height
    }

    private fun hasClickEffect(before: JSONObject?, after: JSONObject?): Boolean {
        if (before == null || after == null) return false
        return before.optBoolean("focused") != after.optBoolean("focused") ||
            before.optBoolean("checked") != after.optBoolean("checked") ||
            before.optBoolean("expanded") != after.optBoolean("expanded") ||
            before.optString("value") != after.optString("value") ||
            before.optString("text") != after.optString("text")
    }

    private suspend fun directOpenHref(href: String, beforeUrl: String, beforeTitle: String, strategy: String): BrowserToolResponse {
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

    private fun dispatchTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(90L)
        onAgentTouch(x, y)
        isDispatchingAgentInput = true
        try {
            geckoView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
            geckoView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + tapTimeout, MotionEvent.ACTION_UP, x, y, 0))
        } finally {
            isDispatchingAgentInput = false
        }
    }

    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
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

    private suspend fun evaluateJson(script: String): String = evaluateString(script).ifBlank { "{}" }

    private suspend fun evaluateString(script: String): String = withContext(Dispatchers.Main.immediate) {
        GeckoBrowserRuntime.evaluate(session, script)
    }

    private suspend fun nativeTypeText(selector: String, text: String, append: Boolean): BrowserToolResponse {
        val focused = jsOkResponse(evaluateJson(DomInspector.focusScript(selector)), "Focused input")
        if (focused is BrowserToolResponse.Failure) return focused
        if (!append) evaluateJson(DomInspector.clearScript(selector))
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

    private fun suppressSoftKeyboard() {
        val imm = geckoView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        geckoView.post {
            imm?.hideSoftInputFromWindow(geckoView.windowToken, 0)
        }
    }

    private suspend fun readElementState(selector: String): JSONObject? {
        val json = evaluateJson(DomInspector.elementStateScript(selector))
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return obj.takeIf { it.optBoolean("ok", false) }
    }

    private fun textApplied(before: JSONObject?, after: JSONObject?, text: String, append: Boolean): Boolean {
        val afterValue = after?.optString("value")?.trim().orEmpty()
        val afterText = after?.optString("text")?.trim().orEmpty()
        if (afterValue.isBlank() && afterText.isBlank()) return false
        if (!append) return afterValue == text || afterText.contains(text)
        val beforeValue = before?.optString("value")?.trim().orEmpty()
        return afterValue.length >= beforeValue.length + text.length || afterValue.endsWith(text) || afterText.contains(text)
    }

    private fun isEffectivelyEmpty(state: JSONObject?): Boolean {
        if (state == null) return false
        return state.optString("value").isBlank() && state.optString("text").isBlank()
    }

    private fun jsOkResponse(json: String, successMessage: String): BrowserToolResponse {
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

    private suspend fun <T> geckoResult(result: GeckoResult<T>): T = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        result.then<Void>({ value: T? ->
            if (value == null) continuation.cancel(IllegalStateException("Gecko operation returned null"))
            else if (continuation.isActive) continuation.resume(value) { _, _, _ -> }
            null
        }, { error: Throwable ->
            if (continuation.isActive) continuation.cancel(error)
            null
        })
    }

    private suspend fun waitForDomReady(timeoutMs: Long) {
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

    private suspend fun waitForInteractionSettle(beforeUrl: String, beforeTitle: String, timeoutMs: Long = 1_400): Boolean {
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

    private suspend fun currentScrollSnapshot(): String = evaluateString("JSON.stringify({x:window.scrollX,y:window.scrollY,h:(document.body?document.body.scrollHeight:0),ready:document.readyState})")

    private suspend fun viewportSnapshot(): String = evaluateString("JSON.stringify({innerWidth:innerWidth,innerHeight:innerHeight,outerWidth:outerWidth,outerHeight:outerHeight,devicePixelRatio:devicePixelRatio,scrollX:scrollX,scrollY:scrollY,visualViewport:visualViewport?{width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale,offsetTop:visualViewport.offsetTop,offsetLeft:visualViewport.offsetLeft}:null,documentWidth:document.documentElement?document.documentElement.clientWidth:0,documentHeight:document.documentElement?document.documentElement.clientHeight:0,bodyHeight:document.body?document.body.scrollHeight:0})")

    private fun logViewport(stage: String) { Log.d("AmayaBrowser", "$stage url=$currentUrlValue view=${geckoView.width}x${geckoView.height}") }

    private suspend fun waitForScrollSettled(timeoutMs: Long) {
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

    private fun emitNavigation() { onNavigationChanged(currentUrlValue, currentTitleValue, progressValue, canGoBackValue, canGoForwardValue) }

    private fun isEmbeddedAppRoute(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        uri.host.equals("x.com", true) && uri.path.orEmpty().startsWith("/i/jf/")
    }.getOrDefault(false)

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "about:blank") return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
