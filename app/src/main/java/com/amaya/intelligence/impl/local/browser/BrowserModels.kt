package com.amaya.intelligence.impl.local.browser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class BrowserAgentStatus { IDLE, THINKING, BROWSING, CANCELLED, ERROR, COMPLETED }

data class BrowserPageTab(
    val id: String = UUID.randomUUID().toString(), val title: String = "New Page", val url: String = "about:blank",
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val scrollX: Int = 0, val scrollY: Int = 0
)

data class BrowserToolLog(val id: String = UUID.randomUUID().toString(), val toolName: String, val argumentsPreview: String, val status: String, val message: String, val timestamp: Long = System.currentTimeMillis()) {
    val displayTime: String get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

data class BrowserUiState(
    val sessionId: String = "", val browserId: String = "browser_local_001", val conversationKey: String? = null, val agentId: Long? = null,
    val status: BrowserAgentStatus = BrowserAgentStatus.IDLE, val activeUrl: String = "about:blank", val activeTitle: String = "AI Browser Operator",
    val activeTabId: String? = null, val tabs: List<BrowserPageTab> = listOf(BrowserPageTab()), val currentAction: String = "Idle",
    val inspectedElement: String? = null, val progress: Float = 0f, val logs: List<BrowserToolLog> = emptyList(), val screenshotBase64: String? = null,
    val downloads: List<BrowserDownload> = emptyList(), val uploadPending: Boolean = false, val uploadAcceptTypes: List<String> = emptyList(),
    val uploadRequestNonce: Long = 0L, val evaluateResult: String? = null,
    val isCancelled: Boolean = false, val lastError: String? = null,
    val sessionHistory: List<BrowserHistoryEntry> = emptyList(),
    val assistantStreamText: String = "", val assistantStreamUpdatedAt: Long = 0L, val isAssistantStreaming: Boolean = false, val browserAccessActive: Boolean = false,
    val agentTouchX: Float? = null, val agentTouchY: Float? = null, val agentTouchNonce: Long = 0L
)

data class BrowserHistoryEntry(val url: String, val title: String, val visitedAt: Long = System.currentTimeMillis())
data class BrowserDownload(val fileName: String, val relativePath: String, val mimeType: String = "application/octet-stream", val size: Long = 0L, val completedAt: Long = System.currentTimeMillis())
sealed class BrowserToolResponse { data class Success(val output: String, val metadata: Map<String, Any> = emptyMap()) : BrowserToolResponse(); data class Failure(val message: String, val recoverable: Boolean = true, val metadata: Map<String, Any> = emptyMap()) : BrowserToolResponse() }
