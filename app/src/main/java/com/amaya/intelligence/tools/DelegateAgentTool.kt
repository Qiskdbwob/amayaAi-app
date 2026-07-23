package com.amaya.intelligence.tools

import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.DelegationTaskDao
import com.amaya.intelligence.data.local.entity.DelegationTaskEntity
import com.amaya.intelligence.data.repository.AgentConversationRepository
import com.amaya.intelligence.impl.local.LocalIntelligenceService
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DelegateAgentTool @Inject constructor(
    private val agentDao: AgentDao,
    private val delegationTaskDao: DelegationTaskDao,
    private val agentConversationRepository: AgentConversationRepository,
    private val localIntelligenceService: Provider<LocalIntelligenceService>
) : Tool, ContextAwareTool {
    override val name = "delegate_agent"
    override val description = "Delegate a task to another member of the active agent group. The target runs with its own configured model, instructions, references, memory, and enabled tools. Use the group-local agent_id; do not use a name or database ID."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
        ToolResult.Error("Agent group context is required", ErrorType.PERMISSION_ERROR)

    override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val groupId = context.ownerId?.toLongOrNull()
            ?: return ToolResult.Error("Active agent group is required", ErrorType.PERMISSION_ERROR)
        val targetLocalId = when (val raw = arguments["agent_id"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: return ToolResult.Error("agent_id and task are required", ErrorType.VALIDATION_ERROR)
        val title = (arguments["title"] as? String)?.trim().orEmpty()
        val request = (arguments["task"] as? String)?.trim().orEmpty()
        if (title.isBlank() || request.isBlank()) return ToolResult.Error("title, agent_id, and task are required", ErrorType.VALIDATION_ERROR)
        val members = agentDao.getByGroup(groupId)
        val source = context.agentId?.let { id -> members.firstOrNull { it.id == id } }
            ?: return ToolResult.Error("Active source agent is required", ErrorType.PERMISSION_ERROR)
        if (targetLocalId == source.localId) {
            return ToolResult.Error("An agent cannot delegate to itself", ErrorType.VALIDATION_ERROR)
        }
        val agent = members.firstOrNull { it.localId == targetLocalId }
            ?: return ToolResult.Error(
                "Agent ID '$targetLocalId' is not in the active group. Available members: ${members.filter { it.id != source.id }.joinToString { "${it.name} (agent_id=${it.localId})" }}",
                ErrorType.PERMISSION_ERROR
            )
        val group = agentDao.getGroupById(groupId)
            ?: return ToolResult.Error("Active agent group no longer exists", ErrorType.PERMISSION_ERROR)
        val targetContext = runCatching {
            agentConversationRepository.appendDelegationRequest(groupId, source, agent, group.workspacePath, request)
        }.getOrElse {
            return ToolResult.Error("Could not append delegation to ${agent.name}: ${it.message}", ErrorType.EXECUTION_ERROR)
        }
        val taskId = delegationTaskDao.insert(DelegationTaskEntity(groupId = groupId, agentId = agent.id, request = request, status = "RUNNING"))
        val parentId = context.toolCallId ?: return ToolResult.Error("Delegation call ID is required", ErrorType.VALIDATION_ERROR)
        context.onEvent?.invoke(com.amaya.intelligence.data.repository.AgentEvent.SubagentUpdate(
            parentToolCallId = parentId,
            index = 0,
            taskName = agent.name,
            prompt = request,
            result = null,
            isComplete = false,
            isError = false
        ))
        val result = runCatching {
            localIntelligenceService.get().runDelegatedAgentTurn(targetContext.conversationId, targetContext.incomingMessage)
        }
        return result.fold(
            onSuccess = {
                val failed = it.summary.startsWith("[ERROR]") || it.summary.startsWith("[RATE LIMITED]") || it.summary.startsWith("[INCOMPLETE]")
                delegationTaskDao.complete(taskId, if (failed) "FAILED" else "COMPLETED", it.summary)
                context.onEvent?.invoke(com.amaya.intelligence.data.repository.AgentEvent.SubagentUpdate(
                    parentToolCallId = parentId,
                    index = 0,
                    taskName = agent.name,
                    prompt = request,
                    result = it.summary,
                    isComplete = true,
                    isError = failed
                ))
                if (failed) ToolResult.Error(it.summary, ErrorType.EXECUTION_ERROR) else ToolResult.Success(it.summary)
            },
            onFailure = {
                val message = "Delegation failed: ${it.message}"
                delegationTaskDao.complete(taskId, "FAILED", message)
                context.onEvent?.invoke(com.amaya.intelligence.data.repository.AgentEvent.SubagentUpdate(
                    parentToolCallId = parentId,
                    index = 0,
                    taskName = agent.name,
                    prompt = request,
                    result = message,
                    isComplete = true,
                    isError = true
                ))
                ToolResult.Error(message, ErrorType.EXECUTION_ERROR)
            }
        )
    }
}
