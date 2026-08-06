package com.amaya.intelligence.impl.bridge.windows

/**
 * Connection parameters for a [WindowsBridgeSessionClient].
 *
 * Phase 2 only exposes what the client needs to open a WebSocket and identify itself.
 * Pairing UI, token rotation, and QR provisioning are deliberately out of scope and
 * will be layered on top of this config in later phases.
 */
data class WindowsBridgeClientConfig(
    /** Hostname or IP of the Windows Bridge peer. */
    val host: String,
    /** TCP port the Windows Bridge listens on. */
    val port: Int,
    /**
     * Optional pairing / auth token. When non-null it is sent as the `token` field of
     * the initial handshake envelope metadata.
     */
    val token: String? = null,
    /** Stable identifier for this Android device. */
    val deviceId: String,
    /**
     * Previously known session id, used when reconnecting to a bridge we have already
     * paired with. `null` for fresh pairings.
     */
    val sessionId: String? = null,
    /** When true, [WindowsBridgeSessionClient] will auto-reconnect on unexpected drops. */
    val reconnectEnabled: Boolean = true,
    /** Hard cap on reconnect attempts per disconnect. 0 means "unlimited". */
    val reconnectMaxAttempts: Int = 5,
    /** Path appended to the WebSocket URL. Defaults to root. */
    val path: String = "/",
    /** WebSocket scheme, normally `ws` on LAN and `wss` when a proxy terminates TLS. */
    val scheme: String = "ws"
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(reconnectMaxAttempts >= 0) { "reconnectMaxAttempts must be >= 0" }
        require(scheme == "ws" || scheme == "wss") { "scheme must be ws or wss, got $scheme" }
    }

    /** Full WebSocket URL assembled from [scheme], [host], [port], and [path]. */
    val url: String get() = "$scheme://$host:$port$path"
}
