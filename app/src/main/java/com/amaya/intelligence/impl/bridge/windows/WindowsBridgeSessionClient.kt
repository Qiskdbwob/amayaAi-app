package com.amaya.intelligence.impl.bridge.windows

import com.amaya.intelligence.domain.bridge.ApprovalDecision
import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeMessageType
import com.amaya.intelligence.domain.bridge.BridgeToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Android-side client that talks to a Windows Bridge using the Phase 1 shared protocol.
 *
 * This is intentionally a plain class (no Hilt, no Context, no persistence). It is
 * self-contained so it can be wired into the existing DI graph or a dedicated
 * bridge-feature module in later phases without churning callers.
 *
 * Reference (for inspiration only, not reuse):
 *  - impl/ide/antigravity/client/RemoteSessionClient.kt
 * The Antigravity protocol is Antigravity-specific; this client is Bridge-specific.
 */
class WindowsBridgeSessionClient(
    private val config: WindowsBridgeClientConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val handler: WindowsBridgeEventHandler = WindowsBridgeEventHandler.NoOp
) {

    // ── State ────────────────────────────────────────────────────────────────

    private val _connectionState =
        MutableStateFlow(WindowsBridgeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WindowsBridgeConnectionState> =
        _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<WindowsBridgeClientEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<WindowsBridgeClientEvent> = _events.asSharedFlow()

    private var wsClient: WebSocketClient? = null
    private var reconnectJob: Job? = null
    @Volatile private var manualClose = false
    @Volatile private var reconnectAttempts = 0

    private val outgoingSeq = AtomicLong(0L)
    @Volatile private var lastIncomingSeq: Long = -1L

    private val pendingOutbound: ArrayDeque<String> = ArrayDeque()
    private val pendingLock = Any()
    private val maxPendingOutbound = 64

    @Volatile private var currentSessionId: String? = config.sessionId

    // ── Public API ───────────────────────────────────────────────────────────

    @Synchronized
    fun connect() {
        manualClose = false
        reconnectAttempts = 0
        WindowsBridgeLogger.connectRequested(config.host, config.port)
        openSocket()
    }

    /**
     * Override connect details at runtime. Useful for pairing flows that supply new
     * host/port/token values after the client is constructed.
     */
    fun connect(host: String, port: Int, token: String? = config.token) {
        val overridden = config.copy(host = host, port = port, token = token)
        replaceConfigAndReconnect(overridden)
    }

    @Synchronized
    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        updateState(WindowsBridgeConnectionState.CLOSING)
        closeSocketQuietly()
        updateState(WindowsBridgeConnectionState.DISCONNECTED)
    }

    /** Enqueue an envelope on the transport. Queued if not connected. */
    fun sendEnvelope(envelope: BridgeEnvelope): Boolean {
        val normalized = envelope.copy(
            seq = if (envelope.seq > 0) envelope.seq else nextOutgoingSeq(),
            deviceId = envelope.deviceId.ifBlank { config.deviceId },
            sessionId = envelope.sessionId ?: currentSessionId
        )
        val payload = WindowsBridgeEnvelopeMapper.encode(normalized)
        val typeWire = normalized.type.wireName
        val tool = (normalized.payload["tool"] as? String)
        val client = wsClient
        return if (client != null && client.isOpen) {
            runCatching { client.send(payload) }
                .onSuccess {
                    WindowsBridgeLogger.outbound(typeWire, normalized.id, tool, normalized.seq, queued = false)
                }
                .onFailure { ex ->
                    WindowsBridgeLogger.error("send failed: ${ex.message}", ex)
                    enqueuePending(payload)
                }
                .isSuccess
        } else {
            enqueuePending(payload)
            WindowsBridgeLogger.outbound(typeWire, normalized.id, tool, normalized.seq, queued = true)
            false
        }
    }

    fun sendToolCall(toolCall: BridgeToolCall) {
        val envelope = buildEnvelope(
            type = BridgeMessageType.TOOL_CALL,
            sessionId = toolCall.sessionId,
            payload = toolCallPayload(toolCall)
        )
        sendEnvelope(envelope)
    }

    fun sendApprovalDecision(decision: ApprovalDecision) {
        val type = if (decision.approved) {
            BridgeMessageType.APPROVAL_ACCEPTED
        } else {
            BridgeMessageType.APPROVAL_REJECTED
        }
        val envelope = buildEnvelope(
            type = type,
            sessionId = decision.sessionId,
            payload = approvalDecisionPayload(decision)
        )
        sendEnvelope(envelope)
    }

    fun pauseSession() = sendSessionControl(BridgeMessageType.AGENT_PAUSED)
    fun resumeSession() = sendSessionControl(BridgeMessageType.AGENT_RESUMED)
    fun cancelSession() = sendSessionControl(BridgeMessageType.AGENT_CANCELLED)

    /**
     * Inform the Windows Bridge that the Android-side Agent Control flag has changed.
     * The status is piggy-backed on [BridgeMessageType.AGENT_STATUS] with a
     * payload-level `status` discriminator — no new message type is introduced.
     */
    fun sendAgentControlStatus(enabled: Boolean) {
        val envelope = buildEnvelope(
            type = BridgeMessageType.AGENT_STATUS,
            sessionId = currentSessionId,
            payload = mapOf(
                "status" to "agent_control_changed",
                "agentControlEnabled" to enabled,
                "source" to "android_agent"
            )
        )
        sendEnvelope(envelope)
    }

    fun closeSession() {
        sendSessionControl(BridgeMessageType.SESSION_CLOSED)
        disconnect()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    @Synchronized
    private fun replaceConfigAndReconnect(overridden: WindowsBridgeClientConfig) {
        manualClose = false
        reconnectAttempts = 0
        // Replace the in-flight socket with the new config's target.
        closeSocketQuietly()
        openSocket(overridden)
    }

    private fun openSocket(target: WindowsBridgeClientConfig = config) {
        updateState(WindowsBridgeConnectionState.CONNECTING)
        val uri = URI(target.url)
        val headers = buildHandshakeHeaders(target)
        val client = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                if (wsClient !== this) return
                WindowsBridgeLogger.connected(target.host, target.port)
                reconnectAttempts = 0
                updateState(WindowsBridgeConnectionState.CONNECTED)
                emit(WindowsBridgeClientEvent.Connected(target.host, target.port))
                flushPending()
            }

            override fun onMessage(message: String?) {
                val text = message ?: return
                handleIncoming(text)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (wsClient !== this) return
                WindowsBridgeLogger.disconnected(code, reason, remote)
                emit(WindowsBridgeClientEvent.Disconnected(code, reason, remote))
                if (manualClose) {
                    updateState(WindowsBridgeConnectionState.DISCONNECTED)
                } else if (target.reconnectEnabled) {
                    scheduleReconnect(target)
                } else {
                    updateState(WindowsBridgeConnectionState.DISCONNECTED)
                }
            }

            override fun onError(ex: Exception?) {
                if (wsClient !== this) return
                WindowsBridgeLogger.error("ws error: ${ex?.message}", ex)
                emit(WindowsBridgeClientEvent.Error(ex?.message ?: "websocket error", ex))
            }
        }
        wsClient = client
        client.connectionLostTimeout = 10
        runCatching { client.connect() }.onFailure { ex ->
            WindowsBridgeLogger.error("connect() threw: ${ex.message}", ex)
            emit(WindowsBridgeClientEvent.Error("connect failed: ${ex.message}", ex))
            if (!manualClose && target.reconnectEnabled) scheduleReconnect(target)
            else updateState(WindowsBridgeConnectionState.ERROR)
        }
    }

    private fun scheduleReconnect(target: WindowsBridgeClientConfig) {
        if (manualClose) return
        if (reconnectJob?.isActive == true) return
        updateState(WindowsBridgeConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            while (isActive && !manualClose) {
                val attempt = ++reconnectAttempts
                if (target.reconnectMaxAttempts > 0 && attempt > target.reconnectMaxAttempts) {
                    WindowsBridgeLogger.error("giving up reconnect after $attempt attempts")
                    updateState(WindowsBridgeConnectionState.ERROR)
                    break
                }
                val delayMs = backoffDelay(attempt)
                WindowsBridgeLogger.reconnectAttempt(attempt, delayMs)
                delay(delayMs)
                if (manualClose) break
                // openSocket() swaps the wsClient ref, so the old onClose will be ignored.
                openSocket(target)
                // Give the socket a chance to open before scheduling the next attempt.
                waitForTransition(target)
                if (_connectionState.value == WindowsBridgeConnectionState.CONNECTED) break
            }
        }
    }

    private suspend fun waitForTransition(
        target: WindowsBridgeClientConfig,
        budgetMs: Long = 6000L
    ) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val state = _connectionState.value
            if (state == WindowsBridgeConnectionState.CONNECTED) return
            if (state == WindowsBridgeConnectionState.ERROR) return
            if (manualClose) return
            delay(150)
        }
        // Timed out waiting for the socket to come up; bump target for next iteration.
        @Suppress("UNUSED_VARIABLE")
        val _unused = target
    }

    private fun handleIncoming(raw: String) {
        val decoded = WindowsBridgeEnvelopeMapper.decode(raw)
        when (decoded) {
            is WindowsBridgeEnvelopeMapper.DecodeResult.Failure -> {
                WindowsBridgeLogger.parseError(decoded.reason)
                emit(WindowsBridgeClientEvent.Error(decoded.reason, decoded.cause))
            }
            is WindowsBridgeEnvelopeMapper.DecodeResult.Success -> {
                val envelope = decoded.envelope
                if (!acceptSeq(envelope.seq)) return
                WindowsBridgeLogger.inbound(
                    type = envelope.type.wireName,
                    id = envelope.id,
                    tool = envelope.payload["tool"] as? String,
                    seq = envelope.seq
                )
                dispatch(envelope)
            }
        }
    }

    private fun acceptSeq(seq: Long): Boolean {
        // Seq 0 / negative means "unset" — accept without dedupe.
        if (seq <= 0) return true
        val previous = lastIncomingSeq
        if (previous in 0 until seq) {
            if (seq > previous + 1) {
                WindowsBridgeLogger.seqGap(previous, seq)
            }
            lastIncomingSeq = seq
            return true
        }
        if (seq == previous) {
            WindowsBridgeLogger.duplicateSeq(seq)
            return false
        }
        // seq < previous: out-of-order; accept but do not rewind cursor.
        return true
    }

    private fun dispatch(envelope: BridgeEnvelope) {
        envelope.sessionId?.let { currentSessionId = it }
        when (envelope.type) {
            BridgeMessageType.SESSION_CREATED -> {
                val sid = envelope.sessionId ?: (envelope.payload["sessionId"] as? String)
                if (sid != null) currentSessionId = sid
                emit(WindowsBridgeClientEvent.SessionCreated(sid ?: ""))
            }
            BridgeMessageType.SESSION_CLOSED -> {
                val reason = envelope.payload["reason"] as? String
                WindowsBridgeLogger.sessionClosed(envelope.sessionId, reason)
                emit(WindowsBridgeClientEvent.SessionClosed(envelope.sessionId, reason))
            }
            BridgeMessageType.DEVICE_PAIRED -> {
                val paired = envelope.payload["deviceId"] as? String ?: envelope.deviceId
                emit(WindowsBridgeClientEvent.DevicePaired(envelope.sessionId, paired))
            }
            BridgeMessageType.DEVICE_DISCONNECTED -> {
                val reason = envelope.payload["reason"] as? String
                emit(WindowsBridgeClientEvent.DeviceDisconnected(envelope.sessionId, reason))
            }
            BridgeMessageType.SCREEN_FRAME ->
                emit(WindowsBridgeClientEvent.ScreenFrameReceived(envelope))
            BridgeMessageType.SCREEN_CAPTURE_RESULT ->
                emit(WindowsBridgeClientEvent.ScreenCaptureResultReceived(envelope))
            BridgeMessageType.TOOL_RESULT -> {
                WindowsBridgeEnvelopeMapper.decodeToolResult(envelope)?.let {
                    emit(WindowsBridgeClientEvent.ToolResultReceived(it))
                } ?: emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            }
            BridgeMessageType.TOOL_ERROR -> {
                WindowsBridgeEnvelopeMapper.decodeToolError(envelope)?.let {
                    emit(WindowsBridgeClientEvent.ToolErrorReceived(it))
                } ?: emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            }
            BridgeMessageType.APPROVAL_REQUEST -> {
                WindowsBridgeEnvelopeMapper.decodeApprovalRequest(envelope)?.let {
                    emit(WindowsBridgeClientEvent.ApprovalRequestReceived(it))
                } ?: emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            }
            BridgeMessageType.APPROVAL_ACCEPTED ->
                emit(WindowsBridgeClientEvent.ApprovalAcceptedReceived(envelope))
            BridgeMessageType.APPROVAL_REJECTED ->
                emit(WindowsBridgeClientEvent.ApprovalRejectedReceived(envelope))
            BridgeMessageType.AGENT_STATUS,
            BridgeMessageType.AGENT_STEP,
            BridgeMessageType.AGENT_PAUSED,
            BridgeMessageType.AGENT_RESUMED,
            BridgeMessageType.AGENT_CANCELLED ->
                emit(WindowsBridgeClientEvent.AgentUpdateReceived(envelope.type, envelope))
            BridgeMessageType.AUDIT_EVENT -> {
                WindowsBridgeEnvelopeMapper.decodeAuditEvent(envelope)?.let {
                    emit(WindowsBridgeClientEvent.AuditEventReceived(it))
                } ?: emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            }
            BridgeMessageType.ERROR -> {
                WindowsBridgeEnvelopeMapper.decodeProtocolError(envelope)?.let {
                    WindowsBridgeLogger.protocolError("${it.code}: ${it.message}")
                    emit(WindowsBridgeClientEvent.ProtocolError(it))
                } ?: emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
            }
            BridgeMessageType.TOOL_CALL ->
                // Unexpected (bridge shouldn't initiate tool calls), but forward raw.
                emit(WindowsBridgeClientEvent.EnvelopeReceived(envelope))
        }
    }

    private fun sendSessionControl(type: BridgeMessageType) {
        val envelope = buildEnvelope(
            type = type,
            sessionId = currentSessionId,
            payload = emptyMap()
        )
        sendEnvelope(envelope)
    }

    private fun buildEnvelope(
        type: BridgeMessageType,
        sessionId: String?,
        payload: Map<String, Any?>
    ): BridgeEnvelope = BridgeEnvelope(
        id = UUID.randomUUID().toString(),
        type = type,
        sessionId = sessionId ?: currentSessionId,
        deviceId = config.deviceId,
        seq = nextOutgoingSeq(),
        timestamp = System.currentTimeMillis(),
        payload = payload,
        metadata = emptyMap()
    )

    private fun toolCallPayload(call: BridgeToolCall): Map<String, Any?> = mapOf(
        "id" to call.id,
        "sessionId" to call.sessionId,
        "tool" to call.tool,
        "args" to call.args,
        "risk" to call.risk.wireName,
        "requiresApproval" to call.requiresApproval,
        "createdAt" to call.createdAt,
        "timeoutMs" to call.timeoutMs,
        "metadata" to call.metadata
    )

    private fun approvalDecisionPayload(decision: ApprovalDecision): Map<String, Any?> = mapOf(
        "requestId" to decision.requestId,
        "sessionId" to decision.sessionId,
        "toolCallId" to decision.toolCallId,
        "approved" to decision.approved,
        "decidedAt" to decision.decidedAt,
        "reason" to decision.reason
    )

    private fun nextOutgoingSeq(): Long = outgoingSeq.incrementAndGet()

    private fun buildHandshakeHeaders(target: WindowsBridgeClientConfig): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["X-Amaya-Device-Id"] = target.deviceId
        target.sessionId?.let { headers["X-Amaya-Session-Id"] = it }
        target.token?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private fun enqueuePending(payload: String) {
        synchronized(pendingLock) {
            while (pendingOutbound.size >= maxPendingOutbound) {
                pendingOutbound.pollFirst()
            }
            pendingOutbound.addLast(payload)
        }
    }

    private fun flushPending() {
        val client = wsClient ?: return
        synchronized(pendingLock) {
            while (client.isOpen && pendingOutbound.isNotEmpty()) {
                val payload = pendingOutbound.pollFirst() ?: break
                val ok = runCatching { client.send(payload) }.isSuccess
                if (!ok) {
                    pendingOutbound.addFirst(payload)
                    break
                }
            }
        }
    }

    private fun closeSocketQuietly() {
        runCatching { wsClient?.close() }
        wsClient = null
    }

    private fun updateState(next: WindowsBridgeConnectionState) {
        if (_connectionState.value == next) return
        _connectionState.value = next
        runCatching { handler.onConnectionStateChanged(next) }
    }

    private fun emit(event: WindowsBridgeClientEvent) {
        _events.tryEmit(event)
        runCatching { handler.onEvent(event) }
    }

    private fun backoffDelay(attempt: Int): Long {
        val base = 1000L
        val capped = base * (1L shl (attempt - 1).coerceIn(0, 5))
        val jitter = (0..350).random().toLong()
        return (capped + jitter).coerceAtMost(30000L)
    }
}
