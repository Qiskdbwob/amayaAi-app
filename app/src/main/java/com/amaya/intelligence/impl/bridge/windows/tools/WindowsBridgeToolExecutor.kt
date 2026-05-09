package com.amaya.intelligence.impl.bridge.windows.tools

import com.amaya.intelligence.domain.bridge.BridgeToolError
import com.amaya.intelligence.domain.bridge.BridgeToolErrorCode
import com.amaya.intelligence.domain.bridge.BridgeToolResult
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeClientEvent
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeSessionClient
import com.amaya.intelligence.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter that executes a Windows Bridge tool-call on behalf of the Android agent.
 *
 * The flow for a single [execute] is:
 *
 *  1. Build an [availability] snapshot. If the bridge is offline / not paired /
 *     tool disabled, return a descriptive [ToolResult.Error] immediately.
 *  2. Map the call to [com.amaya.intelligence.domain.bridge.BridgeToolCall] and hand
 *     it to [WindowsBridgeSessionClient.sendToolCall].
 *  3. Park a [CompletableDeferred] keyed by `toolCallId` until the client emits a
 *     matching `ToolResultReceived` / `ToolErrorReceived`, or the per-call timeout
 *     expires, or the session is closed/errored.
 *  4. Map the bridge outcome back to an existing [ToolResult].
 *
 * This adapter does not modify `ToolExecutor`. It is meant to be wired as an extra
 * provider by a future feature-flag / Agent Control surface.
 */
