package com.amaya.intelligence.impl.local.browser

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal suspend fun BrowserConversationSession.uploadWorkspaceFiles(controller: AndroidBrowserController, arguments: Map<String, Any?>): BrowserToolResponse {
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
                    val buffer = ByteArray(BrowserConversationSession.UPLOAD_CHUNK_BYTES)
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
internal fun BrowserConversationSession.matchesFileAccept(pattern: String, file: File): Boolean {
        val normalized = pattern.trim().lowercase()
        if (normalized.isBlank() || normalized == "*/*") return true
        if (normalized.startsWith('.')) return file.name.lowercase().endsWith(normalized)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()).orEmpty()
        return normalized == mime || (normalized.endsWith("/*") && mime.startsWith(normalized.removeSuffix("*")))
    }
