package com.amaya.intelligence.impl.local.browser

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class BrowserAgentStatus {
    IDLE,
    THINKING,
    BROWSING,
    WAITING_INPUT,
    PAUSED,
    CANCELLED,
    ERROR,
    COMPLETED
}

data class BrowserPageTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Page",
    val url: String = "about:blank",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

data class BrowserToolLog(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val argumentsPreview: String,
    val status: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

data class BrowserSafetyPrompt(
    val id: String = UUID.randomUUID().toString(),
    val reason: String,
    val selector: String? = null,
    val fieldLabel: String? = null,
    val toolName: String? = null
)

data class BrowserUiState(
    val status: BrowserAgentStatus = BrowserAgentStatus.IDLE,
    val activeUrl: String = "about:blank",
    val activeTitle: String = "AI Browser Operator",
    val activeTabId: String? = null,
    val tabs: List<BrowserPageTab> = listOf(BrowserPageTab()),
    val currentAction: String = "Idle",
    val inspectedElement: String? = null,
    val progress: Float = 0f,
    val logs: List<BrowserToolLog> = emptyList(),
    val screenshotBase64: String? = null,
    val safetyPrompt: BrowserSafetyPrompt? = null,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val lastError: String? = null,
    val sessionHistory: List<String> = emptyList(),
    val assistantStreamText: String = "",
    val assistantStreamUpdatedAt: Long = 0L,
    val isAssistantStreaming: Boolean = false,
    val browserAccessActive: Boolean = false,
    val agentTouchX: Float? = null,
    val agentTouchY: Float? = null,
    val agentTouchNonce: Long = 0L
)

data class BrowserElementSummary(
    val selector: String,
    val tag: String,
    val text: String = "",
    val label: String = "",
    val type: String = "",
    val role: String = "",
    val href: String = "",
    val src: String = "",
    val placeholder: String = "",
    val name: String = "",
    val id: String = "",
    val isVisible: Boolean = true,
    val isSensitive: Boolean = false
)

sealed class BrowserToolResponse {
    data class Success(
        val output: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : BrowserToolResponse()

    data class Failure(
        val message: String,
        val recoverable: Boolean = true,
        val metadata: Map<String, Any> = emptyMap()
    ) : BrowserToolResponse()

    data class SafetyPause(
        val prompt: BrowserSafetyPrompt
    ) : BrowserToolResponse()
}