class WindowsBridgeToolExecutor(
    private val client: WindowsBridgeSessionClient,
    private val registry: WindowsBridgeToolRegistry = WindowsBridgeToolRegistry(),
    scope: CoroutineScope? = null
) {

    private val ownScope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pending = ConcurrentHashMap<String, Pending>()
    private val approvalListeners = mutableListOf<(com.amaya.intelligence.domain.bridge.ApprovalRequest) -> Unit>()
    private val approvalLock = Any()

    private data class Pending(
        val toolCallId: String,
        val toolName: String,
        val timeoutMs: Long?,
        val deferred: CompletableDeferred<Outcome>
    )

    private sealed class Outcome {
        data class Ok(val result: BridgeToolResult) : Outcome()
        data class Fail(val error: BridgeToolError) : Outcome()
        data class Cancelled(val reason: String) : Outcome()
    }

    /** Lifecycle-scoped subscription to the client's events. */
    private val subscription: Job = ownScope.launch {
        client.events.collect { handleEvent(it) }
    }

    /** Snapshot of current availability, useful for UI and pre-flight checks. */
    fun availability(): WindowsBridgeToolAvailability {
        val state = client.connectionState.value
        val enabled = registry.enabledNames()
        return when (state) {
            WindowsBridgeConnectionState.CONNECTED -> WindowsBridgeToolAvailability(
                isConnected = true,
                sessionId = null, // populated per-call by the session client
                devicePaired = true,
                connectionState = state,
                enabledTools = enabled,
                reasonIfUnavailable = null
            )
            WindowsBridgeConnectionState.PAUSED -> WindowsBridgeToolAvailability(
                isConnected = true,
                sessionId = null,
                devicePaired = true,
                connectionState = state,
                enabledTools = enabled,
                reasonIfUnavailable = "Windows Bridge session is paused."
            )
            WindowsBridgeConnectionState.CONNECTING,
            WindowsBridgeConnectionState.RECONNECTING ->
                WindowsBridgeToolAvailability.unavailable(
                    state, enabled, "Windows Bridge is still connecting."
                )
            WindowsBridgeConnectionState.CLOSING ->
                WindowsBridgeToolAvailability.unavailable(
                    state, enabled, "Windows Bridge session is closing."
                )
            WindowsBridgeConnectionState.ERROR ->
                WindowsBridgeToolAvailability.unavailable(
                    state, enabled, "Windows Bridge reported a connection error."
                )
            WindowsBridgeConnectionState.DISCONNECTED ->
                WindowsBridgeToolAvailability.unavailable(
                    state, enabled,
                    "Windows Bridge is not connected. Connect to a Windows Bridge session first."
                )
        }
    }

    /** Subscribe to bridge-originated approval requests (Phase 3 stub). */
    fun addApprovalListener(listener: (com.amaya.intelligence.domain.bridge.ApprovalRequest) -> Unit) {
        synchronized(approvalLock) { approvalListeners += listener }
    }

    fun removeApprovalListener(listener: (com.amaya.intelligence.domain.bridge.ApprovalRequest) -> Unit) {
        synchronized(approvalLock) { approvalListeners -= listener }
    }

    /**
     * Execute a Windows Bridge tool by wire name with [arguments].
     *
     * @param sessionId If non-null, used as `BridgeToolCall.sessionId`. If null, the
     *   executor returns [ToolResult.Error] because tool calls without a session are
     *   not meaningful.
     */
    suspend fun execute(
        toolName: String,
        arguments: Map<String, Any?>,
        sessionId: String?
    ): ToolResult {
        val spec = registry.find(toolName)
            ?: return WindowsBridgeToolResultMapper.unknown(toolName)

        if (!spec.enabledByDefault) {
            return WindowsBridgeToolResultMapper.disabled(toolName)
        }

        val availability = availability()
        if (!availability.isAvailable) {
            return WindowsBridgeToolResultMapper.unavailable(
                toolName,
                availability.reasonIfUnavailable
                    ?: "Windows Bridge is not available."
            )
        }
        if (sessionId.isNullOrBlank()) {
            return WindowsBridgeToolResultMapper.unavailable(
                toolName,
                "Windows Bridge session is not established yet."
            )
        }

        val toolCall = WindowsBridgeToolMapper.toBridgeToolCall(
            spec = spec,
            sessionId = sessionId,
            arguments = arguments
        )

        val deferred = CompletableDeferred<Outcome>()
        val entry = Pending(
            toolCallId = toolCall.id,
            toolName = toolCall.tool,
            timeoutMs = toolCall.timeoutMs,
            deferred = deferred
        )
        pending[toolCall.id] = entry
        try {
            client.sendToolCall(toolCall)
            val timeout = toolCall.timeoutMs ?: DEFAULT_TIMEOUT_MS
            val outcome = withTimeoutOrNull(timeout) { deferred.await() }
                ?: run {
                    pending.remove(toolCall.id)
                    return WindowsBridgeToolResultMapper.timeout(
                        toolName = toolCall.tool,
                        toolCallId = toolCall.id,
                        timeoutMs = timeout
                    )
                }
            return when (outcome) {
                is Outcome.Ok -> WindowsBridgeToolResultMapper.toSuccess(outcome.result)
                is Outcome.Fail -> WindowsBridgeToolResultMapper.toError(outcome.error)
                is Outcome.Cancelled ->
                    WindowsBridgeToolResultMapper.cancelled(toolCall.tool, outcome.reason)
            }
        } finally {
            pending.remove(toolCall.id)
        }
    }

    /** Cancel the executor's event subscription. Does not disconnect the client. */
    fun dispose() {
        subscription.cancel()
        failAllPending("Windows Bridge tool executor disposed.")
    }

    // ── Event pump ──────────────────────────────────────────────────────────

    private fun handleEvent(event: WindowsBridgeClientEvent) {
        when (event) {
            is WindowsBridgeClientEvent.ToolResultReceived -> {
                val entry = pending.remove(event.result.toolCallId) ?: return
                entry.deferred.complete(Outcome.Ok(event.result))
            }
            is WindowsBridgeClientEvent.ToolErrorReceived -> {
                val entry = pending.remove(event.error.toolCallId) ?: return
                entry.deferred.complete(Outcome.Fail(event.error))
            }
            is WindowsBridgeClientEvent.SessionClosed ->
                failAllPending("Windows Bridge session closed${event.reason?.let { ": $it" } ?: "."}")
            is WindowsBridgeClientEvent.DeviceDisconnected ->
                failAllPending("Windows Bridge device disconnected${event.reason?.let { ": $it" } ?: "."}")
            is WindowsBridgeClientEvent.Disconnected ->
                failAllPending("Windows Bridge transport disconnected.")
            is WindowsBridgeClientEvent.ProtocolError ->
                failAllPending("Windows Bridge protocol error: ${event.error.message}")
            is WindowsBridgeClientEvent.ApprovalRequestReceived -> notifyApproval(event.request)
            else -> { /* informational */ }
        }
    }

    private fun notifyApproval(request: com.amaya.intelligence.domain.bridge.ApprovalRequest) {
        val snapshot = synchronized(approvalLock) { approvalListeners.toList() }
        for (listener in snapshot) runCatching { listener(request) }
    }

    private fun failAllPending(reason: String) {
        val entries = pending.values.toList()
        pending.clear()
        for (entry in entries) {
            val error = BridgeToolError(
                toolCallId = entry.toolCallId,
                sessionId = "",
                tool = entry.toolName,
                code = BridgeToolErrorCode.SESSION_CLOSED,
                message = reason,
                recoverable = true
            )
            entry.deferred.complete(Outcome.Fail(error))
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}
