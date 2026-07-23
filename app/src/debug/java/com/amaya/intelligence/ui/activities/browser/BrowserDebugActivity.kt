package com.amaya.intelligence.ui.activities.browser

import android.os.Bundle
import android.graphics.SurfaceTexture
import android.view.Surface
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.domain.models.AgentCapabilityProfile
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.impl.local.browser.BrowserActionCatalog
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.browser.GeckoBrowserRuntime
import com.amaya.intelligence.tools.ToolExecutionContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class BrowserDebugActivity : AppCompatActivity() {
    @Inject lateinit var manager: BrowserSessionManager
    private lateinit var output: TextView
    private val running = AtomicBoolean(false)
    private val headlessSessions = mutableListOf<GeckoSession>()
    private val offscreenDisplays = mutableListOf<Triple<GeckoSession, GeckoDisplay, Pair<SurfaceTexture, Surface>>>()
    private val debugMode by lazy { intent.getStringExtra("mode") ?: if (intent.getBooleanExtra("headless", false)) "gecko-headless" else "visible" }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        if (debugMode != "visible") {
            setContentView(output)
            if (running.compareAndSet(false, true)) lifecycleScope.launch {
                when (debugMode) {
                    "manager-headless" -> runManagerHeadlessSuite()
                    "restore-seed" -> runProcessRestoreSeed()
                    "restore-check" -> runProcessRestoreCheck()
                    "real-web" -> runRealWebSuite()
                    else -> runHeadlessSuite()
                }
            }
            return
        }
        manager.resetForConversation("conversation:debug-browser", 1L)
        manager.setWorkspace(filesDir.absolutePath)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(manager.acquireSharedBrowserView(), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(output, LinearLayout.LayoutParams(-1, 260))
        setContentView(root)
        if (running.compareAndSet(false, true)) lifecycleScope.launch { runSuite() }
    }

    private suspend fun runRealWebSuite() {
        manager.resetForConversation("conversation:debug-real-web", 1L)
        manager.setWorkspace(filesDir.absolutePath)
        val context = debugContext("debug-real-web", 1L)
        val report = JSONArray()
        suspend fun action(name: String, params: Map<String, Any?> = emptyMap()) {
            val started = System.currentTimeMillis()
            val raw = runCatching {
                withTimeout(45_000) {
                    manager.executeBrowserTask(mapOf("action" to name, "params" to params, "reset_task" to true), context)
                }
            }.getOrElse { JSONObject().put("status", "exception").put("error", it.stackTraceToString()).toString() }
            val result = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("status", "invalid").put("raw", raw) }
            val row = JSONObject().apply {
                put("site", manager.uiState.value.activeUrl)
                put("action", name)
                put("status", result.optString("status"))
                put("passed", result.optString("status") == "completed")
                put("duration_ms", System.currentTimeMillis() - started)
                put("result", raw.take(12_000))
            }
            report.put(row)
            emit("REAL_WEB ${row.toString()}")
        }
        try {
            action("open_url", mapOf("url" to "https://x.com/compose/post"))
            action("get_status")
            action("observe")
            action("get_dom")
            action("get_html")
            action("get_content")
            action("evaluate", mapOf("expression" to "JSON.stringify({title:document.title,ready:document.readyState,url:location.href})"))
            action("screenshot")
            action("find_text", mapOf("query" to "Post"))
            action("find_element", mapOf("query" to "Post"))
            action("wait_for_selector", mapOf("query" to "textarea", "timeout_ms" to 5_000))
            action("scroll", mapOf("direction" to "down", "amount" to "small"))
            action("reload")
            action("get_dom")

            action("new_page", mapOf("url" to "https://www.instagram.com/"))
            action("get_status")
            action("observe")
            action("get_dom")
            action("get_html")
            action("get_content")
            action("evaluate", mapOf("expression" to "JSON.stringify({title:document.title,ready:document.readyState,url:location.href})"))
            action("screenshot")
            action("find_text", mapOf("query" to "Log in"))
            action("find_element", mapOf("query" to "Log in"))
            action("scroll", mapOf("direction" to "down", "amount" to "small"))
            action("reload")
            action("get_dom")
            action("list_pages")
        } finally {
            val passed = (0 until report.length()).count { report.optJSONObject(it)?.optBoolean("passed") == true }
            val summary = JSONObject().put("passed", passed).put("failed", report.length() - passed).put("total", report.length())
            val file = File(getExternalFilesDir(null), "browser-real-web-report.json")
            file.writeText(JSONObject().put("summary", summary).put("actions", report).toString(2))
            emit("REAL_WEB SUMMARY $summary")
            emit("REAL_WEB REPORT ${file.absolutePath}")
        }
    }

    private suspend fun runHeadlessSuite() {
        val server = withContext(Dispatchers.IO) { TestPageServer() }
        val failures = mutableListOf<String>()
        suspend fun check(name: String, block: suspend () -> Boolean) {
            val started = System.currentTimeMillis()
            val passed = runCatching { withTimeout(10_000) { block() } }.getOrDefault(false)
            val row = "HEADLESS $name passed=$passed duration_ms=${System.currentTimeMillis() - started}"
            emit(row)
            if (!passed) failures += name
        }
        fun newHeadlessSession(label: String, active: Boolean = false, withSurface: Boolean = false): GeckoSession = GeckoSession().also { session ->
            session.setActive(active)
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            session.open(GeckoBrowserRuntime.get(this))
            headlessSessions += session
            if (withSurface) {
                val texture = SurfaceTexture(0).apply { setDefaultBufferSize(1080, 1920) }
                val surface = Surface(texture)
                val display = session.acquireDisplay()
                display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(surface).size(1080, 1920).build())
                offscreenDisplays += Triple(session, display, texture to surface)
            }
            Log.i("AmayaBrowser", "headless session created label=$label session=$session active=$active surface=$withSurface priority=high")
        }
        suspend fun loadAndEvaluate(session: GeckoSession, url: String, expression: String): String {
            GeckoBrowserRuntime.attach(this, session, reloadIfNeeded = false)
            withContext(Dispatchers.Main.immediate) { session.loadUri(url) }
            return GeckoBrowserRuntime.evaluate(session, "new Promise(resolve => { const done = () => resolve($expression); if (document.readyState === 'complete') done(); else addEventListener('load', done, {once:true}); })", 10_000)
                .also { emit("HEADLESS value url=$url expression=$expression value=$it") }
        }
        try {
            val first = newHeadlessSession("inactive-no-surface")
            val second = newHeadlessSession("active-no-surface", active = true)
            val surfaced = newHeadlessSession("active-offscreen-surface", active = true, withSurface = true)
            check("session-1-load-and-js") {
                loadAndEvaluate(first, "${server.url}spa", "document.title") in setOf("SPA loading", "SPA ready")
            }
            check("session-2-load-and-js") {
                loadAndEvaluate(second, "${server.url}", "document.title") == "Amaya test"
            }
            check("inactive-timers-suspended") {
                delay(500)
                GeckoBrowserRuntime.evaluate(first, "JSON.stringify({ready:document.readyState,title:document.title,button:!!document.querySelector('#spa-next'),viewport:[innerWidth,innerHeight]})", 5_000)
                    .also { emit("HEADLESS inactive-state value=$it") }
                    .let { JSONObject(it).optString("title") == "SPA loading" && !JSONObject(it).optBoolean("button") }
            }
            check("active-no-surface-timers-run") {
                loadAndEvaluate(second, "${server.url}spa", "document.title")
                delay(700)
                GeckoBrowserRuntime.evaluate(second, "JSON.stringify({title:document.title,button:!!document.querySelector('#spa-next'),viewport:[innerWidth,innerHeight]})", 5_000)
                    .also { emit("HEADLESS active-no-surface-state value=$it") }
                    .let { JSONObject(it).optString("title") == "SPA ready" && JSONObject(it).optBoolean("button") }
            }
            check("offscreen-surface-has-viewport-and-intersection") {
                loadAndEvaluate(surfaced, "${server.url}intersection", "document.title")
                delay(700)
                GeckoBrowserRuntime.evaluate(surfaced, "JSON.stringify({title:document.title,lazy:!!document.querySelector('#intersection-ready'),viewport:[innerWidth,innerHeight]})", 5_000)
                    .also { emit("HEADLESS offscreen-state value=$it") }
                    .let { json -> JSONObject(json).optBoolean("lazy") && JSONObject(json).getJSONArray("viewport").optInt(0) > 0 }
            }
            check("parallel-sessions-isolation") {
                val values = coroutineScope {
                    listOf(
                        async { GeckoBrowserRuntime.evaluate(first, "location.pathname", 5_000) },
                        async { GeckoBrowserRuntime.evaluate(second, "location.pathname", 5_000) }
                    ).map { it.await() }
                }
                values == listOf("/spa", "/")
            }
            check("switch-stress-no-cross-session-timeout") {
                val sessions = (1..4).map { newHeadlessSession("stress-$it") }
                coroutineScope {
                    sessions.mapIndexed { index, session ->
                        async {
                            loadAndEvaluate(session, "${server.url}${if (index % 2 == 0) "" else "spa"}", "document.title")
                        }
                    }.map { it.await() }
                }.all { it == "Amaya test" || it == "SPA loading" || it == "SPA ready" }
            }
            check("closing-one-session-keeps-other-alive") {
                val survivor = second
                withContext(Dispatchers.Main.immediate) { first.close() }
                headlessSessions.remove(first)
                GeckoBrowserRuntime.detach(first)
                GeckoBrowserRuntime.evaluate(survivor, "document.title", 5_000) == "Amaya test"
            }
        } finally {
            offscreenDisplays.toList().forEach { (session, display, resources) ->
                runCatching { display.surfaceDestroyed() }
                runCatching { session.releaseDisplay(display) }
                resources.second.release()
                resources.first.release()
            }
            offscreenDisplays.clear()
            headlessSessions.toList().forEach { session ->
                runCatching { GeckoBrowserRuntime.detach(session) }
                runCatching { session.close() }
            }
            headlessSessions.clear()
            server.close()
            emit("HEADLESS SUMMARY passed=${failures.isEmpty()} failures=${failures.joinToString(",")}")
        }
    }

    private suspend fun runProcessRestoreSeed() {
        val url = intent.getStringExtra("url") ?: return emit("PROCESS_RESTORE SEED passed=false missing_url")
        val context = debugContext("debug-process-restore", 301L)
        val result = JSONObject(manager.executeBrowserTask(mapOf("action" to "open_url", "params" to mapOf("url" to url), "reset_task" to true), context))
        emit("PROCESS_RESTORE SEED passed=${result.optString("status") == "completed"} session=${result.optString("session_id")} url=${result.optString("active_url")}")
    }

    private suspend fun runProcessRestoreCheck() {
        val context = debugContext("debug-process-restore", 301L)
        val result = JSONObject(manager.executeBrowserTask(mapOf("action" to "evaluate", "params" to mapOf("expression" to "document.title"), "reset_task" to true), context))
        emit("PROCESS_RESTORE CHECK passed=${result.optString("status") == "completed"} session=${result.optString("session_id")} url=${result.optString("active_url")}")
    }

    private fun debugContext(conversationId: String, agentId: Long) = ToolExecutionContext(
        conversationId = conversationId,
        agentId = agentId,
        assistantMode = AssistantMode.AGENT,
        agentCapabilityProfile = AgentCapabilityProfile(),
        workspacePath = filesDir.absolutePath
    )

    private suspend fun runManagerHeadlessSuite() {
        manager.resetForConversation("conversation:debug-browser-manager", 1L)
        manager.setWorkspace(filesDir.absolutePath)
        val server = withContext(Dispatchers.IO) { TestPageServer() }
        val context = debugContext("debug-browser-manager", 1L)
        val failures = mutableListOf<String>()
        suspend fun action(name: String, params: Map<String, Any?> = emptyMap(), verify: (JSONObject) -> Boolean = { it.optString("status") == "completed" }) {
            val started = System.currentTimeMillis()
            val raw = runCatching { manager.executeBrowserTask(mapOf("action" to name, "params" to params, "reset_task" to true), context) }
                .getOrElse { JSONObject().put("status", "exception").put("error", it.stackTraceToString()).toString() }
            val json = JSONObject(raw)
            val passed = verify(json)
            emit("MANAGER_HEADLESS action=$name passed=$passed duration_ms=${System.currentTimeMillis() - started} status=${json.optString("status")} url=${json.optString("active_url")} error=${json.optString("error")}")
            if (!passed) {
                failures += name
                emit("MANAGER_HEADLESS failure=$name raw=${raw.take(4000)}")
            }
        }
        try {
            action("open_url", mapOf("url" to "${server.url}lazy"))
            action("wait_for_selector", mapOf("query" to "Lazy ready", "timeout_ms" to 5_000))
            action("find_text", mapOf("query" to "Lazy ready"))
            action("scroll", mapOf("direction" to "down", "amount" to "large"))
            action("evaluate", mapOf("expression" to "JSON.stringify({title:document.title,ready:document.readyState,lazy:!!document.querySelector('#lazy-ready'),viewport:[innerWidth,innerHeight],scrollY:scrollY})"))
            val firstPage = manager.uiState.value.activeTabId.orEmpty()
            action("new_page", mapOf("url" to "${server.url}spa"))
            val secondPage = manager.uiState.value.activeTabId.orEmpty()
            repeat(10) { index ->
                action("switch_page", mapOf("page_id" to if (index % 2 == 0) firstPage else secondPage))
                action("evaluate", mapOf("expression" to "document.title"))
            }
            val parallelResults = coroutineScope {
                (1L..3L).map { id ->
                    async {
                        val parallelContext = context.copy(conversationId = "debug-parallel-$id", agentId = id)
                        manager.executeBrowserTask(
                            mapOf(
                                "reset_task" to true,
                                "steps" to listOf(
                                    mapOf("action" to "open_url", "params" to mapOf("url" to "${server.url}lazy")),
                                    mapOf("action" to "wait_for_selector", "params" to mapOf("query" to "Lazy ready", "timeout_ms" to 5_000)),
                                    mapOf("action" to "evaluate", "params" to mapOf("expression" to "document.title"))
                                )
                            ),
                            parallelContext
                        )
                    }
                }.also {
                    delay(100)
                    manager.selectConversation("conversation:debug-visible-switch", 99L)
                }.map { JSONObject(it.await()) }
            }
            val parallelPassed = parallelResults.all { it.optString("status") == "completed" } &&
                parallelResults.map { it.optString("session_id") }.distinct().size == 3
            emit("MANAGER_HEADLESS parallel-conversations passed=$parallelPassed statuses=${parallelResults.map { it.optString("status") }} sessions=${parallelResults.map { it.optString("session_id") }}")
            if (!parallelPassed) failures += "parallel-conversations"

            val stressResults = coroutineScope {
                (1L..8L).map { id ->
                    async {
                        val stressContext = context.copy(conversationId = "debug-stress-$id", agentId = 100L + id)
                        JSONObject(manager.executeBrowserTask(
                            mapOf("reset_task" to true, "steps" to listOf(
                                mapOf("action" to "open_url", "params" to mapOf("url" to "${server.url}lazy")),
                                mapOf("action" to "wait_for_selector", "params" to mapOf("query" to "#lazy-ready", "timeout_ms" to 5_000)),
                                mapOf("action" to "evaluate", "params" to mapOf("expression" to "document.querySelector('#external-field').focus()")),
                                mapOf("action" to "type_text", "params" to mapOf("text" to "external-$id", "append" to false)),
                                mapOf("action" to "evaluate", "params" to mapOf("expression" to "document.querySelector('#external-field').value === 'external-$id' ? 'ok' : (() => { throw new Error('active-field typing failed') })()"))
                            )), stressContext))
                    }
                }.map { it.await() }
            }
            val stressPassed = stressResults.all { it.optString("status") == "completed" } &&
                stressResults.map { it.optString("session_id") }.distinct().size == 8
            emit("MANAGER_HEADLESS eight-agent-stress passed=$stressPassed sessions=${stressResults.map { it.optString("session_id") }}")
            if (!stressPassed) failures += "eight-agent-stress"

            val restoreContext = context.copy(conversationId = "debug-restore", agentId = 200L)
            val restoreOpen = JSONObject(manager.executeBrowserTask(mapOf("action" to "open_url", "params" to mapOf("url" to "${server.url}restore"), "reset_task" to true), restoreContext))
            val beforeTrim = memoryMetrics()
            manager.releaseInactiveRuntimes()
            val afterTrim = memoryMetrics()
            emit("MANAGER_HEADLESS memory before_pss_kb=${beforeTrim.first} before_graphics_kb=${beforeTrim.second} after_pss_kb=${afterTrim.first} after_graphics_kb=${afterTrim.second}")
            manager.selectConversation("conversation:debug-visible-switch", 99L)
            val restoreAgain = JSONObject(manager.executeBrowserTask(mapOf("action" to "get_status", "reset_task" to true), restoreContext))
            val restorePassed = restoreOpen.optString("status") == "completed" && restoreAgain.optString("session_id") == restoreOpen.optString("session_id") && restoreAgain.optString("active_url").endsWith("/restore")
            emit("MANAGER_HEADLESS eviction-restore passed=$restorePassed before=${restoreOpen.optString("active_url")} after=${restoreAgain.optString("active_url")}")
            if (!restorePassed) failures += "eviction-restore"

            manager.selectConversation("conversation:debug-browser-manager", 1L)
            action("open_url", mapOf("url" to "${server.url}upload"))
            action("click", mapOf("element_id" to "#upload-file"))
            delay(300)
            val headlessUploadRequested = manager.uiState.value.uploadPending
            manager.cancelPendingUpload()
            val headlessUploadSafe = !headlessUploadRequested
            emit("MANAGER_HEADLESS upload-file-picker passed=$headlessUploadSafe note=visible_operator_required requested=$headlessUploadRequested")
            if (!headlessUploadSafe) failures += "headless-upload-picker"
        } finally {
            server.close()
            emit("MANAGER_HEADLESS SUMMARY passed=${failures.isEmpty()} failures=${failures.joinToString(",")}")
        }
    }

    private suspend fun runSuite() {
        val report = mutableListOf<JSONObject>()
        val server = withContext(Dispatchers.IO) { TestPageServer() }
        File(filesDir, ".amaya/browser/downloads").deleteRecursively()
        File(filesDir, ".amaya/browser/uploads").deleteRecursively()
        val context = ToolExecutionContext(
            conversationId = "debug-browser",
            agentId = 1L,
            assistantMode = AssistantMode.AGENT,
            agentCapabilityProfile = AgentCapabilityProfile(),
            workspacePath = filesDir.absolutePath
        )
        suspend fun action(action: String, params: Map<String, Any?> = emptyMap(), expectSuccess: Boolean = true) {
            val started = System.currentTimeMillis()
            val result = runCatching {
                manager.executeBrowserTask(mapOf("action" to action, "params" to params, "reset_task" to true), context)
            }.getOrElse { "EXCEPTION ${it.stackTraceToString()}" }
            val status = runCatching { JSONObject(result).optString("status") }.getOrDefault("exception")
            val success = status == "completed"
            val row = JSONObject().apply {
                put("kind", "browser_action")
                put("action", action)
                put("expected_exposed", action in BrowserActionCatalog.names)
                put("expected", if (expectSuccess) "completed" else "error")
                put("actual", status)
                put("passed", success == expectSuccess)
                put("duration_ms", System.currentTimeMillis() - started)
                put("return", result)
                put("state", JSONObject().put("url", manager.uiState.value.activeUrl).put("error", manager.uiState.value.lastError ?: JSONObject.NULL))
            }
            report += row
            emit(row.toString(2))
        }
        suspend fun check(name: String, timeoutMs: Long = 2_000, predicate: () -> Boolean) {
            val started = System.currentTimeMillis()
            while (!predicate() && System.currentTimeMillis() - started < timeoutMs) delay(50)
            val row = JSONObject().apply {
                put("kind", "runtime_check")
                put("name", name)
                put("expected", true)
                put("actual", predicate())
                put("passed", predicate())
                put("duration_ms", System.currentTimeMillis() - started)
            }
            report += row
            emit(row.toString(2))
        }
        try {
            // Baseline action coverage.
            action("open_url", mapOf("url" to server.url))
            action("observe")
            action("get_html")
            action("get_content")
            action("find_element", mapOf("query" to "Name"))
            action("wait_for_selector", mapOf("query" to "Name", "timeout_ms" to 2_000))
            action("evaluate", mapOf("expression" to "document.title === 'Amaya test' ? 'ok' : (() => { throw new Error('wrong title') })()"))
            action("type", mapOf("element_id" to "#name", "text" to "Amaya", "append" to false))
            action("clear_input", mapOf("element_id" to "#name"))
            action("select_option", mapOf("element_id" to "#choice", "value" to "two"))
            action("hover", mapOf("element_id" to "#hover"))
            action("click", mapOf("element_id" to "#click"))
            action("press_key", mapOf("key" to "ENTER"))
            action("scroll", mapOf("direction" to "down", "amount" to "small"))
            action("search", mapOf("query" to "query", "text" to "gecko"))
            action("wait_for_nav", mapOf("timeout_ms" to 1_500))

            // SPA: async hydration plus History API, no full document navigation.
            action("open_url", mapOf("url" to "${server.url}spa"))
            action("wait_for_selector", mapOf("query" to "SPA ready", "timeout_ms" to 3_000))
            action("click", mapOf("element_id" to "#spa-next"))
            action("evaluate", mapOf("expression" to "location.pathname === '/spa/step-2' && document.body.innerText.includes('SPA step 2') ? 'ok' : (() => { throw new Error('SPA state lost') })()"))
            action("go_back")
            action("go_forward")

            // Login/OTP: only synthetic values; no external account or credential.
            action("open_url", mapOf("url" to "${server.url}login"))
            action("type", mapOf("element_id" to "#username", "text" to "debug-user", "append" to false))
            action("type", mapOf("element_id" to "#password", "text" to "debug-password", "append" to false))
            action("click", mapOf("element_id" to "#login-submit"))
            action("wait_for_selector", mapOf("query" to "#otp", "timeout_ms" to 3_000))
            action("type", mapOf("element_id" to "#otp", "text" to "123456", "append" to false))
            action("click", mapOf("element_id" to "#otp-submit"))
            action("evaluate", mapOf("expression" to "document.title === 'Dashboard' ? 'ok' : (() => { throw new Error('OTP flow failed') })()"))

            // Download: browser-side Content-Disposition attachment, then workspace persistence.
            action("open_url", mapOf("url" to "${server.url}downloads"))
            action("click", mapOf("element_id" to "#download-report"))
            check("download_saved_to_workspace_once", 5_000) { manager.uiState.value.downloads.count { it.fileName.startsWith("report") && it.size > 0 } == 1 }

            // File chooser is an Android user interaction. Verify the headless path does
            // not deadlock; the visible suite completes it with the same-session picker.
            action("open_url", mapOf("url" to "${server.url}upload"))
            action("click", mapOf("element_id" to "#upload-file"), expectSuccess = false)
            check("visible_upload_picker_requested") { manager.uiState.value.uploadPending }
            val source = File(filesDir, "debug-upload.txt").apply { writeText("visible upload payload") }
            val uri = FileProvider.getUriForFile(this@BrowserDebugActivity, "$packageName.fileprovider", source)
            manager.provideUploadUris(arrayOf(uri))
            check("visible_upload_picker_completed", 5_000) { !manager.uiState.value.uploadPending }
            delay(2_000)
            action("evaluate", mapOf("expression" to "JSON.stringify({count:document.querySelector('#upload-file').files.length,name:document.querySelector('#upload-file').files[0]?.name || '',result:document.querySelector('#upload-result').textContent})"))

            // Local challenge fixtures exercise product detection, waiting, handoff, and resume.
            action("open_url", mapOf("url" to "${server.url}captcha-copy"))
            action("evaluate", mapOf("expression" to "document.title === 'Normal article' ? 'ok' : (() => { throw new Error('false positive CAPTCHA detection') })()"))
            action("open_url", mapOf("url" to "${server.url}challenge-managed"))
            action("evaluate", mapOf("expression" to "document.title === 'Managed challenge complete' ? 'ok' : (() => { throw new Error('managed challenge did not clear') })()"))
            action("open_url", mapOf("url" to "${server.url}challenge-checkbox?test_auto_human=1"), expectSuccess = false)
            check("interactive-human-verification-pauses") { manager.uiState.value.humanVerificationRequired && manager.uiState.value.isPaused }
            action("resume_session", expectSuccess = false)
            delay(3_000)
            action("resume_session")
            action("evaluate", mapOf("expression" to "document.title === 'Challenge complete' ? 'ok' : (() => { throw new Error('challenge still active') })()"))

            // Cross-document edges. Iframe/shadow interactions are intentionally probed, not hidden.
            action("open_url", mapOf("url" to "${server.url}iframe"))
            action("evaluate", mapOf("expression" to "document.querySelector('#test-frame') ? 'iframe_detected' : (() => { throw new Error('iframe missing') })()"))
            action("open_url", mapOf("url" to "${server.url}shadow"))
            action("find_element", mapOf("query" to "Shadow action"), expectSuccess = false)
            action("evaluate", mapOf("expression" to "document.querySelector('test-shadow').shadowRoot.querySelector('button').textContent === 'Shadow action' ? 'shadow_root_present' : (() => { throw new Error('shadow root missing') })()"))

            action("reload")
            action("screenshot")
            val initialPageId = manager.uiState.value.activeTabId.orEmpty()
            action("new_page")
            action("list_pages")
            action("switch_page", mapOf("page_id" to initialPageId))
            action("close_page")
        } finally {
            val passed = report.count { it.optBoolean("passed") }
            val summary = JSONObject().apply {
                put("passed", passed)
                put("failed", report.size - passed)
                put("total", report.size)
                put("policy", "Local deterministic simulation. Anti-bot/CAPTCHA bypass is intentionally excluded; challenge must be reported for manual verification.")
            }
            val file = File(getExternalFilesDir(null), "browser-debug-report.json")
            file.writeText(JSONObject().put("summary", summary).put("actions", JSONArray().apply { report.forEach(::put) }).toString(2))
            emit("SUMMARY ${summary}")
            emit("REPORT ${file.absolutePath}")
            server.close()
        }
    }

    private fun memoryMetrics(): Pair<Int, Int> {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        return info.totalPss to info.totalPrivateDirty
    }

    private fun emit(message: String) {
        Log.i("AmayaBrowserDebug", message)
        runOnUiThread { output.append("\n$message") }
    }

    private class TestPageServer : AutoCloseable {
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        val url = "http://127.0.0.1:${server.localPort}/"
        private val closed = AtomicBoolean(false)

        init {
            Thread {
                while (!closed.get()) runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (reader.readLine().orEmpty().isNotEmpty()) Unit
                        val target = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                        Log.i("AmayaBrowserDebug", "SERVER request=$request target=$target")
                        val response = responseFor(target)
                        socket.getOutputStream().use { stream ->
                            val headers = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: ${response.contentType}\r\n")
                                response.disposition?.let { append("Content-Disposition: $it\r\n") }
                                append("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n")
                            }
                            stream.write(headers.toByteArray())
                            stream.write(response.body)
                        }
                    }
                }
            }.apply { name = "AmayaBrowserDebugServer"; isDaemon = true }.start()
        }

        private data class Response(val body: ByteArray, val contentType: String = "text/html; charset=utf-8", val disposition: String? = null)
        private fun html(body: String) = Response("<!doctype html>$body".trimIndent().toByteArray())
        private fun responseFor(path: String): Response = when (path) {
            "/" -> html("""
                <title>Amaya test</title><label for=name>Name</label><input id=name><select id=choice><option value=one>One</option><option value=two>Two</option></select>
                <button id=click onclick=\"result.textContent='clicked'\">Click</button><button id=hover>Hover</button>
                <form action='/results'><input name=query><button>Search</button></form><p id=result>idle</p><div style='height:2400px'></div>
            """)
            "/spa" -> html("""
                <title>SPA loading</title><main id=app>Loading…</main><script>setTimeout(function(){document.title='SPA ready';var button=document.createElement('button');button.id='spa-next';button.textContent='Next';button.onclick=function(){history.pushState({},'', '/spa/step-2');document.title='SPA step 2';app.innerHTML='<h1>SPA step 2</h1>'};app.replaceChildren(button)},300)</script>
            """)
            "/spa/step-2" -> html("<title>SPA fallback</title><h1>SPA fallback route</h1>")
            "/intersection" -> html("""
                <title>Intersection test</title><main id=target style='height:40px'>Target</main><script>
                new IntersectionObserver(function(entries){if(entries.some(function(e){return e.isIntersecting})){var marker=document.createElement('p');marker.id='intersection-ready';marker.textContent='Intersection ready';document.body.appendChild(marker)}},{threshold:0}).observe(target)
                </script>
            """)
            "/restore" -> html("<title>Restore test</title><input id=restore-field><p>restore marker</p>")
            "/upload" -> html("<title>Upload test</title><input id=upload-file type=file accept='text/plain' onchange=\"uploadResult.textContent=this.files[0]?this.files[0].name:'none'\"><p id=upload-result>none</p>")
            "/lazy" -> html("""
                <title>Lazy test</title><input id=external-field><main id=app>Booting</main><div style='height:3000px'></div><script>
                setTimeout(function(){var marker=document.createElement('button');marker.id='lazy-ready';marker.textContent='Lazy ready';app.replaceChildren(marker)},500)
                </script>
            """)
            "/login" -> html("""
                <title>Login</title><form onsubmit="event.preventDefault(); history.pushState({},'', '/otp'); document.title='OTP verification'; document.body.innerHTML='<form id=otp-form><label>Verification code<input id=otp name=otp inputmode=numeric autocomplete=one-time-code></label><button id=otp-submit type=submit>Verify</button></form>'; document.querySelector('#otp-form').onsubmit=function(e){e.preventDefault(); history.pushState({},'', '/dashboard'); document.title='Dashboard'; document.body.innerHTML='<h1>Authenticated debug user</h1>'}"><label>User<input id=username name=username autocomplete=username></label><label>Password<input id=password name=password type=password autocomplete=current-password></label><button id=login-submit type=submit>Sign in</button></form>
            """)
            "/otp" -> html("""
                <title>OTP verification</title><form action='/dashboard'><label>Verification code<input id=otp name=otp inputmode=numeric autocomplete=one-time-code></label><button id=otp-submit type=submit>Verify</button></form>
            """)
            "/dashboard" -> html("<title>Dashboard</title><h1>Authenticated debug user</h1>")
            "/downloads" -> html("<title>Downloads</title><a id=download-report href='/download/report.txt'>Download report</a>")
            "/download/report.txt" -> Response("amaya browser debug report\n".toByteArray(), "application/octet-stream", "attachment; filename=report.txt")
            "/captcha-copy" -> html("<title>Normal article</title><main>This article explains CAPTCHA history and browser security.</main>")
            "/challenge-managed" -> html("""
                <title>Checking your browser</title><main><p>Managed security check</p></main>
                <script>setTimeout(function(){document.title='Managed challenge complete';document.querySelector('main').textContent='Verification complete'},700)</script>
            """)
            "/challenge-checkbox" -> html("""
                <title>Cloudflare-like challenge</title><main><h1>Checking your browser</h1><p>Verify you are human</p>
                <label><input id='human-check' type='checkbox' onchange="if(this.checked){document.title='Challenge complete';document.querySelector('main').textContent='Verification complete'}"> I am human</label>
                <button id='continue' disabled>Continue</button></main>
                <script>if(location.search.includes('test_auto_human=1'))setTimeout(function(){document.querySelector('#human-check').click()},4000)</script>
            """)
            "/iframe" -> html("<title>Iframe test</title><iframe id=test-frame title='Embedded content' src='/iframe-inner'></iframe>")
            "/iframe-inner" -> html("<title>Iframe child</title><button id=iframe-button>Inner action</button>")
            "/shadow" -> html("""
                <title>Shadow DOM test</title><test-shadow></test-shadow><script>customElements.define('test-shadow',class extends HTMLElement{connectedCallback(){this.attachShadow({mode:'open'}).innerHTML='<button>Shadow action</button>'}})</script>
            """)
            else -> html("<title>Results</title><p>Search result</p>")
        }

        override fun close() { closed.set(true); server.close() }
    }
}
