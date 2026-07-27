package com.amaya.intelligence.impl.local


import com.amaya.intelligence.data.remote.api.MessageRole


import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


internal fun LocalIntelligenceService.publishTurn(turn: LocalIntelligenceService.LocalTurn, status: String, detail: String, urgent: Boolean = false) {
        turn.lastStatus = status
        turn.lastDetail = detail
        val now = System.currentTimeMillis()
        if (!urgent && now - turn.lastNotificationAt < 900L && status in setOf("Streaming", "Thinking")) {
            if (currentConversationId == turn.conversationId) _uiState.value = turn.state
            return
        }
        turn.lastNotificationAt = now
        val activeTool = turn.state.messages.asReversed().asSequence()
            .flatMap { it.toolExecutions.asReversed().asSequence() }
            .firstOrNull { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING }
        val item = RunningSession(
            conversationId = turn.conversationId,
            title = turn.notificationTitle,
            mode = turn.state.assistantMode,
            ownerId = turn.state.ownerId,
            agentId = turn.state.agentId,
            status = status,
            detail = detail,
            updatedAt = System.currentTimeMillis(),
            isDelegating = turn.delegationActive,
            approvalId = activeTool?.metadata?.get("approvalId")?.takeIf { activeTool.metadata["approvalState"] == "pending" },
            approvalLabel = activeTool?.takeIf { it.metadata["approvalState"] == "pending" }
                ?.let { LocalToolMapper.displayLabel(it.name, it.arguments) },
            approvalRisk = activeTool?.metadata?.get("riskLevel"),
            phase = sessionPhase(status, turn.delegationActive),
            latestAssistantMessage = turn.state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content.orEmpty(),
            completedDelegates = turn.delegateCompleted,
            totalDelegates = turn.delegateTotal,
            activeDelegateName = turn.activeDelegateName,
            notificationTitle = turn.notificationTitle,
            notificationSender = turn.notificationSender,
            notificationThreadKey = turn.notificationThreadKey
        )
        if (item.phase in setOf(SessionPhase.COMPLETED, SessionPhase.FAILED, SessionPhase.STOPPED)) {
            _runningSessions.update { sessions -> sessions.filterNot { it.conversationId == turn.conversationId } }
            _completedSessions.tryEmit(item)
        } else {
            _runningSessions.update { sessions ->
                (sessions.filterNot { it.conversationId == turn.conversationId } + item).sortedByDescending(RunningSession::updatedAt)
            }
        }
        if (currentConversationId == turn.conversationId) {
            _uiState.value = turn.state
        }
    }

internal suspend fun LocalIntelligenceService.recoverInterruptedTurns() {
        conversationDao.getAllConversations().first().forEach { summary ->
            val entity = conversationDao.getConversationById(summary.id) ?: return@forEach
            val messages = parseMessagesFromJson(entity.messagesJson).getOrNull() ?: return@forEach
            val recovered = markInterruptedTurn(messages) ?: return@forEach
            // The two columns are recovered independently. Rebuilding the model context from the
            // visible transcript used to silently undo manual compaction and a cleared history.
            val storedContext = parseMessagesFromJson(entity.contextMessagesJson).getOrNull()
            val recoveredContext = storedContext?.let { markInterruptedTurn(it) ?: it } ?: recovered
            conversationDao.updateConversation(entity.copy(
                messagesJson = serializeMessagesToJson(recovered),
                contextMessagesJson = serializeMessagesToJson(recoveredContext),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

internal fun LocalIntelligenceService.sessionPhase(status: String, delegating: Boolean): SessionPhase = when {
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

internal data class NotificationIdentity(val threadKey: String, val title: String, val sender: String)

internal suspend fun LocalIntelligenceService.notificationIdentity(state: ChatUiState): NotificationIdentity = when (state.assistantMode) {
        AssistantMode.AGENT -> {
            val groupId = state.ownerId?.toLongOrNull()
            val agentName = state.agentId?.let { agentDao.getById(it)?.name }.orEmpty().ifBlank { "Agent" }
            val groupName = groupId?.let { agentDao.getGroupById(it)?.name }.orEmpty().ifBlank { "Agent group" }
            val convId = state.conversationId?.toLongOrNull()
            val sessionTitle = allLocalConversations.value.firstOrNull { it.id == convId }?.title
                ?: state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(48)
                ?: "Agent session"
            NotificationIdentity("agent-group:${groupId ?: state.conversationId.orEmpty()}", "$sessionTitle • $groupName", agentName)
        }
        AssistantMode.PROJECT -> {
            val projectId = state.ownerId?.toLongOrNull()
            val projectName = projectId?.let { projectDao.getById(it)?.name }.orEmpty().ifBlank { "Project" }
            val convId = state.conversationId?.toLongOrNull()
            val sessionTitle = allLocalConversations.value.firstOrNull { it.id == convId }?.title
                ?: state.messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(48)
                ?: "Project session"
            NotificationIdentity("project:${projectId ?: state.conversationId.orEmpty()}", "$sessionTitle • $projectName", "AI")
        }
        AssistantMode.CHAT -> NotificationIdentity("chat:${state.conversationId.orEmpty()}", sessionTitle(state), "AI")
    }

internal fun LocalIntelligenceService.sessionTitle(state: ChatUiState): String = when (state.assistantMode) {
        AssistantMode.CHAT -> state.conversationId?.toLongOrNull()?.let { id ->
            allLocalConversations.value.firstOrNull { it.id == id }?.title
        } ?: "Chat"
        AssistantMode.PROJECT -> state.ownerId?.toLongOrNull()?.let { id ->
            allLocalConversations.value.firstOrNull { it.ownerId == id.toString() && it.assistantMode == AssistantMode.PROJECT.name }?.title
        } ?: "Project"
        AssistantMode.AGENT -> state.agentId?.let { id ->
            allLocalConversations.value.firstOrNull { it.agentId == id }?.title
        } ?: "Agent"
    }

