package com.amaya.intelligence.impl.local.chat

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.RunningSession
import com.amaya.intelligence.domain.models.SessionPhase
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.impl.local.tools.LocalToolMapper

internal object LocalSessionProjection {
    fun phase(status: String, delegating: Boolean): SessionPhase = when {
        status == "Approval required" -> SessionPhase.WAITING_APPROVAL
        status == "Compacting" -> SessionPhase.COMPACTING
        status == "Completed" -> SessionPhase.COMPLETED
        status in setOf("Failed", "Incomplete") -> SessionPhase.FAILED
        status == "Stopped" -> SessionPhase.STOPPED
        delegating -> SessionPhase.DELEGATING
        status == "Thinking" -> SessionPhase.THINKING
        status.startsWith("Tools:") || status.startsWith("Tool ") -> SessionPhase.TOOL
        status == "Streaming" -> SessionPhase.STREAMING
        else -> SessionPhase.STARTING
    }

    fun runningSession(
        conversationId: Long,
        title: String,
        sender: String,
        threadKey: String,
        status: String,
        detail: String,
        state: com.amaya.intelligence.domain.models.ChatUiState,
        delegating: Boolean,
        completedDelegates: Int,
        totalDelegates: Int,
        activeDelegateName: String?
    ): RunningSession {
        val activeTool = state.messages.asReversed().asSequence()
            .flatMap { it.toolExecutions.asReversed().asSequence() }
            .firstOrNull(::active)
        return RunningSession(
            conversationId = conversationId,
            title = title,
            mode = state.assistantMode,
            ownerId = state.ownerId,
            agentId = state.agentId,
            status = status,
            detail = detail,
            updatedAt = System.currentTimeMillis(),
            isDelegating = delegating,
            approvalId = activeTool?.metadata?.get("approvalId")?.takeIf { activeTool.metadata["approvalState"] == "pending" },
            approvalLabel = activeTool?.takeIf { it.metadata["approvalState"] == "pending" }
                ?.let { LocalToolMapper.displayLabel(it.name, it.arguments) },
            approvalRisk = activeTool?.metadata?.get("riskLevel"),
            phase = phase(status, delegating),
            latestAssistantMessage = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty(),
            completedDelegates = completedDelegates,
            totalDelegates = totalDelegates,
            activeDelegateName = activeDelegateName,
            notificationTitle = title,
            notificationSender = sender,
            notificationThreadKey = threadKey
        )
    }

    private fun active(tool: ToolExecution): Boolean =
        tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING
}
