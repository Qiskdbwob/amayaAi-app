package com.amaya.intelligence.impl.local.browser
import android.net.Uri
import org.mozilla.geckoview.WebResponse
import androidx.core.content.FileProvider
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.UUID
internal fun BrowserConversationSession.setWorkspace(path: String?) { workspacePath = path?.takeIf(String::isNotBlank) }
internal fun BrowserConversationSession.retain() { clients.incrementAndGet() }
internal fun BrowserConversationSession.release() { clients.decrementAndGet() }
internal fun BrowserConversationSession.isExecuting(): Boolean = clients.get() > 0 || executionMutex.isLocked
internal fun BrowserConversationSession.close() {
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
internal fun BrowserConversationSession.canOpenOperator(): Boolean = _uiState.value.agentId != null && conversationKey != null
internal fun BrowserConversationSession.markAuthHandoffCompleted() { _uiState.update { it.copy(currentAction = "External verification completed") } }
internal fun BrowserConversationSession.releaseInactiveRuntimes() {
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
internal fun BrowserConversationSession.selectConversation(key: String, agentId: Long? = null) {
        visibleConversationKey = key
        visibleAgentId = agentId
        resetForConversation(key, agentId)
    }
internal fun BrowserConversationSession.resetForConversation(key: String, agentId: Long? = null) {
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
            activeTabId = persistence.activeTabId(sessionId)
                ?.takeIf { id -> restoredTabs.any { it.id == id } }
                ?: tab.id,
            tabs = restoredTabs.ifEmpty { listOf(tab) },
            sessionHistory = restoredHistory
        )
    }
internal fun BrowserConversationSession.resetEphemeral() {
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
internal fun BrowserConversationSession.sessionId(): String = sessionId
internal fun BrowserConversationSession.takeLastScreenshotAttachment(): String? = _uiState.value.screenshotBase64.also {
        if (it != null) _uiState.update { state -> state.copy(screenshotBase64 = null) }
    }
internal suspend fun BrowserConversationSession.captureScreenshotToWorkspace(): BrowserToolResponse {
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
internal fun BrowserConversationSession.clearActiveSiteData() {
        val parsed = Uri.parse(_uiState.value.activeUrl)
        val origin = parsed.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { "${it.scheme}://${it.authority}" } ?: return
        GeckoBrowserRuntime.clearHostData(context, parsed.host ?: return)
        _uiState.update { it.copy(currentAction = "Cleared site data for $origin") }
    }
internal fun BrowserConversationSession.provideUploadUris(uris: Array<Uri>?) {
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
internal fun BrowserConversationSession.cancelPendingUpload() {
        val callback = fileChooserCallback
        fileChooserCallback = null
        queuedUploadDecision = false
        queuedUploadUris = null
        pendingUploadAcceptTypes = emptyArray()
        pendingUploadMultiple = false
        _uiState.update { it.copy(uploadPending = false, uploadAcceptTypes = emptyList()) }
        callback?.invoke(null)
    }
internal fun BrowserConversationSession.matchesMime(pattern: String, actual: String): Boolean = pattern == "*/*" || pattern == actual || (pattern.endsWith("/*") && actual.startsWith(pattern.removeSuffix("*")))
internal fun BrowserConversationSession.queryDisplayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
internal fun BrowserConversationSession.sanitizeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "upload" }
internal fun BrowserConversationSession.openDownload(download: BrowserDownload): Uri? = workspacePath?.let(::File)?.let { root ->
        val file = File(root, download.relativePath.removePrefix(".amaya/browser/downloads/")).takeIf(File::isFile) ?: return@let null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
internal fun BrowserConversationSession.deleteDownload(download: BrowserDownload) {
        workspacePath?.let(::File)?.let { root -> File(root, download.relativePath.removePrefix(".amaya/browser/downloads/")).delete() }
        _uiState.update { it.copy(downloads = it.downloads.filterNot { item -> item.relativePath == download.relativePath }) }
    }
internal fun BrowserConversationSession.clearSessionState() {
        if (conversationKey == null) return
        persistence.clear(sessionId)
        resetEphemeral()
    }
internal fun BrowserConversationSession.uniqueFile(root: File, name: String): File {
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
    internal fun BrowserConversationSession.handleGeckoDownload(response: WebResponse) {
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
                android.util.Log.i("AmayaBrowser", "download saved file=${target.name} bytes=$total")
                withContext(Dispatchers.Main.immediate) { _uiState.update { it.copy(downloads = (it.downloads + BrowserDownload(target.name, relative, mimeType, target.length())).takeLast(50)) } }
            }.onFailure { error ->
                android.util.Log.e("AmayaBrowser", "download failed", error)
                withContext(Dispatchers.Main.immediate) { onBrowserError("Download failed: ${error.message ?: "unknown error"}") }
            }
        }
    }
