package com.amaya.intelligence.impl.ide.opencode

import com.amaya.intelligence.domain.bridge.AgentEventKind
import com.amaya.intelligence.domain.bridge.AgentMessagePart
import com.amaya.intelligence.domain.bridge.BridgeEnvelope
import com.amaya.intelligence.domain.bridge.BridgeMessageType
import com.amaya.intelligence.impl.bridge.windows.tools.OpencodeBridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android facade for the opencode runtime exposed by the Windows Bridge.
 *
 * Talks to the bridge only via the small [OpencodeBridgeTransport] surface so
 * unit tests can swap in a fake transport.
 */
@Singleton
class OpencodeClient @Inject constructor(
    private val transport: OpencodeBridgeTransport
) {

    sealed class Event {
        data class Runtime(val info: OpencodeRuntimeSnapshot) : Event()
        data class Config(val json: String, val configPath: String?) : Event()
        data class Providers(val providers: List<OpencodeProviderSummary>) : Event()
        data class Models(
            val models: List<OpencodeModelSummary>,
            val defaultProviderId: String?,
            val defaultModelId: String?
        ) : Event()
        data class Mcp(val servers: List<OpencodeMcpSummary>) : Event()
        data class Sessions(val sessions: List<OpencodeSessionSummary>) : Event()
        data class SessionCreated(val session: OpencodeSessionSummary) : Event()
        data class SessionDeleted(val sessionId: String) : Event()
        data class MessagePart(val update: OpencodeMessagePartUpdate) : Event()
        data class PermissionAsked(val request: OpencodePermissionRequest) : Event()
        data class SessionStatus(
            val sessionId: String?,
            val status: String?,
            val data: Map<String, Any?>
        ) : Event()
        data class SessionError(val sessionId: String?, val message: String) : Event()
        data class PlanUpdate(val sessionId: String?, val entries: List<Map<String, Any?>>) : Event()
        data class TodoUpdate(val sessionId: String?, val todos: List<Map<String, Any?>>) : Event()
        data class Error(val message: String) : Event()
    }

    private val _runtime = MutableStateFlow(OpencodeRuntimeSnapshot.STOPPED)
    val runtime: StateFlow<OpencodeRuntimeSnapshot> = _runtime.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 128)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _pendingPermission = MutableStateFlow<OpencodePermissionRequest?>(null)
    val pendingPermission: StateFlow<OpencodePermissionRequest?> = _pendingPermission.asStateFlow()

    private var subscriptionJob: Job? = null
    @Volatile private var attached: Boolean = false
    private val attachLock = Any()

    /**
     * Start consuming bridge events. Safe to call multiple times; only the first call
     * installs the collector.
     */
    fun attach(scope: CoroutineScope) {
        synchronized(attachLock) {
            if (attached) return
            attached = true
        }
        subscriptionJob = scope.launch {
            transport.envelopes.collect { envelope ->
                runCatching { handleEnvelope(envelope) }
                    .onFailure {
                        _events.tryEmit(Event.Error("OpencodeClient dispatch failed: ${it.message}"))
                    }
            }
        }
    }

    fun detach() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        synchronized(attachLock) { attached = false }
    }

    // ── Outbound envelopes ───────────────────────────────────────────────────

    fun requestRuntimeStatus() = send(
        BridgeMessageType.AGENT_RUNTIME_STATUS_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun startRuntime(port: Int? = null, hostname: String? = null, configJson: String? = null) =
        send(
            BridgeMessageType.AGENT_RUNTIME_START,
            buildMap {
                put("runtimeId", OPENCODE_RUNTIME_ID)
                if (port != null) put("port", port)
                if (hostname != null) put("hostname", hostname)
                if (configJson != null) put("configJson", configJson)
            }
        )

    fun stopRuntime() = send(
        BridgeMessageType.AGENT_RUNTIME_STOP,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun restartRuntime() = send(
        BridgeMessageType.AGENT_RUNTIME_RESTART,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun requestConfig() = send(
        BridgeMessageType.AGENT_CONFIG_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun requestProviders() = send(
        BridgeMessageType.AGENT_PROVIDER_LIST_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun requestModels() = send(
        BridgeMessageType.AGENT_MODEL_LIST_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun requestMcp() = send(
        BridgeMessageType.AGENT_MCP_LIST_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun requestSessions() = send(
        BridgeMessageType.AGENT_SESSION_LIST_REQUEST,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID)
    )

    fun createSession(title: String? = null, agent: String? = null, modelId: String? = null, providerId: String? = null) =
        send(
            BridgeMessageType.AGENT_SESSION_CREATE,
            buildMap {
                put("runtimeId", OPENCODE_RUNTIME_ID)
                if (title != null) put("title", title)
                if (agent != null) put("agent", agent)
                if (providerId != null && modelId != null) {
                    put("model", mapOf("providerId" to providerId, "modelId" to modelId))
                }
            }
        )

    fun deleteSession(sessionId: String) = send(
        BridgeMessageType.AGENT_SESSION_DELETE,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID, "sessionId" to sessionId)
    )

    fun sendPrompt(
        sessionId: String,
        text: String,
        agent: String? = null,
        providerId: String? = null,
        modelId: String? = null,
        attachments: List<AgentMessagePart> = emptyList()
    ) {
        val parts = buildList<Map<String, Any?>> {
            add(mapOf("type" to AgentMessagePart.TYPE_TEXT, "text" to text))
            attachments.forEach { add(partToMap(it)) }
        }
        send(
            BridgeMessageType.AGENT_SESSION_PROMPT,
            buildMap {
                put("runtimeId", OPENCODE_RUNTIME_ID)
                put("sessionId", sessionId)
                put("parts", parts)
                if (agent != null) put("agent", agent)
                if (providerId != null && modelId != null) {
                    put("model", mapOf("providerId" to providerId, "modelId" to modelId))
                }
            }
        )
    }

    fun abortPrompt(sessionId: String) = send(
        BridgeMessageType.AGENT_SESSION_ABORT,
        mapOf("runtimeId" to OPENCODE_RUNTIME_ID, "sessionId" to sessionId)
    )

    fun replyPermission(sessionId: String, permissionId: String, reply: String) = send(
        BridgeMessageType.AGENT_PERMISSION_REPLY,
        mapOf(
            "runtimeId" to OPENCODE_RUNTIME_ID,
            "sessionId" to sessionId,
            "permissionId" to permissionId,
            "reply" to reply
        )
    )

    /**
     * Reply to whatever permission is currently pending. No-op when nothing is
     * awaiting a reply, so callers can wire this to buttons without guarding.
     */
    fun respondCurrentPermission(reply: String) {
        val pending = _pendingPermission.value ?: return
        replyPermission(pending.sessionId, pending.permissionId, reply)
        _pendingPermission.value = null
    }

    // ── Inbound handling ─────────────────────────────────────────────────────

    private fun handleEnvelope(envelope: BridgeEnvelope) {
        when (envelope.type) {
            BridgeMessageType.AGENT_RUNTIME_STATUS -> handleRuntimeStatus(envelope)
            BridgeMessageType.AGENT_CONFIG -> handleConfig(envelope)
            BridgeMessageType.AGENT_PROVIDER_LIST -> handleProviders(envelope)
            BridgeMessageType.AGENT_MODEL_LIST -> handleModels(envelope)
            BridgeMessageType.AGENT_MCP_LIST -> handleMcp(envelope)
            BridgeMessageType.AGENT_SESSION_LIST -> handleSessions(envelope)
            BridgeMessageType.AGENT_SESSION_CREATED -> handleSessionCreated(envelope)
            BridgeMessageType.AGENT_SESSION_DELETED -> handleSessionDeleted(envelope)
            BridgeMessageType.AGENT_EVENT -> handleAgentEvent(envelope)
            else -> Unit
        }
    }

    private fun handleRuntimeStatus(envelope: BridgeEnvelope) {
        val snapshot = envelope.payload.toRuntimeSnapshot() ?: return
        _runtime.update { snapshot }
        _events.tryEmit(Event.Runtime(snapshot))
    }

    private fun handleConfig(envelope: BridgeEnvelope) {
        val json = envelope.payload["configJson"] as? String ?: return
        val path = envelope.payload["configPath"] as? String
        _events.tryEmit(Event.Config(json, path))
    }

    private fun handleProviders(envelope: BridgeEnvelope) {
        val raw = envelope.payload["providers"] as? List<*> ?: return
        val providers = raw.mapNotNull { entry ->
            val obj = entry as? Map<*, *> ?: return@mapNotNull null
            val providerId = obj["providerId"] as? String ?: return@mapNotNull null
            val modelsRaw = obj["models"] as? List<*> ?: emptyList<Any?>()
            OpencodeProviderSummary(
                providerId = providerId,
                displayName = (obj["displayName"] as? String).orEmpty().ifEmpty { providerId },
                authenticated = (obj["authenticated"] as? Boolean) ?: true,
                defaultModelId = obj["defaultModelId"] as? String,
                modelCount = modelsRaw.size
            )
        }
        _events.tryEmit(Event.Providers(providers))
    }

    private fun handleModels(envelope: BridgeEnvelope) {
        val raw = envelope.payload["models"] as? List<*> ?: return
        val models = raw.mapNotNull { entry ->
            val obj = entry as? Map<*, *> ?: return@mapNotNull null
            val modelId = obj["modelId"] as? String ?: return@mapNotNull null
            val providerId = obj["providerId"] as? String ?: return@mapNotNull null
            OpencodeModelSummary(
                modelId = modelId,
                providerId = providerId,
                displayName = (obj["displayName"] as? String).orEmpty().ifEmpty { modelId },
                contextWindowTokens = (obj["contextWindowTokens"] as? Number)?.toInt(),
                maxOutputTokens = (obj["maxOutputTokens"] as? Number)?.toInt(),
                supportsImages = (obj["supportsImages"] as? Boolean) ?: false
            )
        }
        val def = envelope.payload["defaultModel"] as? Map<*, *>
        val defProvider = def?.get("providerId") as? String
        val defModel = def?.get("modelId") as? String
        _events.tryEmit(Event.Models(models, defProvider, defModel))
    }

    private fun handleMcp(envelope: BridgeEnvelope) {
        val raw = envelope.payload["servers"] as? List<*> ?: return
        val servers = raw.mapNotNull { entry ->
            val obj = entry as? Map<*, *> ?: return@mapNotNull null
            val name = obj["name"] as? String ?: return@mapNotNull null
            OpencodeMcpSummary(
                name = name,
                type = (obj["type"] as? String).orEmpty(),
                enabled = (obj["enabled"] as? Boolean) ?: false,
                connected = (obj["connected"] as? Boolean) ?: false,
                toolCount = (obj["toolCount"] as? Number)?.toInt() ?: 0
            )
        }
        _events.tryEmit(Event.Mcp(servers))
    }

    private fun handleSessions(envelope: BridgeEnvelope) {
        val raw = envelope.payload["sessions"] as? List<*> ?: return
        val sessions = raw.mapNotNull { entry -> (entry as? Map<*, *>)?.toSessionSummary() }
        _events.tryEmit(Event.Sessions(sessions))
    }

    private fun handleSessionCreated(envelope: BridgeEnvelope) {
        val obj = envelope.payload["session"] as? Map<*, *> ?: return
        val summary = obj.toSessionSummary() ?: return
        _events.tryEmit(Event.SessionCreated(summary))
    }

    private fun handleSessionDeleted(envelope: BridgeEnvelope) {
        val sessionId = envelope.payload["sessionId"] as? String ?: return
        _events.tryEmit(Event.SessionDeleted(sessionId))
    }

    private fun handleAgentEvent(envelope: BridgeEnvelope) {
        val kind = envelope.payload["kind"] as? String ?: return
        val sessionId = envelope.payload["sessionId"] as? String
        @Suppress("UNCHECKED_CAST")
        val data = (envelope.payload["data"] as? Map<String, Any?>) ?: emptyMap()
        when (kind) {
            AgentEventKind.MESSAGE_PART_TEXT -> _events.tryEmit(
                Event.MessagePart(
                    OpencodeMessagePartUpdate(
                        sessionId = sessionId.orEmpty(),
                        messageId = data["messageId"] as? String,
                        partId = data["partId"] as? String,
                        partType = OpencodeMessagePartUpdate.PartType.TEXT,
                        text = (data["text"] as? String).orEmpty()
                    )
                )
            )
            AgentEventKind.MESSAGE_PART_THOUGHT -> _events.tryEmit(
                Event.MessagePart(
                    OpencodeMessagePartUpdate(
                        sessionId = sessionId.orEmpty(),
                        messageId = data["messageId"] as? String,
                        partId = data["partId"] as? String,
                        partType = OpencodeMessagePartUpdate.PartType.THOUGHT,
                        text = (data["text"] as? String).orEmpty()
                    )
                )
            )
            AgentEventKind.MESSAGE_PART_TOOL -> _events.tryEmit(
                Event.MessagePart(
                    OpencodeMessagePartUpdate(
                        sessionId = sessionId.orEmpty(),
                        messageId = data["messageId"] as? String,
                        partId = data["partId"] as? String,
                        partType = OpencodeMessagePartUpdate.PartType.TOOL,
                        toolName = data["tool"] as? String,
                        toolState = (data["state"] as? Map<*, *>)?.get("status") as? String
                    )
                )
            )
            AgentEventKind.PERMISSION_ASKED -> {
                val permissionId = data["id"] as? String ?: data["permissionId"] as? String ?: return
                val request = OpencodePermissionRequest(
                    sessionId = sessionId.orEmpty(),
                    permissionId = permissionId,
                    title = (data["title"] as? String).orEmpty(),
                    kind = data["kind"] as? String,
                    description = data["description"] as? String
                )
                _pendingPermission.value = request
                _events.tryEmit(Event.PermissionAsked(request))
            }
            AgentEventKind.PERMISSION_REPLIED -> {
                val permissionId = data["id"] as? String ?: data["permissionId"] as? String
                val pending = _pendingPermission.value
                if (pending != null && (permissionId == null || pending.permissionId == permissionId)) {
                    _pendingPermission.value = null
                }
            }
            AgentEventKind.PLAN_UPDATE -> {
                @Suppress("UNCHECKED_CAST")
                val entries = (data["entries"] as? List<Map<String, Any?>>).orEmpty()
                _events.tryEmit(Event.PlanUpdate(sessionId, entries))
            }
            AgentEventKind.TODO_UPDATE -> {
                @Suppress("UNCHECKED_CAST")
                val todos = (data["todos"] as? List<Map<String, Any?>>).orEmpty()
                _events.tryEmit(Event.TodoUpdate(sessionId, todos))
            }
            AgentEventKind.SESSION_STATUS, AgentEventKind.SESSION_IDLE -> _events.tryEmit(
                Event.SessionStatus(
                    sessionId = sessionId,
                    status = (data["status"] as? Map<*, *>)?.get("type") as? String,
                    data = data
                )
            )
            AgentEventKind.SESSION_ERROR -> _events.tryEmit(
                Event.SessionError(
                    sessionId = sessionId,
                    message = (data["error"] as? Map<*, *>)?.get("name") as? String
                        ?: (data["reason"] as? String)
                        ?: "session error"
                )
            )
            else -> Unit
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun send(type: BridgeMessageType, payload: Map<String, Any?>) {
        val envelope = BridgeEnvelope(
            id = UUID.randomUUID().toString(),
            type = type,
            sessionId = null,
            deviceId = "", // filled by bridge client
            seq = 0L,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )
        transport.sendEnvelope(envelope)
    }

    private fun Map<String, Any?>.toRuntimeSnapshot(): OpencodeRuntimeSnapshot? {
        val status = this["status"] as? String ?: return null
        return OpencodeRuntimeSnapshot(
            status = status,
            version = this["version"] as? String,
            baseUrl = this["baseUrl"] as? String,
            binaryPath = this["binaryPath"] as? String,
            configPath = this["configPath"] as? String,
            lastError = this["lastError"] as? String,
            updatedAt = (this["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )
    }

    private fun Map<*, *>.toSessionSummary(): OpencodeSessionSummary? {
        val id = this["sessionId"] as? String ?: return null
        return OpencodeSessionSummary(
            sessionId = id,
            title = this["title"] as? String,
            createdAt = (this["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (this["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            agent = this["agent"] as? String,
            modelId = this["modelId"] as? String,
            providerId = this["providerId"] as? String
        )
    }

    private fun partToMap(part: AgentMessagePart): Map<String, Any?> = buildMap {
        put("type", part.type)
        part.text?.let { put("text", it) }
        part.url?.let { put("url", it) }
        part.mime?.let { put("mime", it) }
        part.filename?.let { put("filename", it) }
        part.dataBase64?.let { put("dataBase64", it) }
    }
}
