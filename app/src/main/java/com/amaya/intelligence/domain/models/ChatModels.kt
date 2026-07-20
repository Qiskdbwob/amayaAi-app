package com.amaya.intelligence.domain.models


import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.data.remote.api.ThinkingEffort
import com.amaya.intelligence.tools.TodoItem
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

// ── UI State ─────────────────────────────────────────────────────────────────

enum class SessionPhase { STARTING, THINKING, STREAMING, TOOL, DELEGATING, WAITING_APPROVAL, COMPLETED, FAILED, STOPPED }

data class RunningSession(
    val conversationId: Long,
    val title: String,
    val mode: AssistantMode,
    val ownerId: String?,
    val agentId: Long?,
    val status: String,
    val detail: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isDelegating: Boolean = false,
    val approvalId: String? = null,
    val approvalLabel: String? = null,
    val approvalRisk: String? = null,
    val phase: SessionPhase = SessionPhase.STARTING,
    val latestAssistantMessage: String = "",
    val completedDelegates: Int = 0,
    val totalDelegates: Int = 0,
    val activeDelegateName: String? = null,
    val notificationTitle: String = title,
    val notificationSender: String = "AI",
    val notificationThreadKey: String = "chat:$conversationId"
)

data class ChatUiState(
    val messages:         List<UiMessage> = emptyList(),
    val isLoading:        Boolean         = false,
    val isLoadingHistory: Boolean         = false,
    val isStreaming:      Boolean         = false,
    val error:            String?         = null,
    val selectedModel:    String          = "",
    val activeProjectId:  Long?           = null,
    val workspacePath:    String?         = null,
    val assistantMode:    AssistantMode   = AssistantMode.CHAT,
    val ownerId:          String?         = null,
    val agentId:          Long?           = null,
    val totalInputTokens:  Int            = 0,
    val totalOutputTokens: Int            = 0,
    val isLoadingConversations: Boolean   = false,
    val modelOptions:  List<ModelOption> = emptyList(),
    val activeModelKey: String            = "",
    val conversationId: String?           = null,
    val conversationMode: ConversationMode = ConversationMode.PLANNING,
    val conversationModeId: String?        = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionMode: com.amaya.intelligence.domain.ai.IntelligenceSessionManager.SessionMode = com.amaya.intelligence.domain.ai.IntelligenceSessionManager.SessionMode.LOCAL,
    val serverIp: String? = null,
    /** Global reasoning effort from the chat bulb. */
    val effort: ThinkingEffort = ThinkingEffort.MEDIUM
)



data class ComposerReferences(
    val agentIds: List<Long> = emptyList(),
    val workspacePaths: List<String> = emptyList(),
    val commands: List<String> = emptyList()
)

/** Agent reference ID is group-local; database IDs never enter model-owned Markdown. */
fun agentMentionMarkdown(agentId: Long, name: String): String =
    "[@${name.replace("[", "").replace("]", "")}](agent:$agentId)"

fun workspaceMentionMarkdown(path: String): String {
    val normalized = path.replace('\\', '/')
    val label = normalized.substringAfterLast('/').ifBlank { normalized }
    return "[@${label.replace("[", "").replace("]", "")}](workspace:${encodeComposerReference(normalized)})"
}

fun commandMarkdown(command: String): String {
    val normalized = command.trim().removePrefix("/")
    return "[/$normalized](command:$normalized)"
}

fun parseComposerReferences(text: String): ComposerReferences = ComposerReferences(
    agentIds = AGENT_REFERENCE.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList(),
    workspacePaths = WORKSPACE_REFERENCE.findAll(text).mapNotNull { decodeComposerReference(it.groupValues[1]) }.distinct().toList(),
    commands = COMMAND_REFERENCE.findAll(text).map { it.groupValues[1] }.distinct().toList()
)

private fun encodeComposerReference(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20").replace("%2F", "/")

private fun decodeComposerReference(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()?.takeIf(String::isNotBlank)

private val AGENT_REFERENCE = Regex("""\]\(agent:(\d+)\)""")
private val WORKSPACE_REFERENCE = Regex("""\]\(workspace:([^)]+)\)""")
private val COMMAND_REFERENCE = Regex("""\]\(command:([a-zA-Z0-9_-]+)\)""")

enum class ConversationMode(val wireValue: String) {
    PLANNING("planning"),
    FAST("fast");

    companion object {
        fun fromWireValue(value: String?): ConversationMode {
            return if (value.equals("fast", ignoreCase = true)) FAST else PLANNING
        }
    }
}

// ── Message Timeline ───────────────────────────────────────────────────────────

sealed class MessageStep {
    abstract val id: String

    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        val content: String,
        val formattedContent: String? = null
    ): MessageStep()

    data class ToolCall(
        override val id: String = UUID.randomUUID().toString(),
        val execution: ToolExecution
    ): MessageStep()
}

// ── Message ──────────────────────────────────────────────────────────────────

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val formattedContent: String? = null, // Pre-formatted (e.g., Markdown or code blocks)
    val thinking: String? = null,
    val isThinking: Boolean = false,
    val thinkingStartedAt: Long? = null,
    /** Thinking duration in ms, captured when the turn ends so the
     *  "Thought for Xs" label survives reloads without recomputing from
     *  startedAt/completedAt (which may be absent for remote/inline tags). */
    val thinkingDurationMs: Long? = null,
    val intent: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val toolExecutions: List<ToolExecution> = emptyList(),
    val steps: List<MessageStep> = emptyList(),
    val todoItems: List<TodoItem> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val attachments: List<MessageAttachment> = emptyList(),
    val responseItems: List<String> = emptyList(),
    /** Ordered provider/tool history. UI fields remain a projection of these items. */
    val canonicalHistory: List<String> = emptyList()
)

data class MessageAttachment(
    val mimeType: String,
    val dataBase64: String,
    val fileName: String = ""
)

// ── Tool Execution ───────────────────────────────────────────────────────────

data class ToolExecution(
    val toolCallId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val result: String? = null,
    val status: ToolStatus = ToolStatus.PENDING,
    val children: List<SubagentExecution> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val uiMetadata: ToolUiMetadata? = null // Metadata for "Dumb UI" rendering
)

data class SubagentExecution(
    val index: Int,
    val taskName: String,
    val prompt: String,
    val result: String? = null,
    val status: ToolStatus = ToolStatus.PENDING
)

enum class ToolStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR
}

data class ModelOption(
    val id: String,
    val name: String,
    val modelId: String,
    val connectionId: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val tagTitle: String? = null,
    val quotaLabel: String? = null,
    val resetTime: String? = null,
    val isRemote: Boolean = false,
    val iconType: String = "default",
    val supportsImages: Boolean = false
)



// ── Workspace & Projects ──────────────────────────────────────────────────

data class ProjectFileEntry(
    val name: String,
    val path: String,
    val type: String, // "file" or "directory"
    val size: Long
)

data class RemoteWorkspace(
    val name: String,
    val path: String,
    val isCurrent: Boolean
)
