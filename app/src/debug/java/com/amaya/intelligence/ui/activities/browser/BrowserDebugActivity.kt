package com.amaya.intelligence.ui.activities.browser

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.domain.models.AgentCapabilityProfile
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.impl.local.browser.BrowserActionCatalog
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.tools.ToolExecutionContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        manager.resetForConversation("conversation:debug-browser", 1L)
        manager.setWorkspace(filesDir.absolutePath)
        output = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(manager.acquireSharedBrowserView(), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(output, LinearLayout.LayoutParams(-1, 260))
        setContentView(root)
        if (running.compareAndSet(false, true)) lifecycleScope.launch { runSuite() }
    }

    private suspend fun runSuite() {
        val report = mutableListOf<JSONObject>()
        val server = withContext(Dispatchers.IO) { TestPageServer() }
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
            action("wait_for_selector", mapOf("query" to "Verification code", "timeout_ms" to 3_000))
            action("type", mapOf("element_id" to "#otp", "text" to "123456", "append" to false))
            action("click", mapOf("element_id" to "#otp-submit"))
            action("evaluate", mapOf("expression" to "document.title === 'Dashboard' ? 'ok' : (() => { throw new Error('OTP flow failed') })()"))

            // Download: browser-side Content-Disposition attachment, then workspace persistence.
            action("open_url", mapOf("url" to "${server.url}downloads"))
            action("click", mapOf("element_id" to "#download-report"))
            check("download_saved_to_workspace_once", 5_000) { manager.uiState.value.downloads.count { it.fileName.startsWith("report") && it.size > 0 } == 1 }

            // Challenge detection only. Never solve/bypass CAPTCHA, fingerprint checks, rate limits, or OTP.
            action("open_url", mapOf("url" to "${server.url}challenge"))
            action("evaluate", mapOf("expression" to "(/captcha|checking your browser|verify you are human/i.test(document.body.innerText)) ? 'challenge_detected_manual_verification_required' : (() => { throw new Error('challenge not detected') })()"))

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
            val file = File(filesDir, "browser-debug-report.json")
            file.writeText(JSONObject().put("summary", summary).put("actions", JSONArray().apply { report.forEach(::put) }).toString(2))
            emit("SUMMARY ${summary}")
            emit("REPORT ${file.absolutePath}")
            server.close()
        }
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
            "/login" -> html("""
                <title>Login</title><form action='/otp'><label>User<input id=username name=username autocomplete=username></label><label>Password<input id=password name=password type=password autocomplete=current-password></label><button id=login-submit type=submit>Sign in</button></form>
            """)
            "/otp" -> html("""
                <title>OTP verification</title><form action='/dashboard'><label>Verification code<input id=otp name=otp inputmode=numeric autocomplete=one-time-code></label><button id=otp-submit type=submit>Verify</button></form>
            """)
            "/dashboard" -> html("<title>Dashboard</title><h1>Authenticated debug user</h1>")
            "/downloads" -> html("<title>Downloads</title><a id=download-report href='/download/report.txt'>Download report</a>")
            "/download/report.txt" -> Response("amaya browser debug report\n".toByteArray(), "application/octet-stream", "attachment; filename=report.txt")
            "/challenge" -> html("<title>Human verification</title><main><h1>Checking your browser</h1><p>CAPTCHA: verify you are human to continue.</p><button disabled>Continue</button></main>")
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
