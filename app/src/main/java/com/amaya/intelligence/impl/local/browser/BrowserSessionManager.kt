package com.amaya.intelligence.impl.local.browser

import android.content.Context
import android.net.Uri
import android.view.View
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.PowerManager
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import androidx.core.content.FileProvider
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.amaya.intelligence.tools.ToolExecutionContext

@Singleton
class BrowserSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class SessionKey(val conversationKey: String, val agentId: Long)

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val headlessSurfaceSlots = Semaphore(2)
    private val executionWakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amaya:browser-agent")
        .apply { setReferenceCounted(true) }
    private val sessions = object : LinkedHashMap<SessionKey, BrowserConversationSession>(8, 0.75f, true) {}
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()
    private var visibleKey: SessionKey? = null
    private var sharedViewOwner: SessionKey? = null
    private var visibleStateJob: Job? = null
    private var workspacePath: String? = null

    @Synchronized
    private fun sessionFor(key: SessionKey): BrowserConversationSession {
        sessions[key]?.let { return it }
        val session = BrowserConversationSession(context, headlessSurfaceSlots).apply {
            resetForConversation(key.conversationKey, key.agentId)
            setWorkspace(workspacePath)
        }
        sessions[key] = session
        return session
    }

    @Synchronized
    private fun selected(): BrowserConversationSession? = visibleKey?.let(sessions::get)

    @Synchronized
    private fun trimSessions() {
        while (sessions.size > MAX_RESIDENT_SESSIONS) {
            val removable = sessions.entries.firstOrNull {
                it.key != visibleKey && it.key != sharedViewOwner && !it.value.isExecuting()
            } ?: return
            sessions.remove(removable.key)
            removable.value.close()
        }
    }

    @Synchronized
    fun selectConversation(key: String, agentId: Long? = null) {
        if (agentId == null) {
            visibleKey = null
            visibleStateJob?.cancel()
            visibleStateJob = null
            _uiState.value = BrowserUiState()
            return
        }
        val sessionKey = SessionKey(key, agentId)
        val session = sessionFor(sessionKey)
        visibleKey = sessionKey
        trimSessions()
        visibleStateJob?.cancel()
        _uiState.value = session.uiState.value
        visibleStateJob = scope.launch { session.uiState.collect { _uiState.value = it } }
    }

    fun setWorkspace(path: String?) {
        workspacePath = path?.takeIf(String::isNotBlank)
        selected()?.setWorkspace(workspacePath)
    }

    fun canOpenOperator(): Boolean = selected()?.canOpenOperator() == true
    fun markAuthHandoffCompleted() { selected()?.markAuthHandoffCompleted() }
    fun releaseInactiveRuntimes() { synchronized(this) { sessions.values.toList() }.forEach(BrowserConversationSession::releaseInactiveRuntimes); trimSessions() }
    fun resetForConversation(key: String, agentId: Long? = null) = selectConversation(key, agentId)
    fun resetEphemeral() { selectConversation("", null) }
    fun sessionId(): String = selected()?.sessionId().orEmpty()
    fun takeLastScreenshotAttachment(executionContext: ToolExecutionContext): String? {
        val agentId = executionContext.agentId ?: return null
        val key = SessionKey(executionContext.conversationId?.let { "conversation:$it" } ?: "agent:$agentId", agentId)
        return synchronized(this) { sessions[key] }?.takeLastScreenshotAttachment()
    }
    suspend fun captureScreenshotToWorkspace(): BrowserToolResponse = selected()?.captureScreenshotToWorkspace() ?: BrowserToolResponse.Failure("No active browser session")
    fun clearActiveSiteData() { selected()?.clearActiveSiteData() }
    fun provideUploadUris(uris: Array<Uri>?) { selected()?.provideUploadUris(uris) }
    fun cancelPendingUpload() { selected()?.cancelPendingUpload() }
    fun openDownload(download: BrowserDownload): Uri? = selected()?.openDownload(download)
    fun deleteDownload(download: BrowserDownload) { selected()?.deleteDownload(download) }
    fun clearSessionState() { selected()?.clearSessionState() }
    suspend fun switchToTab(pageId: String): BrowserToolResponse = selected()?.switchToTab(pageId) ?: BrowserToolResponse.Failure("No active browser session")
    fun acquireSharedBrowserView(): GeckoView {
        val previous: BrowserConversationSession?
        val current: BrowserConversationSession
        synchronized(this) {
            val key = checkNotNull(visibleKey) { "No active browser session" }
            previous = sharedViewOwner?.takeIf { it != key }?.let(sessions::get)
            sharedViewOwner = key
            current = checkNotNull(sessions[key]) { "No active browser session" }
        }
        previous?.releaseSharedBrowserView()
        return current.acquireSharedBrowserView()
    }

    fun releaseSharedBrowserView() {
        val owner = synchronized(this) {
            sharedViewOwner?.let(sessions::get).also { sharedViewOwner = null }
        }
        owner?.releaseSharedBrowserView()
        trimSessions()
    }
    fun isDispatchingAgentInput(): Boolean = selected()?.isDispatchingAgentInput() == true
    fun hideSoftKeyboardForAgent() { selected()?.hideSoftKeyboardForAgent() }
    fun onAssistantStreamingChanged(streaming: Boolean) { selected()?.onAssistantStreamingChanged(streaming) }
    fun onAssistantTextDelta(delta: String) { selected()?.onAssistantTextDelta(delta) }
    fun onAgentTouch(x: Float, y: Float) { selected()?.onAgentTouch(x, y) }
    fun onBrowserError(message: String) { selected()?.onBrowserError(message) }
    suspend fun executeBrowserTask(arguments: Map<String, Any?>, executionContext: ToolExecutionContext = ToolExecutionContext()): String {
        val agentId = executionContext.agentId
            ?: return JSONObject().put("status", "error").put("error", "Browser requires an active Agent").toString(2)
        val key = SessionKey(executionContext.conversationId?.let { "conversation:$it" } ?: "agent:$agentId", agentId)
        val session = synchronized(this) { sessionFor(key).also { it.retain() } }
        executionWakeLock.acquire(BROWSER_ACTION_WAKE_TIMEOUT_MS)
        return try {
            session.setWorkspace(executionContext.workspacePath?.takeIf(String::isNotBlank) ?: workspacePath)
            session.executeBrowserTask(arguments, executionContext)
        } finally {
            if (executionWakeLock.isHeld) executionWakeLock.release()
            session.release()
            trimSessions()
        }
    }
    suspend fun execute(toolName: String, arguments: Map<String, Any?>): BrowserToolResponse =
        selected()?.execute(toolName, arguments) ?: BrowserToolResponse.Failure("No active browser session")
    fun cancelFromUser() { selected()?.cancelFromUser() }

    companion object {
        private const val MAX_RESIDENT_SESSIONS = 6
        private const val BROWSER_ACTION_WAKE_TIMEOUT_MS = 60_000L
    }
}

