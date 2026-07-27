package com.amaya.intelligence.impl.bridge.windows

import com.amaya.intelligence.domain.models.ConnectionState

internal fun WindowsBridgeConnectionState.toChatConnectionState(): ConnectionState = when (this) {
    WindowsBridgeConnectionState.CONNECTED,
    WindowsBridgeConnectionState.PAUSED -> ConnectionState.CONNECTED
    WindowsBridgeConnectionState.CONNECTING,
    WindowsBridgeConnectionState.RECONNECTING,
    WindowsBridgeConnectionState.CLOSING -> ConnectionState.CONNECTING
    WindowsBridgeConnectionState.DISCONNECTED,
    WindowsBridgeConnectionState.ERROR -> ConnectionState.DISCONNECTED
}
