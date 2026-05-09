package com.amaya.intelligence.domain.bridge

/**
 * Lifecycle status of a bridge session between an Android device and a Windows Bridge.
 *
 *  - [CREATED]       : session record exists but no transport yet
 *  - [PAIRING]       : pairing handshake in progress (QR / code exchange)
 *  - [CONNECTED]     : transport established, read-only observations allowed
 *  - [AGENT_CONTROL] : agent is permitted to drive input (mouse/keyboard/shell/etc.)
 *  - [VIEW_ONLY]     : user has downgraded the session to observation-only
 *  - [PAUSED]        : temporarily suspended by user or policy, resumable
 *  - [CANCELLED]     : agent run cancelled; session itself may still be open
 *  - [CLOSED]        : session ended cleanly
 *  - [ERROR]         : session ended or is stuck due to a failure
 */
enum class BridgeSessionStatus(val wireName: String) {
    CREATED("created"),
    PAIRING("pairing"),
    CONNECTED("connected"),
    AGENT_CONTROL("agent_control"),
    VIEW_ONLY("view_only"),
    PAUSED("paused"),
    CANCELLED("cancelled"),
    CLOSED("closed"),
    ERROR("error");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeSessionStatus? =
            if (value == null) null else byWireName[value.lowercase()]
    }
}

/**
 * Capabilities advertised by a Windows Bridge peer. The set is declared at handshake
 * time and may narrow over the session lifetime (e.g. the user revokes shell access).
 */
enum class BridgeCapability(val wireName: String) {
    SCREEN_CAPTURE("screenCapture"),
    SCREEN_STREAM("screenStream"),
    MOUSE_CONTROL("mouseControl"),
    KEYBOARD_CONTROL("keyboardControl"),
    WINDOW_CONTROL("windowControl"),
    FILE_ACCESS("fileAccess"),
    SHELL_ACCESS("shellAccess"),
    BROWSER_ACCESS("browserAccess"),
    UI_AUTOMATION("uiAutomation"),
    CLIPBOARD_ACCESS("clipboardAccess");

    companion object {
        private val byWireName = values().associateBy { it.wireName }
        fun fromWireName(value: String?): BridgeCapability? =
            if (value == null) null else byWireName[value]
    }
}

/**
 * Snapshot of a bridge session's lifecycle state. Immutable — progress through the
 * session produces new values rather than mutating the existing one.
 */
data class BridgeSessionState(
    /** Unique session id. */
    val sessionId: String,
    /** Paired device id (Windows Bridge identifier). */
    val deviceId: String,
    /** Human-readable computer name as advertised by the bridge, if known. */
    val computerName: String? = null,
    /** Current lifecycle status. */
    val status: BridgeSessionStatus,
    /** Capabilities advertised for this session. */
    val capabilities: Set<BridgeCapability> = emptySet(),
    /** Epoch millis when the session was first created. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Epoch millis of the last status/capability update. */
    val updatedAt: Long = createdAt,
    /** Epoch millis of the last keepalive / message received from the peer. */
    val lastSeenAt: Long = createdAt
)
