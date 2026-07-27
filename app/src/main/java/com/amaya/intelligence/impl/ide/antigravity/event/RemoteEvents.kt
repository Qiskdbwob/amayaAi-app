package com.amaya.intelligence.impl.ide.antigravity.event

import com.amaya.intelligence.domain.models.MessageAttachment

sealed class RemoteEvent {
    abstract val seqId: Int
    abstract val conversationId: String?
    open val serverIp: String? = null

    data class StateSync(
        val messages: List<RemoteChatMessage>,
        val isLoading: Boolean,
        val isStreaming: Boolean,
        val currentModel: String,
        val toolExecutions: List<RemoteToolExecution>,
        val conversationMode: String? = null,
        val appName: String = "Antigravity",
        val appVersion: String = "",
        val currentWorkspace: RemoteWorkspace? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class StateUpdate(
        val isLoading: Boolean,
        val isStreaming: Boolean,
        override val seqId: Int = 0,
        override val conversationId: String? = null,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class TextDelta(
        val text: String,
        val stepIndex: String? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class StreamProgress(
        override val conversationId: String,
        val sizeDelta: Int,
        val totalGrowth: Int,
        override val seqId: Int = 0
    ) : RemoteEvent()

    data class ToolCallStart(
        val toolCallId: String,
        val name: String,
        val arguments: Map<String, Any?>,
        val metadata: Map<String, String> = emptyMap(),
        val status: String = "RUNNING",
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ToolCallResult(
        val toolCallId: String,
        val name: String? = null,
        val result: String,
        val isError: Boolean,
        override val seqId: Int = 0,
        override val conversationId: String? = null,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class ToolActivity(
        val type: String,
        val file: String = "",
        val terminalData: String = "",
        override val seqId: Int = 0,
        override val conversationId: String? = null,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class StreamDone(
        val stopReason: String? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class Error(
        val message: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ConfirmationRequired(
        val title: String,
        val description: String,
        val riskLevel: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class NewAssistantMessage(
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()
    data class NewConversation(
        override val seqId: Int = 0,
        override val conversationId: String? = null,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class ExternalActivity(
        override val conversationId: String,
        override val seqId: Int = 0
    ) : RemoteEvent()

    data class UserMessage(
        val content: String,
        val attachments: List<MessageAttachment> = emptyList(),
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class AiThinking(
        val text: String,
        val stepIndex: String = "",
        val isRunning: Boolean = true,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class TitleGenerated(
        val title: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class StatusChange(
        val status: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ConversationsList(
        val conversations: List<RemoteConversationMeta>,
        val currentWorkspacePath: String? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ModelsList(
        val models: List<RemoteModelInfo>,
        val selectedModelId: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ConversationLoaded(
        override val conversationId: String,
        val messages: List<RemoteChatMessage>,
        val conversationMode: String? = null,
        override val seqId: Int = 0,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class DebugLog(
        val message: String,
        val timestamp: Long,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ModelSelected(
        val modelId: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class WorkspacesList(
        val workspaces: List<RemoteWorkspace>,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ProjectFiles(
        val files: List<RemoteFileEntry>,
        val path: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class FileDiff(
        val diff: String,
        val error: String? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class FileContent(
        val path: String,
        val content: String,
        val error: String? = null,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()

    data class ActiveConversation(
        override val conversationId: String,
        override val seqId: Int = 0,
        override val serverIp: String? = null
    ) : RemoteEvent()

    data class CurrentWorkspace(
        val name: String,
        val path: String,
        override val seqId: Int = 0,
        override val conversationId: String? = null
    ) : RemoteEvent()
}

data class RemoteChatMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
    val intent: String? = null,
    val toolExecutions: List<RemoteToolExecution> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val attachments: List<MessageAttachment> = emptyList()
)

data class RemoteToolExecution(
    val toolCallId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val result: String? = null,
    val status: String = "PENDING",
    val metadata: Map<String, String> = emptyMap()
)

data class RemoteAttachment(
    val mimeType: String,
    val dataBase64: String,
    val fileName: String = ""
)

data class RemoteConversationMeta(
    val id: String,
    val lastModified: Long,
    val size: Long,
    val title: String = "",
    val preview: String = "",
    val workspacePath: String = ""
)

data class RemoteModelInfo(
    val id: String,
    val label: String,
    val isRecommended: Boolean,
    val quota: Double,
    val quotaLabel: String? = null,
    val resetTime: String? = null,
    val tagTitle: String? = null,
    val supportsImages: Boolean
)

data class RemoteWorkspace(
    val name: String,
    val path: String,
    val isCurrent: Boolean = false
)

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val type: String, // "file" or "directory"
    val size: Long = 0
)
