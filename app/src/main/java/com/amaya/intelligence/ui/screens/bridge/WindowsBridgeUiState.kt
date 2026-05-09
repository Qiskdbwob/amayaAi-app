package com.amaya.intelligence.ui.screens.bridge

import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.domain.bridge.BridgeRiskLevel
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState

/**
 * UI state for the Windows Bridge screen.
 */
data class WindowsBridgeUiState(
    val connectionState: WindowsBridgeConnectionState = WindowsBridgeConnectionState.DISCONNECTED,
    val sessionId: String? = null,
    val agentControlEnabled: Boolean = false,
    val host: String = "",
    val port: String = "17878",
    val token: String = "",
    val deviceId: String = "",
    val error: String? = null,
    val screenCapture: ScreenCaptureState? = null,
    val pendingApproval: ApprovalRequest? = null,
    val recentActivity: List<BridgeActivityEntry> = emptyList()
) {
    val isConnected: Boolean
        get() = connectionState == WindowsBridgeConnectionState.CONNECTED ||
            connectionState == WindowsBridgeConnectionState.PAUSED

    val isConnecting: Boolean
        get() = connectionState == WindowsBridgeConnectionState.CONNECTING ||
            connectionState == WindowsBridgeConnectionState.RECONNECTING

    val statusLabel: String
        get() = when (connectionState) {
            WindowsBridgeConnectionState.DISCONNECTED -> "Disconnected"
            WindowsBridgeConnectionState.CONNECTING -> "Connecting…"
            WindowsBridgeConnectionState.CONNECTED -> if (agentControlEnabled) "Agent Control" else "Connected"
            WindowsBridgeConnectionState.RECONNECTING -> "Reconnecting…"
            WindowsBridgeConnectionState.PAUSED -> "Paused"
            WindowsBridgeConnectionState.CLOSING -> "Closing…"
            WindowsBridgeConnectionState.ERROR -> "Error"
        }
}

data class ScreenCaptureState(
    val imageBase64: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: String = "jpeg",
    val isLoading: Boolean = false,
    val error: String? = null
)

data class BridgeActivityEntry(
    val timestamp: Long,
    val tool: String?,
    val outcome: String,
    val summary: String
)
