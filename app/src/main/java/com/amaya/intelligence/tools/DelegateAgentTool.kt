package com.amaya.intelligence.tools

import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.DelegationTaskDao
import com.amaya.intelligence.data.local.entity.DelegationTaskEntity
import com.amaya.intelligence.data.repository.AgentConversationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DelegateAgentTool @Inject constructor(
    private val agentDao: AgentDao,
    private val delegationTaskDao: DelegationTaskDao,
    private val agentConversationRepository: AgentConversationRepository,
    private val subagentRunner: SubagentRunner
) : Tool, ContextAwareTool {
    override val name = "delegate_agent"
    override val description = "Delegate one focused read-only task to a member of the active agent group by stable ID."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
        ToolResult.Error("Agent group context is required", ErrorType.PERMISSION_ERROR)

    override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val groupId = context.ownerId?.toLongOrNull()
            ?: return ToolResult.Error("Active agent group is required", ErrorType.PERMISSION_ERROR)
        val targetId = when (val raw = arguments["agent_id"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: return ToolResult.Error("agent_id and task are required", ErrorType.VALIDATION_ERROR)
        val title = (arguments["title"] as? String)?.trim().orEmpty()
        val request = (arguments["task"] as? String)?.trim().orEmpty()
        if (title.isBlank() || request.isBlank()) return ToolResult.Error("title, agent_id, and task are required", ErrorType.VALIDATION_ERROR)
        if (targetId == context.agentId) {
            return ToolResult.Error("An agent cannot delegate to itself", ErrorType.VALIDATION_ERROR)
        }
        val members = agentDao.getByGroup(groupId)
        val agent = members.firstOrNull { it.id == targetId }
            ?: return ToolResult.Error(
                "Agent ID '$targetId' is not in the active group. Available members: ${members.filter { it.id != context.agentId }.joinToString { "${it.name} (${it.id})" }}",
                ErrorType.PERMISSION_ERROR
            )
        val source = context.agentId?.let { id -> members.firstOrNull { it.id == id } }
            ?: return ToolResult.Error("Active source agent is required", ErrorType.PERMISSION_ERROR)
        val group = agentDao.getGroupById(groupId)
            ?: return ToolResult.Error("Active agent group no longer exists", ErrorType.PERMISSION_ERROR)
        val targetContext = runCatching {
            agentConversationRepository.appendDelegationRequest(groupId, source, agent, group.workspacePath, request)
        }.getOrElse {
            return ToolResult.Error("Could not append delegation to ${agent.name}: ${it.message}", ErrorType.EXECUTION_ERROR)
        }
        val taskId = delegationTaskDao.insert(DelegationTaskEntity(groupId = groupId, agentId = agent.id, request = request, status = "RUNNING"))
        val result = runCatching {
            subagentRunner.run(
                SubagentTask(
                    index = 0,
                    taskName = agent.name,
                    task = targetContext.incomingMessage,
                    workspacePath = group.workspacePath,
                    providerConnection = context.providerConnection,
                    selectedModelId = context.selectedModelId,
                    conversationHistory = targetContext.history,
                    systemInstructions = buildString {
                        append("You are ${agent.name}.")
                        agent.role.takeIf(String::isNotBlank)?.let { append("\nRole: $it") }
                        agent.instructions.takeIf(String::isNotBlank)?.let { append("\nInstructions: $it") }
                        group.instructions.takeIf(String::isNotBlank)?.let { append("\nGroup instructions: $it") }
                        append("\nContinue this agent's existing conversation. Answer the incoming delegation directly.")
                    }
                )
            )
        }
        return result.fold(
            onSuccess = {
                val failed = it.summary.startsWith("[ERROR]") || it.summary.startsWith("[RATE LIMITED]") || it.summary.startsWith("[INCOMPLETE]")
                agentConversationRepository.appendDelegationResponse(targetContext.conversationId, source, it, failed)
                delegationTaskDao.complete(taskId, if (failed) "FAILED" else "COMPLETED", it.summary)
                if (failed) ToolResult.Error(it.summary, ErrorType.EXECUTION_ERROR) else ToolResult.Success(it.summary)
            },
            onFailure = {
                val message = "Delegation failed: ${it.message}"
                agentConversationRepository.appendDelegationResponse(
                    targetContext.conversationId,
                    source,
                    SubagentResult(agent.name, message),
                    true
                )
                delegationTaskDao.complete(taskId, "FAILED", message)
                ToolResult.Error(message, ErrorType.EXECUTION_ERROR)
            }
        )
    }
}
