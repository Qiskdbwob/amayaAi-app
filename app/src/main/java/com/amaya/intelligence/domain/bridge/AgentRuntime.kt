package com.amaya.intelligence.domain.bridge

/**
 * Shared contract for CLI coding-agent runtimes (opencode, claude-code, codex, ...)
 * exposed by a bridge to the Android app. Keep these types platform-neutral and
 * transport-neutral — they travel inside [BridgeEnvelope.payload] and are mirrored
 * 1:1 in `windows-bridge/src/protocol/bridge-agent.ts`.
 *
 * Phase: opencode integration — only opencode is implemented today, but shapes are
 * generic so Claude Code and Codex adapters can reuse them without churn.
 */

/** Stable runtime identifiers used on the wire. */
object AgentRuntimeIds {
    const val OPENCODE = "opencode"
    const val CLAUDE_CODE = "claude_code"
    const val CODEX = "codex"
}

enum class AgentRuntimeStatus(val wireName: String) {
    STOPPED("stopped"),
    STARTING("starting"),
    READY("ready"),
    DEGRADED("degraded"),
    ERROR("error");

    companion object {
        private val byWire = values().associateBy { it.wireName }
        fun fromWireName(value: String?): AgentRuntimeStatus =
            byWire[value?.lowercase()] ?: STOPPED
    }
}

/** Describes a single CLI agent runtime advertised by a bridge. */
data class AgentRuntimeInfo(
    val runtimeId: String,
    val displayName: String,
    val status: AgentRuntimeStatus,
    val version: String? = null,
    val baseUrl: String? = null,
    val binaryPath: String? = null,
    val configPath: String? = null,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Optional capability flags declared by the bridge for this runtime. */
    val capabilities: Set<String> = emptySet()
)

/** A provider entry exposed by the runtime (e.g. opencode's /provider listing). */
data class AgentProviderEntry(
    val providerId: String,
    val displayName: String,
    val source: String? = null,
    val authenticated: Boolean = true,
    val defaultModelId: String? = null,
    val models: List<AgentModelEntry> = emptyList()
)

/** A model entry belonging to an [AgentProviderEntry]. */
data class AgentModelEntry(
    val modelId: String,
    val displayName: String,
    val providerId: String,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsImages: Boolean = false,
    val tagTitle: String? = null
)

/** Ref to a model (provider + model id). */
data class AgentModelRef(
    val providerId: String,
    val modelId: String
)

/** MCP server entry advertised by the runtime (opencode's /mcp listing). */
data class AgentMcpEntry(
    val name: String,
    val type: String,
    val enabled: Boolean,
    val connected: Boolean,
    val toolCount: Int = 0
)

/** Session summary returned for agent session listings. */
data class AgentSessionSummary(
    val sessionId: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val agent: String? = null,
    val modelId: String? = null,
    val providerId: String? = null
)

/** Mode label used when sending a prompt (maps to opencode's `agent` field). */
object AgentModes {
    const val BUILD = "build"
    const val PLAN = "plan"
}

/**
 * A single message part inside an agent prompt / event. Mirrors opencode's
 * streamable parts: text, file (url / base64), or image. Keep primitive-only so it
 * can round-trip through [BridgeEnvelope.payload].
 */
data class AgentMessagePart(
    val type: String,
    val text: String? = null,
    val url: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val dataBase64: String? = null
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_FILE = "file"
        const val TYPE_IMAGE = "image"
    }
}

/** Stable event kinds that the bridge forwards inside [BridgeMessageType.AGENT_EVENT]. */
object AgentEventKind {
    const val MESSAGE_PART_TEXT = "message.part.text"
    const val MESSAGE_PART_THOUGHT = "message.part.thought"
    const val MESSAGE_PART_TOOL = "message.part.tool"
    const val TOOL_CALL_UPDATE = "tool.call.update"
    const val PLAN_UPDATE = "plan.update"
    const val TODO_UPDATE = "todo.update"
    const val PERMISSION_ASKED = "permission.asked"
    const val PERMISSION_REPLIED = "permission.replied"
    const val QUESTION_ASKED = "question.asked"
    const val QUESTION_REPLIED = "question.replied"
    const val SESSION_STATUS = "session.status"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_ERROR = "session.error"
    const val SESSION_DIFF = "session.diff"
    const val MCP_CHANGED = "mcp.changed"
    const val MODEL_CHANGED = "model.changed"
    const val INSTALLATION_UPDATE = "installation.update"
}
