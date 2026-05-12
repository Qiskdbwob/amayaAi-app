package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.domain.bridge.ApprovalDecision
import com.amaya.intelligence.domain.bridge.ApprovalRequest
import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeRiskLevel
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeClientConfig
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeClientEvent
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped controller that owns the [WindowsBridgeSessionClient] and the
 * [WindowsBridgeToolExecutor] adapter.
 *
 * Responsibilities:
 *  - Hold the bridge client lifecycle so callers have a single injectable object.
 *  - Gate Agent Control from the Android side (MEDIUM tool visibility) and
 *    synchronise the flag with the Windows bridge over `agent.status`.
 *  - Route incoming approval requests to callers and forward decisions back.
 *
 * The controller is dormant until [connect] is called. When dormant, bridge tools
 * remain hidden from the model and any attempt to execute them short-circuits with
 * a clear error.
 */
@Singleton
class WindowsBridgeController @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val clientRef = AtomicReference<WindowsBridgeSessionClient?>(null)
    private val executorRef = AtomicReference<WindowsBridgeToolExecutor?>(null)
    private val registry = WindowsBridgeToolRegistry()

    /**
     * Replays envelopes received from the bridge to opportunistic subscribers
     * (e.g. the opencode agent client). Unlike `WindowsBridgeSessionClient.events`
     * the flow survives client reconnects because it lives on the controller.
     */
    private val _envelopes = MutableSharedFlow<BridgeEnvelope>(extraBufferCapacity = 128)
    val envelopes: SharedFlow<BridgeEnvelope> = _envelopes.asSharedFlow()

    private val _agentControlEnabled = MutableStateFlow(false)
    val agentControlEnabled: StateFlow<Boolean> = _agentControlEnabled.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _approvalEvents = MutableSharedFlow<ApprovalRequest>(extraBufferCapacity = 16)
    val approvalEvents: SharedFlow<ApprovalRequest> = _approvalEvents.asSharedFlow()

    /** Session id of the currently active bridge session, if any. */
    @Volatile private var activeSessionId: String? = null

    private var eventPump: Job? = null

    /**
     * True when the user has explicitly unlocked Agent Control input tools. The
     * setter also sends an `agent.status` envelope to the Windows side so its risk
     * engine agrees with Android on the current state.
     */
    fun setAgentControlEnabled(enabled: Boolean) {
        val changed = _agentControlEnabled.value != enabled
        _agentControlEnabled.value = enabled
        if (changed) {
            clientRef.get()?.sendAgentControlStatus(enabled)
        }
    }

    /**
     * Emergency stop: disable Agent Control, send `agent.cancelled` to Windows,
     * clear pending approvals. Windows will reject all MEDIUM tools until resumed.
     */
    fun emergencyStop() {
        _agentControlEnabled.value = false
        clientRef.get()?.cancelSession()
        _pendingApproval.value = null
    }

    /**
     * Resume after emergency stop. Agent Control remains OFF — user must re-enable
     * it manually for safety.
     */
    fun resumeSession() {
        clientRef.get()?.resumeSession()
    }

    /** True when both the client is CONNECTED and a session id is known. */
    fun isActive(): Boolean {
        val client = clientRef.get() ?: return false
        return client.connectionState.value == WindowsBridgeConnectionState.CONNECTED &&
            !activeSessionId.isNullOrBlank()
    }

    fun currentSessionId(): String? = activeSessionId

    fun currentConnectionState(): WindowsBridgeConnectionState =
        clientRef.get()?.connectionState?.value ?: WindowsBridgeConnectionState.DISCONNECTED

    /**
     * Create (or replace) the underlying client with [config] and open the socket.
     * Idempotent: calling [connect] with the same config while already connected is a
     * no-op.
     */
    @Synchronized
    fun connect(config: WindowsBridgeClientConfig) {
        val existing = clientRef.get()
        if (existing != null) {
            // Hand off to the existing client's runtime override so we don't lose
            // subscriptions built by the current executor.
            existing.connect(config.host, config.port, config.token)
            return
        }
        val client = WindowsBridgeSessionClient(config = config, scope = appScope)
        clientRef.set(client)
        executorRef.set(WindowsBridgeToolExecutor(client = client, registry = registry, scope = appScope))
        activeSessionId = config.sessionId
        startEventPump(client)
        client.connect()
    }

    /** Manually disconnect and tear down the client/executor. */
    @Synchronized
    fun disconnect() {
        eventPump?.cancel()
        eventPump = null
        executorRef.getAndSet(null)?.dispose()
        clientRef.getAndSet(null)?.disconnect()
        activeSessionId = null
        _pendingApproval.value = null
    }

    /**
     * Answer the currently pending approval (or any approval by id). Safe to call
     * even if the request has already expired — extra decisions are logged and
     * ignored by the Windows side.
     */
    fun respondApproval(requestId: String, approved: Boolean, reason: String? = null) {
        val pending = _pendingApproval.value
        val sessionId = pending?.sessionId ?: activeSessionId ?: return
        val toolCallId = pending?.toolCallId ?: run {
            // Caller answered by id alone. Without the original toolCallId we can
            // still respond, but use a blank so the Windows side falls back to the
            // request-scoped lookup.
            ""
        }
        clientRef.get()?.sendApprovalDecision(
            ApprovalDecision(
                requestId = requestId,
                sessionId = sessionId,
                toolCallId = if (pending?.id == requestId) pending.toolCallId else toolCallId,
                approved = approved,
                reason = reason
            )
        )
        if (pending?.id == requestId) {
            _pendingApproval.value = null
        }
    }

    /** Convenience helper when callers only track the latest pending request. */
    fun respondPending(approved: Boolean, reason: String? = null) {
        val pending = _pendingApproval.value ?: return
        respondApproval(pending.id, approved, reason)
    }

    /**
     * Registry-level snapshot for the given agent-control flag. Exposed so callers can
     * decide what to advertise to the model without going through the executor.
     */
    fun availability(): WindowsBridgeToolAvailability {
        val executor = executorRef.get()
            ?: return WindowsBridgeToolAvailability.unavailable(
                state = currentConnectionState(),
                enabled = visibleToolNames(),
                reason = "Windows Bridge is not initialized."
            )
        val underlying = executor.availability()
        return underlying.copy(
            sessionId = activeSessionId,
            enabledTools = visibleToolNames(connected = underlying.isConnected)
        )
    }

    /**
     * Names of bridge tools eligible to be advertised to the model right now.
     *
     * Rules:
     *  - Empty when the bridge is not connected.
     *  - Always includes LOW-risk enabled tools (screen capture, window list).
     *  - Includes MEDIUM-risk input tools only when Agent Control is enabled.
     *  - Includes HIGH-risk tools only when they are enabled by default, require approval,
     *    and Agent Control is enabled (Windows side still asks for approval before running).
     *  - Never includes tools whose `enabledByDefault` is false.
     */
    fun visibleToolNames(connected: Boolean = isActive()): Set<String> {
        if (!connected) return emptySet()
        val agentControl = _agentControlEnabled.value
        return registry.enabledSpecs()
            .filter { spec ->
                when (spec.risk) {
                    BridgeRiskLevel.LOW -> true
                    BridgeRiskLevel.MEDIUM -> agentControl
                    BridgeRiskLevel.HIGH -> agentControl && spec.requiresApproval
                    BridgeRiskLevel.BLOCKED -> false
                }
            }
            .map { it.name }
            .toSet()
    }

    /** Executor adapter. Null when the controller has never been connected. */
    fun executor(): WindowsBridgeToolExecutor? = executorRef.get()

    /** Registry — always safe to read. */
    fun registry(): WindowsBridgeToolRegistry = registry

    /** True when [toolName] is a known bridge tool wire name. */
    fun isBridgeTool(toolName: String): Boolean = registry.isKnown(toolName)

    /** True when [toolName] may be advertised / executed right now. */
    fun isToolVisible(toolName: String): Boolean =
        toolName in visibleToolNames()

    // ── Event pump ──────────────────────────────────────────────────────────

    private fun startEventPump(client: WindowsBridgeSessionClient) {
        eventPump?.cancel()
        eventPump = appScope.launch {
            client.events.collect { event -> handleEvent(event) }
        }
    }

    private fun handleEvent(event: WindowsBridgeClientEvent) {
        when (event) {
            is WindowsBridgeClientEvent.EnvelopeReceived -> {
                _envelopes.tryEmit(event.envelope)
            }
            is WindowsBridgeClientEvent.SessionCreated -> {
                if (event.sessionId.isNotBlank()) activeSessionId = event.sessionId
                // Push the latest Agent Control state to the bridge after a reconnect
                // so the two sides never drift.
                clientRef.get()?.sendAgentControlStatus(_agentControlEnabled.value)
            }
            is WindowsBridgeClientEvent.ApprovalRequestReceived -> {
                _pendingApproval.value = event.request
                _approvalEvents.tryEmit(event.request)
            }
            is WindowsBridgeClientEvent.SessionClosed,
            is WindowsBridgeClientEvent.DeviceDisconnected,
            is WindowsBridgeClientEvent.Disconnected -> {
                _pendingApproval.value = null
            }
            else -> { /* other events handled by the tool executor */ }
        }
    }

    /**
     * Send an envelope through the active bridge client. Returns false when there
     * is no client yet (the controller hasn't been connected).
     */
    fun sendEnvelope(envelope: BridgeEnvelope): Boolean {
        return clientRef.get()?.sendEnvelope(envelope) ?: false
    }
}
