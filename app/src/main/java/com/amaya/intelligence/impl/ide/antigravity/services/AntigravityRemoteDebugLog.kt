package com.amaya.intelligence.impl.ide.antigravity.services

import android.util.Log
import com.amaya.intelligence.domain.models.ChatUiState
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteChatMessage
import com.amaya.intelligence.impl.ide.antigravity.client.RemoteEvent

/** Verbose remote-session diagnostics for Antigravity reconnect/stream stress tests. */
object AntigravityRemoteDebugLog {
    private const val TAG = "AGRemoteTrace"

    fun rawInbound(type: String, seqId: Int, conversationId: String?, serverSessionId: String?, payloadLength: Int) {
        Log.i(TAG, "WS_IN type=$type seq=$seqId cid=${conversationId.orDash()} serverSession=${serverSessionId.orDash()} bytes=$payloadLength")
    }

    fun rawOutbound(action: String, connected: Boolean, queued: Boolean, payloadLength: Int) {
        Log.i(TAG, "WS_OUT action=$action connected=$connected queued=$queued bytes=$payloadLength")
    }

    fun queue(action: String?, size: Int) {
        Log.w(TAG, "WS_QUEUE action=${action.orDash()} size=$size")
    }

    fun connection(message: String) {
        Log.w(TAG, "CONNECTION $message")
    }

    fun eventBefore(event: RemoteEvent, state: ChatUiState) {
        Log.i(TAG, "EVENT_BEFORE ${eventSummary(event)} state=${stateSummary(state)}")
    }

    fun eventAfter(event: RemoteEvent, state: ChatUiState) {
        Log.i(TAG, "EVENT_AFTER ${eventSummary(event)} state=${stateSummary(state)}")
    }

    fun handlerDrop(handler: String, eventConversationId: String?, currentConversationId: String?) {
        Log.w(TAG, "DROP handler=$handler eventCid=${eventConversationId.orDash()} currentCid=${currentConversationId.orDash()}")
    }

    fun handlerNote(handler: String, note: String) {
        Log.i(TAG, "$handler $note")
    }

