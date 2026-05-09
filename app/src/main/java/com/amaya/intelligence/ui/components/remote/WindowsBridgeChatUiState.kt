package com.amaya.intelligence.ui.components.remote

import com.amaya.intelligence.domain.bridge.ApprovalRequest

/**
 * Lightweight UI state for the Windows Bridge banner shown inside ChatScreen.
 * Intentionally small — only what the banner/chip/dialog needs to render.
 */
data class WindowsBridgeChatUiState(
    val isConnected: Boolean = false,
    val isAgentControlEnabled: Boolean = false,
    val isPaused: Boolean = false,
    val sessionId: String? = null,
    val deviceId: String? = null,
    val pendingApproval: ApprovalRequest? = null,
    val lastError: String? = null
) {
    val statusLabel: String
        get() = when {
            isPaused -> "Paused"
            isAgentControlEnabled -> "Agent Control"
            isConnected -> "View Only"
            else -> "Disconnected"
        }

    val shouldShowBanner: Boolean
        get() = isConnected || isPaused
}
