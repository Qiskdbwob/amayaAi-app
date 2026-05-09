package com.amaya.intelligence.impl.bridge.windows

import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.domain.bridge.BridgeAuditEvent
import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeError
import com.amaya.intelligence.domain.bridge.BridgeMessageType
import com.amaya.intelligence.domain.bridge.BridgeToolError
import com.amaya.intelligence.domain.bridge.BridgeToolResult

/**
 * Events emitted by [WindowsBridgeSessionClient] to its subscribers. Each event wraps
 * either a lifecycle transition or a parsed Windows-Bridge message.
 *
 * Downstream consumers (agent integration, approval UI) will pattern-match on these
 * variants. Phase 2 only produces them; no consumer lives in this package yet.
 */
sealed class WindowsBridgeClientEvent {

    /** WebSocket successfully opened against the configured host. */
    data class Connected(val host: String, val port: Int) : WindowsBridgeClientEvent()

    /** WebSocket closed. [code] is the WebSocket close code when available. */
    data class Disconnected(
        val code: Int?,
        val reason: String?,
        val remote: Boolean
    ) : WindowsBridgeClientEvent()

    /** Bridge confirmed that a session has been created or re-attached. */
    data class SessionCreated(val sessionId: String) : WindowsBridgeClientEvent()

    /** Bridge reported that the session is closed. */
    data class SessionClosed(val sessionId: String?, val reason: String?) :
        WindowsBridgeClientEvent()

    /** Pairing handshake succeeded. */
    data class DevicePaired(val sessionId: String?, val deviceId: String) :
        WindowsBridgeClientEvent()

    /** The paired Windows device dropped or was unpaired by the user. */
    data class DeviceDisconnected(val sessionId: String?, val reason: String?) :
        WindowsBridgeClientEvent()

    /** A video/screen frame was received. Phase 2 just forwards the envelope. */
    data class ScreenFrameReceived(val envelope: BridgeEnvelope) :
        WindowsBridgeClientEvent()

    /** Ad-hoc screen capture result. */
    data class ScreenCaptureResultReceived(val envelope: BridgeEnvelope) :
        WindowsBridgeClientEvent()

    /** Successful tool-call result. */
    data class ToolResultReceived(val result: BridgeToolResult) :
        WindowsBridgeClientEvent()

    /** Failed tool-call result. */
    data class ToolErrorReceived(val error: BridgeToolError) :
        WindowsBridgeClientEvent()

    /** Bridge is asking the user to approve a tool call. */
    data class ApprovalRequestReceived(val request: ApprovalRequest) :
        WindowsBridgeClientEvent()

    /** Bridge reported that an approval was accepted upstream. */
    data class ApprovalAcceptedReceived(val envelope: BridgeEnvelope) :
        WindowsBridgeClientEvent()

    /** Bridge reported that an approval was rejected upstream. */
    data class ApprovalRejectedReceived(val envelope: BridgeEnvelope) :
        WindowsBridgeClientEvent()

    /** Agent status / step / pause / resume / cancel report from the bridge side. */
    data class AgentUpdateReceived(
        val type: BridgeMessageType,
        val envelope: BridgeEnvelope
    ) : WindowsBridgeClientEvent()

    /** Audit log entry produced by the bridge. */
    data class AuditEventReceived(val event: BridgeAuditEvent) :
        WindowsBridgeClientEvent()

    /**
     * Any envelope that was parsed successfully but isn't surfaced through a more
     * specific variant. Emitted as a catch-all so consumers can still route unknown
     * or extension message types without re-parsing JSON themselves.
     */
    data class EnvelopeReceived(val envelope: BridgeEnvelope) :
        WindowsBridgeClientEvent()

    /** Transport-level error envelope from the peer. */
    data class ProtocolError(val error: BridgeError) : WindowsBridgeClientEvent()

    /** Local error (parse failure, IO error, bad config). Never crashes the client. */
    data class Error(val message: String, val throwable: Throwable? = null) :
        WindowsBridgeClientEvent()
}
