package com.amaya.intelligence.impl.bridge.windows.pairing

/**
 * A saved Windows Bridge connection profile. Persisted locally on Android so the
 * user can reconnect without re-entering host/port every time.
 *
 * Token is intentionally NOT stored here. If the bridge requires a token, the user
 * must re-enter it (or paste a fresh pairing payload) on each reconnect unless
 * secure storage is wired in a later phase.
 */
data class WindowsBridgeProfile(
    val id: String,
    val bridgeId: String? = null,
    val name: String,
    val host: String,
    val port: Int = 17878,
    val deviceId: String,
    val computerName: String? = null,
    val lastConnectedAt: Long? = null,
    val trusted: Boolean = false
)