private class BrowserConversationSession(
    private val context: Context,
    private val headlessSurfaceSlots: Semaphore,
) {
    private var sessionId = newSessionId()
    private val initialTab = BrowserPageTab()
    private val _uiState = MutableStateFlow(BrowserUiState(
        sessionId = sessionId,
        activeTabId = initialTab.id,
        tabs = listOf(initialTab)
    ))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private class PageRuntime(
        val tabId: String,
        val view: GeckoView,
        val session: GeckoSession,
        val controller: AndroidBrowserController,
        var display: GeckoDisplay? = null,
        var imageReader: ImageReader? = null
    )

    @Volatile private var controller: AndroidBrowserController? = null
    private val pageRuntimes = mutableMapOf<String, PageRuntime>()
    private val assistantStreamBuffer = StringBuilder()
    private var lastAssistantStreamUiEmitAt = 0L

    private val browserId = "browser_local_001"
    private var parentTaskId = "browser_task_${UUID.randomUUID().toString().take(8)}"
    private var parentStartedAt = BrowserResponseFormatter.nowIso()
    private var parentSummary = "Browser task"
    private val parentSubToolcalls = mutableListOf<JSONObject>()
    private var pendingApprovalCallId: String? = null
    private var pendingApprovalStepIndex: Int? = null
    private var conversationKey: String? = null
    private var visibleConversationKey: String? = null
    private var visibleAgentId: Long? = null
    private var pendingRestoreUrl: String? = null
    private val prefs = context.getSharedPreferences("browser_sessions", Context.MODE_PRIVATE)
    private val executionMutex = Mutex()
    @Volatile private var fileChooserCallback: ((Array<Uri>?) -> Unit)? = null
    @Volatile private var queuedUploadDecision = false
    @Volatile private var queuedUploadUris: Array<Uri>? = null
    @Volatile private var pendingUploadAcceptTypes: Array<String> = emptyArray()
    @Volatile private var pendingUploadMultiple: Boolean = false
    private var workspacePath: String? = null
    private var lastDownloadUri: String? = null
    private var lastDownloadAtMs = 0L
    private val resumeScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    @Volatile private var visibleHost = false
    private var headlessSurfaceHeld = false
    private val clients = AtomicInteger()

    fun setWorkspace(path: String?) { workspacePath = path?.takeIf(String::isNotBlank) }

    fun retain() { clients.incrementAndGet() }
    fun release() { clients.decrementAndGet() }
    fun isExecuting(): Boolean = clients.get() > 0 || executionMutex.isLocked

    fun close() {
        detachHeadlessSurface()
        pageRuntimes.values.forEach { runtime ->
            runtime.controller.resetToBlank()
            GeckoBrowserRuntime.detach(runtime.session)
            runtime.session.close()
        }
        pageRuntimes.clear()
        controller = null
        resumeScope.cancel()
    }

    fun canOpenOperator(): Boolean = _uiState.value.agentId != null && conversationKey != null

    fun markAuthHandoffCompleted() { _uiState.update { it.copy(currentAction = "External verification completed") } }

    fun releaseInactiveRuntimes() {
        val active = _uiState.value.activeTabId
        pageRuntimes.keys.filter { it != active }.forEach { id ->
            pageRuntimes.remove(id)?.let { runtime ->
                runtime.controller.resetToBlank()
                runtime.session.setActive(false)
                GeckoBrowserRuntime.detach(runtime.session)
                runtime.session.close()
            }
        }
    }

    fun selectConversation(key: String, agentId: Long? = null) {
        visibleConversationKey = key
        visibleAgentId = agentId
        resetForConversation(key, agentId)
    }

    fun resetForConversation(key: String, agentId: Long? = null) {
        if (conversationKey == key && _uiState.value.agentId == agentId) return
        conversationKey = key
        sessionId = stableSessionId(key, agentId)
        pageRuntimes.values.forEach { runtime ->
            runtime.controller.resetToBlank()
            GeckoBrowserRuntime.detach(runtime.session)
            runtime.session.close()
        }
        pageRuntimes.clear()
        controller = null
        pendingRestoreUrl = restoreState(sessionId)
        parentTaskId = "browser_task_${UUID.randomUUID().toString().take(8)}"
        parentStartedAt = BrowserResponseFormatter.nowIso()
        parentSummary = "Browser task"
        parentSubToolcalls.clear()
        val restoredTabs = restoreTabs(sessionId)
        val restoredHistory = restoreHistory(sessionId)
        val tab = restoredTabs.firstOrNull() ?: BrowserPageTab()
        _uiState.value = BrowserUiState(
            sessionId = sessionId,
            browserId = browserId,
            conversationKey = conversationKey,
            agentId = agentId,
            activeTabId = prefs.getString("${sessionId}.active_tab_id", null)
                ?.takeIf { id -> restoredTabs.any { it.id == id } }
                ?: tab.id,
            tabs = restoredTabs.ifEmpty { listOf(tab) },
            sessionHistory = restoredHistory
        )
    }

    fun resetEphemeral() {
        conversationKey = null
        visibleConversationKey = null
        visibleAgentId = null
        sessionId = newSessionId()
        pageRuntimes.values.forEach { runtime ->
            runtime.controller.resetToBlank()
            GeckoBrowserRuntime.detach(runtime.session)
            runtime.session.close()
        }
        pageRuntimes.clear()
        controller = null
        parentSubToolcalls.clear()
        val tab = BrowserPageTab()
        _uiState.value = BrowserUiState(
            sessionId = sessionId,
            browserId = browserId,
            activeTabId = tab.id,
            tabs = listOf(tab)
        )
    }

    fun sessionId(): String = sessionId

    fun takeLastScreenshotAttachment(): String? = _uiState.value.screenshotBase64.also {
        if (it != null) _uiState.update { state -> state.copy(screenshotBase64 = null) }
    }

    suspend fun captureScreenshotToWorkspace(): BrowserToolResponse {
        val root = workspacePath?.let(::File)?.let { File(it, ".amaya/browser/screenshots") }
            ?: return BrowserToolResponse.Failure("Screenshot blocked: select a workspace first")
        val result = execute("get_screenshot", emptyMap())
        val success = result as? BrowserToolResponse.Success ?: return result
        val image = success.metadata["image_base64"] as? String ?: return BrowserToolResponse.Failure("Screenshot image was empty")
        return runCatching {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                val file = uniqueFile(root, "${System.currentTimeMillis()}.jpg")
                file.writeBytes(android.util.Base64.decode(image, android.util.Base64.DEFAULT))
                success.copy(output = "Screenshot saved", metadata = success.metadata + ("relative_path" to ".amaya/browser/screenshots/${file.name}"))
            }
        }.getOrElse { BrowserToolResponse.Failure("Screenshot failed: ${it.message ?: "unknown error"}") }
    }

    fun clearActiveSiteData() {
        val parsed = Uri.parse(_uiState.value.activeUrl)
        val origin = parsed.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { "${it.scheme}://${it.authority}" } ?: return
        GeckoBrowserRuntime.clearHostData(context, parsed.host ?: return)
        _uiState.update { it.copy(currentAction = "Cleared site data for $origin") }
    }

    fun provideUploadUris(uris: Array<Uri>?) {
        val callback = fileChooserCallback
        if (callback == null) {
            // Gecko can deliver the file prompt one main-loop turn after the click.
            // Keep the user's selection instead of dropping it during that race.
            queuedUploadDecision = true
            queuedUploadUris = uris
            return
        }
        fileChooserCallback = null
        queuedUploadDecision = false
        queuedUploadUris = null
        if (uris.isNullOrEmpty()) _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
        if (uris.isNullOrEmpty()) {
            callback(null)
            return
        }
        val selected = if (pendingUploadMultiple) uris.toList() else uris.take(1)
        val acceptTypes = pendingUploadAcceptTypes.filter(String::isNotBlank)
        resumeScope.launch(Dispatchers.IO) {
            val staged = runCatching {
                val root = workspacePath?.let(::File)?.let { File(it, ".amaya/browser/uploads") }
                    ?: error("Upload blocked: select a workspace first")
                root.mkdirs()
                selected.mapNotNull { uri ->
                    val type = context.contentResolver.getType(uri).orEmpty()
                    if (acceptTypes.isNotEmpty() && acceptTypes.none { matchesMime(it, type) }) {
                        error("File does not match the web input format: ${queryDisplayName(uri) ?: uri}")
                    }
                    val name = sanitizeFileName(queryDisplayName(uri) ?: "upload")
                    val target = uniqueFile(root, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot read selected file")
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target).also {
                        context.grantUriPermission(context.packageName, it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }.toTypedArray()
            }
            withContext(Dispatchers.Main.immediate) {
                staged.onSuccess { values ->
                    if (values.isEmpty()) callback(null) else callback(values)
                    _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
                }.onFailure {
                    callback(null)
                    _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
                    onBrowserError(it.message ?: "Upload failed")
                }
            }
        }
    }

    fun cancelPendingUpload() {
        val callback = fileChooserCallback
        fileChooserCallback = null
        queuedUploadDecision = false
        queuedUploadUris = null
        pendingUploadAcceptTypes = emptyArray()
        pendingUploadMultiple = false
        _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
        callback?.invoke(null)
    }

    private fun matchesMime(pattern: String, actual: String): Boolean = pattern == "*/*" || pattern == actual || (pattern.endsWith("/*") && actual.startsWith(pattern.removeSuffix("*")))

    private fun queryDisplayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun sanitizeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "upload" }

    fun openDownload(download: BrowserDownload): Uri? = workspacePath?.let(::File)?.let { root ->
        val file = File(root, download.relativePath.removePrefix(".amaya/browser/downloads/")).takeIf(File::isFile) ?: return@let null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun deleteDownload(download: BrowserDownload) {
        workspacePath?.let(::File)?.let { root -> File(root, download.relativePath.removePrefix(".amaya/browser/downloads/")).delete() }
        _uiState.update { it.copy(downloads = it.downloads.filterNot { item -> item.relativePath == download.relativePath }) }
    }

    fun clearSessionState() {
        if (conversationKey == null) return
        prefs.edit().remove("${sessionId}.tabs").remove("${sessionId}.history").remove("${sessionId}.active_url").remove("${sessionId}.active_title").remove("${sessionId}.active_tab_id").apply()
        resetEphemeral()
    }

    private fun uniqueFile(root: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf { it != name }
        var index = 0
        var candidate = File(root, name)
        while (candidate.exists()) {
            index++
            candidate = File(root, "$base ($index)${extension?.let { ".${it}" }.orEmpty()}")
        }
        return candidate
    }

    @Synchronized
    private fun handleGeckoDownload(response: WebResponse) {
        val now = System.currentTimeMillis()
        if (response.uri == lastDownloadUri && now - lastDownloadAtMs < 1_000) {
            response.body?.close()
            android.util.Log.i("AmayaBrowser", "download duplicate ignored uri=${response.uri}")
            return
        }
        lastDownloadUri = response.uri
        lastDownloadAtMs = now
        val root = workspacePath?.let(::File)?.let { File(it, ".amaya/browser/downloads") } ?: run { onBrowserError("Download blocked: select a workspace first"); response.body?.close(); return }
        android.util.Log.i("AmayaBrowser", "download callback uri=${response.uri} status=${response.statusCode} hasBody=${response.body != null}")
        resumeScope.launch(Dispatchers.IO) {
            runCatching {
                root.mkdirs()
                val url = response.uri
                val mimeType = response.headers["content-type"] ?: "application/octet-stream"
                val name = URLUtil.guessFileName(url, response.headers["content-disposition"], mimeType).replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "download" }
                val target = uniqueFile(root, name)
                var total = 0L
                (response.body ?: error("Download body unavailable")).use { input -> FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count; output.write(buffer, 0, count) }
                } }
                val relative = ".amaya/browser/downloads/${target.name}"
                android.util.Log.i("AmayaBrowser", "download saved path=$relative bytes=$total")
                withContext(Dispatchers.Main.immediate) { _uiState.update { it.copy(downloads = (it.downloads + BrowserDownload(target.name, relative, mimeType, target.length())).takeLast(50)) } }
            }.onFailure { error ->
                android.util.Log.e("AmayaBrowser", "download failed", error)
                withContext(Dispatchers.Main.immediate) { onBrowserError("Download failed: ${error.message ?: "unknown error"}") }
            }
        }
    }


    suspend fun switchToTab(pageId: String): BrowserToolResponse = execute("switch_tab", mapOf("page_id" to pageId))

    private fun newSessionId(): String = "sess_android_${UUID.randomUUID().toString().take(8)}"

    private fun stableSessionId(key: String, agentId: Long?): String = "sess_android_${UUID.nameUUIDFromBytes("$key|agent:$agentId".toByteArray()).toString().take(8)}"

    private fun restoreState(id: String): String? = prefs.getString("$id.active_url", null)

    private fun restoreTabs(id: String): List<BrowserPageTab> = runCatching {
        val array = JSONArray(prefs.getString("$id.tabs", "[]"))
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

    private fun restoreHistory(id: String): List<BrowserHistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString("$id.history", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.optString("url").takeIf(String::isNotBlank)?.let { BrowserHistoryEntry(it, item.optString("title", it), item.optLong("visited_at")) }
        }.takeLast(60)
    }.getOrDefault(emptyList())

    private fun persistState(state: BrowserUiState) {
        val active = state.tabs.firstOrNull { it.id == state.activeTabId } ?: return
        prefs.edit()
            .putString("${state.sessionId}.active_url", active.url)
            .putString("${state.sessionId}.active_title", active.title)
            .putString("${state.sessionId}.active_tab_id", state.activeTabId.orEmpty())
            .putString("${state.sessionId}.tabs", JSONArray().apply {
                state.tabs.forEach { tab -> put(JSONObject().apply {
                    put("id", tab.id); put("title", tab.title); put("url", tab.url)
                    put("can_go_back", tab.canGoBack); put("can_go_forward", tab.canGoForward)
                    put("scroll_x", tab.scrollX); put("scroll_y", tab.scrollY)
                }) }
            }.toString())
            .putString("${state.sessionId}.history", JSONArray().apply {
                state.sessionHistory.forEach { entry -> put(JSONObject().apply {
                    put("url", entry.url); put("title", entry.title); put("visited_at", entry.visitedAt)
                }) }
            }.toString())
            .apply()
    }

    fun acquireSharedBrowserView(): GeckoView {
        if (headlessSurfaceHeld) detachHeadlessSurface()
        visibleHost = true
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Browser view must be acquired on the main thread"
        }
        val view = ensureSharedControllerOnMain().first
        controller?.setVisibleFileChooserHost(true)
        pageRuntimes.forEach { (tabId, runtime) ->
            val active = tabId == _uiState.value.activeTabId
            runtime.session.setActive(active)
            runtime.session.setPriorityHint(if (active) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        }
        if (!pendingRestoreUrl.isNullOrBlank() && controller?.currentUrl() == "about:blank") {
            val url = pendingRestoreUrl ?: return view
            pendingRestoreUrl = null
            controller?.session?.loadUri(url)
        }
        return view
    }

    fun releaseSharedBrowserView() {
        visibleHost = false
        controller?.setVisibleFileChooserHost(false)
        detachHeadlessSurface()
        pageRuntimes.values.forEach { runtime ->
            runtime.session.setActive(false)
            runtime.session.setFocused(false)
            runtime.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }
    }

    fun isDispatchingAgentInput(): Boolean = controller?.isDispatchingAgentInput == true

    fun hideSoftKeyboardForAgent() {
        controller?.hideSoftKeyboard()
    }

    private suspend fun attachHeadlessSurfaceOnMain() {
        val tabId = _uiState.value.activeTabId ?: return
        val runtime = pageRuntimes[tabId] ?: return
        if (runtime.view.isAttachedToWindow) return
        if (runtime.display != null) return
        if (headlessSurfaceHeld) detachHeadlessSurface(releaseSlot = false) else headlessSurfaceSlots.acquire()
        val imageReader = ImageReader.newInstance(1080, 1920, PixelFormat.RGBX_8888, 2).apply {
            setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, null)
        }
        val display = runtime.session.acquireDisplay()
        display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(imageReader.surface).size(1080, 1920).build())
        runtime.imageReader = imageReader
        runtime.display = display
        runtime.session.setActive(true)
        runtime.session.setFocused(true)
        runtime.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        headlessSurfaceHeld = true
    }

    private fun detachHeadlessSurface(releaseSlot: Boolean = true) {
        val hasDisplay = pageRuntimes.values.any { it.display != null }
        if (!headlessSurfaceHeld && !hasDisplay) return
        pageRuntimes.values.forEach { runtime ->
            runtime.display?.let { display ->
                runCatching { display.surfaceDestroyed() }
                runCatching { runtime.session.releaseDisplay(display) }
            }
            runtime.imageReader?.close()
            runtime.display = null
            runtime.imageReader = null
            runtime.session.setActive(false)
            runtime.session.setFocused(false)
            runtime.session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }
        val releasePermit = headlessSurfaceHeld
        headlessSurfaceHeld = false
        if (releaseSlot && releasePermit) headlessSurfaceSlots.release()
    }

    private fun ensureSharedControllerOnMain(): Pair<GeckoView, AndroidBrowserController> {
        val tabId = _uiState.value.activeTabId ?: BrowserPageTab().id
        pageRuntimes[tabId]?.let { runtime ->
            controller = runtime.controller
            return runtime.view to runtime.controller
        }
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: BrowserPageTab(id = tabId)
        val session = GeckoSession()
        session.open(GeckoBrowserRuntime.get(context))
        val view = GeckoView(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setSession(session)
            // Headless Gecko still needs a logical viewport for native file-input
            // gestures; the offscreen display supplies the actual pixels.
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        session.setActive(false)
        session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        val newController = AndroidBrowserController(
            geckoView = view,
            session = session,
            capturePixels = { pageRuntimes[tabId]?.display?.capturePixels() ?: view.capturePixels() },
            onNavigationChanged = { url, title, progress, back, forward ->
                onNavigationChanged(tabId, url, title, progress, back, forward)
            },
            onScrollChanged = { x, y -> onScrollChanged(tabId, x, y) },
            onError = this::onBrowserError,
            onAgentTouch = this::onAgentTouch,
            onDownload = this::handleGeckoDownload,
            onFileChooser = { acceptTypes, multiple, callback ->
                fileChooserCallback?.invoke(null)
                fileChooserCallback = callback
                pendingUploadAcceptTypes = acceptTypes
                pendingUploadMultiple = multiple
                _uiState.update { it.copy(uploadPending = true, uploadAcceptTypes = acceptTypes.filter(String::isNotBlank), uploadRequestNonce = System.currentTimeMillis()) }
                if (queuedUploadDecision) {
                    val queued = queuedUploadUris
                    queuedUploadDecision = false
                    queuedUploadUris = null
                    if (queued.isNullOrEmpty()) {
                        callback(null)
                    } else {
                        // Agent-selected workspace files already have stable FileProvider
                        // URIs. Pass them directly; staging here changes the filename and
                        // breaks the upload postcondition.
                        callback(queued)
                        _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
                    }
                }
            }
        )
        pageRuntimes[tabId] = PageRuntime(tabId, view, session, newController)
        controller = newController
        _uiState.update { state ->
            state.copy(
                sessionId = sessionId,
                browserId = browserId,
                conversationKey = conversationKey,
                status = BrowserAgentStatus.IDLE,
                currentAction = "Browser ready",
                lastError = null
            )
        }
        return view to newController
    }

    private fun onScrollChanged(tabId: String, x: Int, y: Int) {
        _uiState.update { state -> state.copy(tabs = state.tabs.map { tab -> if (tab.id == tabId) tab.copy(scrollX = x, scrollY = y) else tab }) }
        persistState(_uiState.value)
    }

    private fun onNavigationChanged(tabId: String, url: String, title: String, progress: Float, canGoBack: Boolean, canGoForward: Boolean) {
        // Gecko may still deliver a final callback after a tab is removed.
        if (_uiState.value.tabs.none { it.id == tabId }) return
        _uiState.update { state ->
            val isActive = tabId == state.activeTabId
            val tabs = state.tabs.map { tab ->
                if (tab.id == tabId) tab.copy(title = title.ifBlank { url }, url = url, canGoBack = canGoBack, canGoForward = canGoForward) else tab
            }
            state.copy(
                activeUrl = if (isActive) url else state.activeUrl,
                activeTitle = if (isActive) title.ifBlank { url } else state.activeTitle,
                progress = if (isActive) progress else state.progress,
                tabs = tabs,
                sessionHistory = if (!isActive || url == "about:blank" || state.sessionHistory.lastOrNull()?.url == url) state.sessionHistory else
                    (state.sessionHistory + BrowserHistoryEntry(url, title.ifBlank { url })).takeLast(60)
            )
        }
        persistState(_uiState.value)
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

    suspend fun executeBrowserTask(
        arguments: Map<String, Any?>,
        executionContext: ToolExecutionContext = ToolExecutionContext()
    ): String = executionMutex.withLock {
        val restoreKey = visibleConversationKey
        val restoreAgentId = visibleAgentId
        var needsHeadlessSurface = false
        try {
        if (executionContext.assistantMode != com.amaya.intelligence.domain.models.AssistantMode.AGENT) {
            return@withLock JSONObject().put("status", "error").put("error", "Browser is available only in Agent mode").toString(2)
        }
        if (executionContext.agentCapabilityProfile?.browser == false) {
            return@withLock JSONObject().put("status", "error").put("error", "Browser capability is disabled for this Agent").toString(2)
        }
        if (executionContext.agentId == null) {
            return@withLock JSONObject().put("status", "error").put("error", "Browser requires an active Agent").toString(2)
        }
        val contextKey = executionContext.conversationId?.let { "conversation:$it" }
            ?: "agent:${executionContext.agentId}"
        workspacePath = executionContext.workspacePath?.takeIf(String::isNotBlank) ?: workspacePath
        if (conversationKey != contextKey || _uiState.value.agentId != executionContext.agentId) {
            resetForConversation(contextKey, executionContext.agentId)
        }
        _uiState.update { it.copy(browserAccessActive = true) }
        needsHeadlessSurface = !visibleHost
        withContext(Dispatchers.Main.immediate) {
            ensureSharedControllerOnMain()
            if (needsHeadlessSurface) attachHeadlessSurfaceOnMain()
        }
        val callId = executionContext.toolCallId
            ?: "browser_task_${UUID.randomUUID().toString().take(8)}"
        val resumeStep = pendingApprovalStepIndex.takeIf {
            executionContext.confirmed && pendingApprovalCallId == callId
        }
        val reset = resumeStep == null && (arguments["reset_task"] == true || parentSubToolcalls.isEmpty())
        if (reset) {
            parentTaskId = callId
            parentStartedAt = BrowserResponseFormatter.nowIso()
            parentSubToolcalls.clear()
            pendingApprovalCallId = null
            pendingApprovalStepIndex = null
        }
        arguments["task"]?.toString()?.takeIf { it.isNotBlank() }?.let { parentSummary = it.take(160) }

        val steps = parseSteps(arguments)
        val totalSteps = steps.size.coerceAtLeast(1)
        var lastLabel = "Preparing browser"
        steps.forEachIndexed { index, step ->
            if (resumeStep != null && index < resumeStep) return@forEachIndexed
            val action = normalizeActionName(step.first)
            val params = if (executionContext.confirmed) step.second + ("__confirmed" to true) else step.second
            lastLabel = readableAction(action)
            val sub = executeNormalizedSubtool(
                parentCallId = parentTaskId,
                action = action,
                params = params,
                currentStep = index + 1,
                totalSteps = totalSteps
            )
            parentSubToolcalls += sub
            if (sub.optJSONObject("error")?.optString("code") == "USER_APPROVAL_REQUIRED") {
                pendingApprovalCallId = callId
                pendingApprovalStepIndex = index
            } else if (resumeStep == index) {
                pendingApprovalCallId = null
                pendingApprovalStepIndex = null
            }
            if (sub.optString("status") in setOf("paused", "cancelled", "error", "timeout")) {
                return parentSnapshot(lastLabel, index + 1, totalSteps).toString(2)
            }
        }
        parentSnapshot(lastLabel, totalSteps, totalSteps).toString(2)
        } finally {
            if (needsHeadlessSurface) withContext(Dispatchers.Main.immediate) { detachHeadlessSurface() }
            if (restoreKey != null && restoreKey != conversationKey) {
                resetForConversation(restoreKey, restoreAgentId)
            }
        }
    }

    private suspend fun executeNormalizedSubtool(
        parentCallId: String,
        action: String,
        params: Map<String, Any?>,
        currentStep: Int,
        totalSteps: Int
    ): JSONObject {
        if (!visibleHost) withContext(Dispatchers.Main.immediate) { attachHeadlessSurfaceOnMain() }
        hydrateRestoredPageIfNeeded(action)
        val started = System.currentTimeMillis()
        val internalName = actionToInternalTool(action)
        _uiState.update { it.copy(progress = currentStep.toFloat() / totalSteps.toFloat(), currentAction = readableAction(action)) }
        val rawResponse = when (action) {
            "get_status" -> BrowserToolResponse.Success("Browser status read", currentSessionMetadata())
            "analyze_page" -> execute("get_dom", params)
            else -> execute(internalName, params)
        }
        val response = rawResponse
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
        val topLevelParams = arguments.filterKeys { it !in setOf("task", "action", "params", "steps", "reset_task", "debug", "parent_call_id") }
        val nestedParams = (arguments["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        return listOf(action to (topLevelParams + nestedParams))
    }

    private fun normalizeActionName(raw: String): String {
        val name = raw.removePrefix("browser.").trim()
        return when (name) {
            "observe", "analyze_page", "get_snapshot" -> "get_dom"
            "get_content" -> "get_visible_text"
            "wait_for_selector" -> "wait_for_element"
            "wait_for_nav", "wait_for_navigation" -> "wait_for_navigation"
            "list_pages" -> "get_status"
            "switch_page" -> "switch_tab"
            "type" -> "type_text"
            "click_element" -> "click"
            "scroll_page", "swipe" -> "scroll"
            "get_screenshot" -> "screenshot"
            "reload_page" -> "reload"
            "new_page" -> "new_tab"
            "close_page" -> "close_tab"
            "expression", "run_js", "run_javascript" -> "evaluate"
            else -> name
        }
    }

    private fun actionToInternalTool(action: String): String = when (action) {
        "new_tab" -> "new_page"
        "close_tab" -> "close_page"
        "list_pages" -> "get_status"
        "switch_page" -> "switch_tab"
        "wait_for_selector" -> "wait_for_element"
        "wait_for_nav" -> "wait_for_navigation"
        "get_content" -> "get_visible_text"
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
        put("pages", BrowserResponseFormatter.toJsonValue(pageList()))
    }

    private fun pageList(): List<Map<String, Any>> = _uiState.value.tabs.map { tab -> mapOf(
        "page_id" to tab.id,
        "active" to (tab.id == _uiState.value.activeTabId),
        "title" to tab.title,
        "url" to tab.url,
        "can_go_back" to tab.canGoBack,
        "can_go_forward" to tab.canGoForward
    ) }

    private fun currentSessionMetadata(): Map<String, Any> = mapOf(
        "url" to _uiState.value.activeUrl,
        "title" to _uiState.value.activeTitle,
        "session_id" to sessionId,
        "browser_id" to browserId,
        "active_page_id" to (_uiState.value.activeTabId ?: ""),
        "pages" to pageList(),
        "status" to _uiState.value.status.name.lowercase()
    )

    suspend fun execute(toolName: String, arguments: Map<String, Any?>): BrowserToolResponse {
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
                        status = BrowserAgentStatus.IDLE,
                        currentAction = "Completed ${readableAction(toolName)}",
                        screenshotBase64 = image ?: it.screenshotBase64,
                        evaluateResult = if (toolName == "evaluate") result.output else it.evaluateResult,
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
        }
        return result
    }

    fun cancelFromUser() {
        controller?.cancel()
        _uiState.update {
            it.copy(
                status = BrowserAgentStatus.CANCELLED,
                currentAction = "Cancelled by user",
                isCancelled = true
            )
        }
        appendLog("cancel_action", "", "cancelled", "Cancelled by user")
    }

    private suspend fun executeBrowserTool(toolName: String, arguments: Map<String, Any?>): BrowserToolResponse {
        val controller = ensureController()
        return when (toolName) {
            "open_url" -> {
                val url = arguments["url"]?.toString() ?: return BrowserToolResponse.Failure("Missing url")
                pendingRestoreUrl = null
                controller.openUrl(url, longArg(arguments, "timeout_ms", 30_000)).also { updateTabAfterNavigation(controller) }
            }
            "new_page" -> {
                createTab(arguments["url"]?.toString())
                val runtime = withContext(Dispatchers.Main.immediate) {
                    ensureSharedControllerOnMain().also { attachHeadlessSurfaceOnMain() }
                }
                GeckoBrowserRuntime.attach(context, runtime.second.session, reloadIfNeeded = false)
                runtime.second.newPage(arguments["url"]?.toString()).also { updateTabAfterNavigation(runtime.second) }
            }
            "close_page" -> closeActiveTab(controller)
            "switch_tab" -> switchTab(controller, arguments["page_id"]?.toString() ?: arguments["tab_id"]?.toString())
            "click_element" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for click. Use element_id from interactive_elements.")
                val clicked = try {
                    controller.click(selector)
                } catch (error: IllegalStateException) {
                    if (error.message == "Browser document navigated") controller.waitForNavigation(30_000)
                    else BrowserToolResponse.Failure("Click failed: ${error.message ?: "unknown error"}")
                }
                clicked.also { updateTabAfterNavigation(controller) }
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
                controller.focus(selector)
            }
            "press_key" -> {
                val key = arguments["key"]?.toString() ?: arguments["text"]?.toString() ?: "ENTER"
                controller.pressKey(key).also { updateTabAfterNavigation(controller) }
            }
            "search" -> {
                val text = arguments["query"]?.toString() ?: arguments["text"]?.toString()
                    ?: return domBackedFailure(controller, "Missing query/text for search.")
                val selector = selectorArg(arguments)
                controller.search(text, selector).also { updateTabAfterNavigation(controller) }
            }
            "type_text" -> {
                val selector = selectorArg(arguments)
                val text = arguments["text"]?.toString() ?: return BrowserToolResponse.Failure("Missing text for type_text")
                // External keyboard focus is valid even when the field is outside the
                // DOM viewport. Omit element_id/selector to type into document.activeElement.
                val typed = controller.typeText(selector, text, boolArg(arguments, "append", true))
                if (typed is BrowserToolResponse.Success && boolArg(arguments, "submit", false)) {
                    controller.submitFromContext(selector).also { updateTabAfterNavigation(controller) }
                } else typed
            }
            "clear_input" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for clear_input. Use element_id from interactive_elements.")
                controller.clearInput(selector)
            }
            "scroll_page" -> {
                val direction = arguments["direction"]?.toString()?.trim()?.lowercase()
                val distance = when (arguments["amount"]?.toString()?.lowercase()) {
                    "small" -> 420
                    "large" -> 1_400
                    else -> (floatArg(arguments, "distance", 0.28f) * 2_000).toInt().coerceIn(320, 1_600)
                }
                val (deltaX, deltaY) = when (direction) {
                    "left" -> distance to 0
                    "right" -> -distance to 0
                    "up" -> 0 to distance
                    "down" -> 0 to -distance
                    else -> intArg(arguments, "delta_x", 0) to intArg(arguments, "delta_y", 800)
                }
                // DOM scrolling works for headless Gecko sessions; touch coordinates do not.
                controller.scrollPage(deltaX, deltaY)
            }
            "get_dom" -> controller.getDom()
            "get_html" -> controller.getHtml()
            "get_visible_text" -> controller.getVisibleText()
            "get_screenshot" -> controller.screenshot()
            "evaluate" -> {
                val expression = arguments["expression"]?.toString() ?: arguments["script"]?.toString()
                    ?: return BrowserToolResponse.Failure("Missing expression for evaluate")
                controller.evaluate(expression, longArg(arguments, "timeout_ms", 10_000))
            }
            "hover" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for hover")
                controller.hover(selector)
            }
            "select_option" -> {
                val selector = selectorArg(arguments)
                    ?: return domBackedFailure(controller, "Missing selector/element_id for select_option")
                val value = firstString(arguments, "value", "text", "label")
                    ?: return BrowserToolResponse.Failure("Missing value for select_option")
                controller.selectOption(selector, value)
            }
            "upload_file" -> uploadWorkspaceFiles(controller, arguments)
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
            "find_text" -> {
                val query = queryArg(arguments)
                    ?: return BrowserToolResponse.Failure("Missing query/text for find_text")
                controller.findText(query)
            }
            "wait_for_element" -> {
                val query = queryArg(arguments)
                    ?: return domBackedFailure(controller, "Missing query for wait_for_element. Provide query/text/label/target.")
                val found = controller.waitForElement(query, longArg(arguments, "timeout_ms", 10_000))
                if (found is BrowserToolResponse.Failure && found.message.contains("Timed out", ignoreCase = true)) {
                    domBackedFailure(controller, "Timed out waiting for element: $query")
                } else found
            }
            "wait_for_navigation" -> controller.waitForNavigation(longArg(arguments, "timeout_ms", 30_000)).also { updateTabAfterNavigation(controller) }
            "go_back" -> controller.goBack().also { updateTabAfterNavigation(controller) }
            "go_forward" -> controller.goForward().also { updateTabAfterNavigation(controller) }
            "reload_page" -> controller.reload().also { updateTabAfterNavigation(controller) }
            else -> BrowserToolResponse.Failure("Unknown browser tool: $toolName")
        }
    }

    private suspend fun uploadWorkspaceFiles(controller: AndroidBrowserController, arguments: Map<String, Any?>): BrowserToolResponse {
        val root = workspacePath?.let(::File)?.canonicalFile
            ?: return BrowserToolResponse.Failure("Upload requires an active workspace")
        val rawPaths = when (val paths = arguments["paths"]) {
            is Iterable<*> -> paths.mapNotNull { it?.toString() }
            else -> listOfNotNull(arguments["path"]?.toString())
        }
        if (rawPaths.isEmpty()) return BrowserToolResponse.Failure("Missing workspace-relative path/paths for upload_file")
        val files = runCatching {
            rawPaths.map { raw ->
                require(raw.isNotBlank() && raw.replace('\\', '/').split('/').none { it == ".." }) { "Invalid workspace path: $raw" }
                val file = File(root, raw).canonicalFile
                require(file.path == root.path || file.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)) { "Path is outside the active workspace: $raw" }
                require(file.isFile) { "Workspace file not found: $raw" }
                file
            }
        }.getOrElse { return BrowserToolResponse.Failure(it.message ?: "Invalid upload path") }
        val selector = selectorArg(arguments)
            ?: return domBackedFailure(controller, "Missing selector/element_id for upload_file")
        val accept = controller.fileInputConstraints(selector) ?: return BrowserToolResponse.Failure("Target is not a file input")
        if (!accept.multiple && files.size > 1) return BrowserToolResponse.Failure("Web input accepts only one file")
        val rejected = files.firstOrNull { file -> accept.acceptTypes.isNotEmpty() && accept.acceptTypes.none { matchesFileAccept(it, file) } }
        if (rejected != null) return BrowserToolResponse.Failure("File does not match web input format: ${rejected.name}; accepts ${accept.acceptTypes.joinToString()}")
        if (!boolArg(arguments, "__confirmed", false)) {
            return BrowserToolResponse.Failure(
                "User approval required before selecting workspace files for upload",
                metadata = mapOf(
                    "requires_confirmation" to true,
                    "confirmation_reason" to "Select ${files.size} workspace file(s) for this web form?",
                    "confirmation_details" to files.joinToString { it.relativeTo(root).invariantSeparatorsPath },
                    "risk_level" to "medium",
                    "confirmation_action" to "upload_file"
                )
            )
        }
        val prepared = controller.beginFileInputAssignment(selector)
        if (prepared is BrowserToolResponse.Failure) return prepared
        val chunkError = withContext(Dispatchers.IO) {
            files.firstNotNullOfOrNull { file ->
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
                    var first = true
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val chunk = android.util.Base64.encodeToString(buffer.copyOf(count), android.util.Base64.NO_WRAP)
                        val response = withContext(Dispatchers.Main.immediate) {
                            controller.appendFileInputChunk(file.name, mime, file.lastModified(), chunk, first)
                        }
                        if (response is BrowserToolResponse.Failure) return@firstNotNullOfOrNull response
                        first = false
                    }
                    if (first) {
                        val response = withContext(Dispatchers.Main.immediate) {
                            controller.appendFileInputChunk(file.name, mime, file.lastModified(), "", true)
                        }
                        if (response is BrowserToolResponse.Failure) return@firstNotNullOfOrNull response
                    }
                }
                null
            }
        }
        if (chunkError != null) return chunkError
        val assigned = controller.finishFileInputAssignment()
        if (assigned is BrowserToolResponse.Failure) return assigned
        if (controller.uploadedFileNames(selector).toSet() == files.map { it.name }.toSet()) {
            return BrowserToolResponse.Success(
                "Selected ${files.size} workspace file(s) for upload",
                controller.currentMetadata() + mapOf("files" to files.map { it.relativeTo(root).invariantSeparatorsPath }, "accept" to accept.acceptTypes, "multiple" to accept.multiple)
            )
        }
        val uris = files.map(Uri::fromFile).toTypedArray()
        queuedUploadDecision = true
        queuedUploadUris = uris
        val opened = controller.click(selector)
        if (opened is BrowserToolResponse.Failure && !opened.message.equals("File selection pending", ignoreCase = true)) {
            queuedUploadDecision = false
            queuedUploadUris = null
            return opened
        }
        repeat(30) {
            if (!queuedUploadDecision && controller.uploadedFileNames(selector).toSet() == files.map { it.name }.toSet()) {
                return BrowserToolResponse.Success(
                    "Selected ${files.size} workspace file(s) for upload",
                    controller.currentMetadata() + mapOf("files" to files.map { it.relativeTo(root).invariantSeparatorsPath }, "accept" to accept.acceptTypes, "multiple" to accept.multiple)
                )
            }
            delay(100)
        }
        queuedUploadDecision = false
        queuedUploadUris = null
        return BrowserToolResponse.Failure("Web file chooser did not open", recoverable = true)
    }

    private fun matchesFileAccept(pattern: String, file: File): Boolean {
        val normalized = pattern.trim().lowercase()
        if (normalized.isBlank() || normalized == "*/*") return true
        if (normalized.startsWith('.')) return file.name.lowercase().endsWith(normalized)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()).orEmpty()
        return normalized == mime || (normalized.endsWith("/*") && mime.startsWith(normalized.removeSuffix("*")))
    }

    private suspend fun hydrateRestoredPageIfNeeded(action: String) {
        if (action in setOf("open_url", "new_page", "new_tab", "get_status", "list_pages")) return
        val url = pendingRestoreUrl?.takeIf { it.isNotBlank() && it != "about:blank" } ?: return
        pendingRestoreUrl = null
        val active = ensureController()
        if (active.currentUrl() == "about:blank") active.openUrl(url)
    }

    private suspend fun ensureController(): AndroidBrowserController {
        val active = withContext(Dispatchers.Main.immediate) {
            ensureSharedControllerOnMain().also { attachHeadlessSurfaceOnMain() }.second
        }
        // A delegated turn may select this persisted session before its GeckoView is
        // mounted. Attach/reload here so DOM actions get the same ready bridge as the UI.
        GeckoBrowserRuntime.attach(context, active.session, reloadIfNeeded = active.currentUrl() != "about:blank")
        return active
    }

    private fun createTab(url: String?) {
        if (_uiState.value.tabs.size >= 8) {
            val removable = _uiState.value.tabs.firstOrNull { it.id != _uiState.value.activeTabId }
            removable?.let { old -> pageRuntimes.remove(old.id)?.let { it.controller.resetToBlank(); GeckoBrowserRuntime.detach(it.session); it.session.close() } }
            _uiState.update { it.copy(tabs = it.tabs.filterNot { tab -> tab.id == removable?.id }) }
        }
        val tab = BrowserPageTab(title = "New Page", url = url ?: "about:blank")
        _uiState.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
    }

    private suspend fun switchTab(@Suppress("UNUSED_PARAMETER") current: AndroidBrowserController, pageId: String?): BrowserToolResponse {
        val target = _uiState.value.tabs.firstOrNull { it.id == pageId }
            ?: return BrowserToolResponse.Failure("Tab not found: ${pageId ?: "missing page_id"}")
        _uiState.update { it.copy(activeTabId = target.id, activeUrl = target.url, activeTitle = target.title) }
        val targetRuntime = withContext(Dispatchers.Main.immediate) {
            ensureSharedControllerOnMain().also {
                it.second.session.setActive(true)
                it.second.session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
                attachHeadlessSurfaceOnMain()
            }
        }
        // A background GeckoSession may have lost its bridge while its page stayed
        // resident. Attach with recovery enabled; otherwise the next DOM action waits
        // 15 seconds on a dead port.
        GeckoBrowserRuntime.attach(context, targetRuntime.second.session, reloadIfNeeded = true)
        return if (targetRuntime.second.currentUrl() == "about:blank" && target.url != "about:blank") {
            targetRuntime.second.openUrl(target.url).also { updateTabAfterNavigation(targetRuntime.second) }
        } else {
            updateTabAfterNavigation(targetRuntime.second)
            BrowserToolResponse.Success("Switched to ${targetRuntime.second.currentUrl()}", targetRuntime.second.currentMetadata())
        }
    }

    private suspend fun closeActiveTab(controller: AndroidBrowserController): BrowserToolResponse {
        val state = _uiState.value
        val active = state.activeTabId
        val remaining = state.tabs.filterNot { it.id == active }
        return if (remaining.isEmpty()) {
            pageRuntimes.remove(active)?.let { it.controller.resetToBlank(); GeckoBrowserRuntime.detach(it.session); it.session.close() }
            val newTab = BrowserPageTab()
            _uiState.update { it.copy(tabs = listOf(newTab), activeTabId = newTab.id, activeUrl = "about:blank", activeTitle = "New Page") }
            val runtime = withContext(Dispatchers.Main.immediate) {
                ensureSharedControllerOnMain().also { if (!visibleHost) attachHeadlessSurfaceOnMain() }
            }
            GeckoBrowserRuntime.attach(context, runtime.second.session, reloadIfNeeded = false)
            runtime.second.closePage()
        } else {
            val next = remaining.last()
            pageRuntimes.remove(active)?.let { it.controller.resetToBlank(); GeckoBrowserRuntime.detach(it.session); it.session.close() }
            _uiState.update { it.copy(tabs = remaining, activeTabId = next.id, activeUrl = next.url, activeTitle = next.title) }
            val nextRuntime = withContext(Dispatchers.Main.immediate) {
                ensureSharedControllerOnMain().also { if (!visibleHost) attachHeadlessSurfaceOnMain() }
            }
            GeckoBrowserRuntime.attach(context, nextRuntime.second.session, reloadIfNeeded = false)
            nextRuntime.second.openUrl(next.url).also { updateTabAfterNavigation(nextRuntime.second) }
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
            val tabId = _uiState.value.activeTabId ?: return@withContext
            onNavigationChanged(
                tabId,
                controller.currentUrl(),
                controller.currentTitle(),
                1f,
                metadata["can_go_back"] == true,
                metadata["can_go_forward"] == true
            )
        }
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

    companion object {
        private const val UPLOAD_CHUNK_BYTES = 192 * 1024
    }
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
