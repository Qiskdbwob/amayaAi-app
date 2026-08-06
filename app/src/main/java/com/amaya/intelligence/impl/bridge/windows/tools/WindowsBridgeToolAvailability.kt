package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState

/**
 * Immutable snapshot of whether Windows Bridge tools are callable at a given moment.
 *
 * The executor emits one of these before every tool invocation so callers (and future
 * UI layers) can render a clear reason when bridge tools are hidden.
 */
data class WindowsBridgeToolAvailability(
    val isConnected: Boolean,
    val sessionId: String?,
    val devicePaired: Boolean,
    val connectionState: WindowsBridgeConnectionState,
    val enabledTools: Set<String>,
    val reasonIfUnavailable: String?
) {
    val isAvailable: Boolean
        get() = reasonIfUnavailable == null

    companion object {
        fun unavailable(
            state: WindowsBridgeConnectionState,
            enabled: Set<String>,
            reason: String
        ): WindowsBridgeToolAvailability = WindowsBridgeToolAvailability(
            isConnected = state == WindowsBridgeConnectionState.CONNECTED,
            sessionId = null,
            devicePaired = false,
            connectionState = state,
            enabledTools = enabled,
            reasonIfUnavailable = reason
        )
    }
}
