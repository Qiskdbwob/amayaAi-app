package com.amaya.intelligence.ui.components.remote

import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState

/**
 * UI state shared by the chat-side Windows Bridge surfaces: connection banner in
 * local chat, the session-info sheet opened from the chat top bar, and the
 * connection-setup sheet opened from the Remote Session screen.
 *
 * There is intentionally one ViewModel + state for all three so Agent Control,
 * capture, approvals, and connection details stay in sync.
 */
data class WindowsBridgeChatUiState(
    val connectionState: WindowsBridgeConnectionState = WindowsBridgeConnectionState.DISCONNECTED,
    val sessionId: String? = null,
    val deviceId: String = "",
    val host: String = "",
    val port: String = "17878",
    val token: String = "",
    val isAgentControlEnabled: Boolean = false,
    val pendingApproval: ApprovalRequest? = null,
    val screenCapture: WindowsBridgeCaptureState? = null,
    val lastError: String? = null
) {
    val isConnected: Boolean
        get() = connectionState == WindowsBridgeConnectionState.CONNECTED ||
            connectionState == WindowsBridgeConnectionState.PAUSED

    val isConnecting: Boolean
        get() = connectionState == WindowsBridgeConnectionState.CONNECTING ||
            connectionState == WindowsBridgeConnectionState.RECONNECTING

    val isPaused: Boolean
        get() = connectionState == WindowsBridgeConnectionState.PAUSED

    val statusLabel: String
        get() = when {
            isPaused -> "Paused"
            isAgentControlEnabled -> "Agent Control"
            isConnected -> "View Only"
            isConnecting -> "Connecting…"
            else -> "Disconnected"
        }

    val shouldShowBanner: Boolean
        get() = isConnected || isPaused

    val serverLabel: String
        get() = if (host.isNotBlank()) "$host:${port.ifBlank { "17878" }}" else "—"
}

/**
 * Screen-capture preview state used by [WindowsBridgeSessionInfoSheet] when the
 * user taps "Capture screen".
 */
data class WindowsBridgeCaptureState(
    val imageBase64: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: String = "jpeg",
    val isLoading: Boolean = false,
    val error: String? = null
)
