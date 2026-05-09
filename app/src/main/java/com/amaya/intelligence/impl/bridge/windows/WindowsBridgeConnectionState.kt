package com.amaya.intelligence.impl.bridge.windows

/**
 * High-level connection state reported by [WindowsBridgeSessionClient].
 *
 * This is the Windows Bridge counterpart of
 * `com.amaya.intelligence.domain.models.ConnectionState` but intentionally richer —
 * the bridge flow has explicit PAUSED / RECONNECTING / CLOSING / ERROR transitions
 * that the simpler Antigravity client does not need.
 */
enum class WindowsBridgeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    PAUSED,
    CLOSING,
    ERROR
}
