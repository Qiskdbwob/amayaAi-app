package com.amaya.intelligence.impl.bridge.windows

import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeMessageType

internal object WindowsBridgeEnvelopeDispatcher {
    fun dispatch(envelope: BridgeEnvelope, emit: (WindowsBridgeClientEvent) -> Unit) {
        when (envelope.type) {
            BridgeMessageType.SESSION_CREATED -> emit(WindowsBridgeClientEvent.SessionCreated(envelope.sessionId ?: envelope.payload["sessionId"] as? String ?: ""))
            BridgeMessageType.SESSION_CLOSED -> emit(WindowsBridgeClientEvent.SessionClosed(envelope.sessionId, envelope.payload["reason"] as? String))
            BridgeMessageType.DEVICE_PAIRED -> emit(WindowsBridgeClientEvent.DevicePaired(envelope.sessionId, envelope.payload["deviceId"] as? String ?: envelope.deviceId))
            BridgeMessageType.DEVICE_DISCONNECTED -> emit(WindowsBridgeClientEvent.DeviceDisconnected(envelope.sessionId, envelope.payload["reason"] as? String))
            BridgeMessageType.SCREEN_FRAME -> emit(WindowsBridgeClientEvent.ScreenFrameReceived(envelope))
            BridgeMessageType.SCREEN_CAPTURE_RESULT -> emit(WindowsBridgeClientEvent.ScreenCaptureResultReceived(envelope))
            BridgeMessageType.TOOL_RESULT -> emit(WindowsBridgeEnvelopeMapper.decodeToolResult(envelope)?.let(WindowsBridgeClientEvent::ToolResultReceived) ?: WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            BridgeMessageType.TOOL_ERROR -> emit(WindowsBridgeEnvelopeMapper.decodeToolError(envelope)?.let(WindowsBridgeClientEvent::ToolErrorReceived) ?: WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            BridgeMessageType.APPROVAL_REQUEST -> emit(WindowsBridgeEnvelopeMapper.decodeApprovalRequest(envelope)?.let(WindowsBridgeClientEvent::ApprovalRequestReceived) ?: WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            BridgeMessageType.APPROVAL_ACCEPTED -> emit(WindowsBridgeClientEvent.ApprovalAcceptedReceived(envelope))
            BridgeMessageType.APPROVAL_REJECTED -> emit(WindowsBridgeClientEvent.ApprovalRejectedReceived(envelope))
            BridgeMessageType.AGENT_STATUS, BridgeMessageType.AGENT_STEP, BridgeMessageType.AGENT_PAUSED, BridgeMessageType.AGENT_RESUMED, BridgeMessageType.AGENT_CANCELLED -> emit(WindowsBridgeClientEvent.AgentUpdateReceived(envelope.type, envelope))
            BridgeMessageType.AUDIT_EVENT -> emit(WindowsBridgeEnvelopeMapper.decodeAuditEvent(envelope)?.let(WindowsBridgeClientEvent::AuditEventReceived) ?: WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            BridgeMessageType.ERROR -> emit(WindowsBridgeEnvelopeMapper.decodeProtocolError(envelope)?.let(WindowsBridgeClientEvent::ProtocolError) ?: WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            else -> emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
        }
    }
}