    fun eventSummary(event: RemoteEvent): String {
        return when (event) {
            is RemoteEvent.StateSync -> "StateSync seq=${event.seqId} cid=${event.conversationId.orDash()} loading=${event.isLoading} streaming=${event.isStreaming} msgs=${event.messages.size} ${remoteMessagesSummary(event.messages)}"
            is RemoteEvent.ConversationLoaded -> "ConversationLoaded seq=${event.seqId} cid=${event.conversationId} msgs=${event.messages.size} mode=${event.conversationMode.orDash()} ${remoteMessagesSummary(event.messages)}"
            is RemoteEvent.StateUpdate -> "StateUpdate seq=${event.seqId} cid=${event.conversationId.orDash()} loading=${event.isLoading} streaming=${event.isStreaming}"
            is RemoteEvent.TextDelta -> "TextDelta seq=${event.seqId} cid=${event.conversationId.orDash()} step=${event.stepIndex.orDash()} len=${event.text.length} hash=${event.text.hashCode()} tail=${event.text.tail()}"
            is RemoteEvent.AiThinking -> "AiThinking seq=${event.seqId} cid=${event.conversationId.orDash()} step=${event.stepIndex} running=${event.isRunning} len=${event.text.length} hash=${event.text.hashCode()} tail=${event.text.tail()}"
            is RemoteEvent.ToolCallStart -> "ToolStart seq=${event.seqId} cid=${event.conversationId.orDash()} id=${event.toolCallId} name=${event.name} status=${event.status} args=${event.arguments.keys.sorted()} meta=${event.metadata}"
            is RemoteEvent.ToolCallResult -> "ToolResult seq=${event.seqId} cid=${event.conversationId.orDash()} id=${event.toolCallId} name=${event.name.orDash()} error=${event.isError} len=${event.result.length} hash=${event.result.hashCode()} tail=${event.result.tail()}"
            is RemoteEvent.ToolActivity -> "ToolActivity seq=${event.seqId} cid=${event.conversationId.orDash()} type=${event.type} file=${event.file} len=${event.terminalData.length} tail=${event.terminalData.tail()}"
            is RemoteEvent.StreamDone -> "StreamDone seq=${event.seqId} cid=${event.conversationId.orDash()} reason=${event.stopReason.orDash()}"
            is RemoteEvent.UserMessage -> "UserMessage seq=${event.seqId} cid=${event.conversationId.orDash()} len=${event.content.length} attachments=${event.attachments.size} tail=${event.content.tail()}"
            is RemoteEvent.NewAssistantMessage -> "NewAssistantMessage seq=${event.seqId} cid=${event.conversationId.orDash()}"
            is RemoteEvent.ActiveConversation -> "ActiveConversation seq=${event.seqId} cid=${event.conversationId} serverIp=${event.serverIp.orDash()}"
            is RemoteEvent.NewConversation -> "NewConversation seq=${event.seqId} cid=${event.conversationId.orDash()}"
            is RemoteEvent.StatusChange -> "StatusChange seq=${event.seqId} cid=${event.conversationId.orDash()} status=${event.status}"
            is RemoteEvent.TitleGenerated -> "TitleGenerated seq=${event.seqId} cid=${event.conversationId.orDash()} title=${event.title.tail(80)}"
            is RemoteEvent.Error -> "Error seq=${event.seqId} cid=${event.conversationId.orDash()} msg=${event.message.tail(160)}"
            is RemoteEvent.ConversationsList -> "ConversationsList seq=${event.seqId} count=${event.conversations.size} currentWs=${event.currentWorkspacePath.orDash()} latest=${event.conversations.firstOrNull()?.id.orDash()}"
            is RemoteEvent.ModelsList -> "ModelsList seq=${event.seqId} count=${event.models.size} selected=${event.selectedModelId}"
            is RemoteEvent.WorkspacesList -> "WorkspacesList seq=${event.seqId} count=${event.workspaces.size}"
            is RemoteEvent.ProjectFiles -> "ProjectFiles seq=${event.seqId} cid=${event.conversationId.orDash()} path=${event.path} count=${event.files.size}"
            is RemoteEvent.ModelSelected -> "ModelSelected seq=${event.seqId} cid=${event.conversationId.orDash()} model=${event.modelId}"
            is RemoteEvent.CurrentWorkspace -> "CurrentWorkspace seq=${event.seqId} cid=${event.conversationId.orDash()} path=${event.path}"
            is RemoteEvent.FileDiff -> "FileDiff seq=${event.seqId} cid=${event.conversationId.orDash()} len=${event.diff.length} error=${event.error.orDash()}"
            is RemoteEvent.FileContent -> "FileContent seq=${event.seqId} cid=${event.conversationId.orDash()} path=${event.path} len=${event.content.length} error=${event.error.orDash()}"
            is RemoteEvent.ExternalActivity -> "ExternalActivity seq=${event.seqId} cid=${event.conversationId}"
            is RemoteEvent.StreamProgress -> "StreamProgress seq=${event.seqId} cid=${event.conversationId} delta=${event.sizeDelta} total=${event.totalGrowth}"
            is RemoteEvent.ConfirmationRequired -> "ConfirmationRequired seq=${event.seqId} cid=${event.conversationId.orDash()} title=${event.title} risk=${event.riskLevel}"
            is RemoteEvent.DebugLog -> "DebugLog seq=${event.seqId} cid=${event.conversationId.orDash()} msg=${event.message.tail(120)}"
        }
    }

    fun stateSummary(state: ChatUiState): String {
        val last = state.messages.lastOrNull()
        val lastTools = last?.toolExecutions.orEmpty()
        val running = state.messages.sumOf { msg -> msg.toolExecutions.count { it.status == ToolStatus.RUNNING } }
        val pending = state.messages.sumOf { msg -> msg.toolExecutions.count { it.status == ToolStatus.PENDING } }
        val success = state.messages.sumOf { msg -> msg.toolExecutions.count { it.status == ToolStatus.SUCCESS } }
        val error = state.messages.sumOf { msg -> msg.toolExecutions.count { it.status == ToolStatus.ERROR } }
        return "cid=${state.conversationId.orDash()} loading=${state.isLoading} streaming=${state.isStreaming} conn=${state.connectionState} msgs=${state.messages.size} tools(R/P/S/E)=$running/$pending/$success/$error last=${last?.role}:${last?.content?.length ?: 0} steps=${last?.steps?.size ?: 0} tools=${lastTools.size} completed=${last?.metadata?.get("completedAt").orDash()} lastTail=${last?.content.orEmpty().tail()}"
    }

    private fun remoteMessagesSummary(messages: List<RemoteChatMessage>): String {
        val last = messages.lastOrNull()
        val toolCount = messages.sumOf { it.toolExecutions.size }
        val lastTools = last?.toolExecutions.orEmpty().takeLast(4).joinToString { "${it.name}:${it.status}:${it.toolCallId}" }
        return "remoteTools=$toolCount last=${last?.role.orDash()}:${last?.content?.length ?: 0} lastTools=[$lastTools]"
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"

    private fun String.tail(max: Int = 96): String {
        val cleaned = replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length <= max) cleaned else "…" + cleaned.takeLast(max)
    }
}
