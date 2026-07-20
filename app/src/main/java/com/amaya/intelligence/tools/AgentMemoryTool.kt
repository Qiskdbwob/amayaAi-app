package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.AgentMemoryRepository
import com.amaya.intelligence.domain.models.AssistantMode
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentMemoryTool @Inject constructor(
    private val repository: AgentMemoryRepository
) : Tool, ContextAwareTool {
    override val name = "agent_memory_internal"
    override val description = "Manage memory private to the active agent."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
        ToolResult.Error("Agent memory requires an active agent.", ErrorType.PERMISSION_ERROR)

    override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        if (context.assistantMode != AssistantMode.AGENT || context.agentId == null) {
            return ToolResult.Error("Agent memory requires an active agent.", ErrorType.PERMISSION_ERROR)
        }
        val agentId = context.agentId
        return when (val operation = arguments["operation"] as? String) {
            "list", "search" -> {
                val records = repository.list(agentId, arguments["query"] as? String, (arguments["limit"] as? Number)?.toInt() ?: 20)
                ToolResult.Success(JSONObject().put("results", JSONArray(records.map { record ->
                    JSONObject().put("id", record.id).put("title", record.title).put("content", record.content).put("version", record.version)
                })).toString())
            }
            "save" -> repository.save(agentId, arguments["title"] as? String, arguments["content"] as? String ?: "").fold(
                { ToolResult.Success(JSONObject().put("id", it.id).put("title", it.title).put("content", it.content).put("version", it.version).toString()) },
                { ToolResult.Error(it.message.orEmpty(), ErrorType.VALIDATION_ERROR) }
            )
            "update" -> repository.update(
                agentId,
                arguments["id"] as? String ?: return ToolResult.Error("Missing required: id", ErrorType.VALIDATION_ERROR),
                arguments["content"] as? String ?: return ToolResult.Error("Missing required: content", ErrorType.VALIDATION_ERROR),
                (arguments["expected_version"] as? Number)?.toInt() ?: return ToolResult.Error("Missing required: expected_version", ErrorType.VALIDATION_ERROR)
            ).fold(
                { ToolResult.Success(JSONObject().put("id", it.id).put("content", it.content).put("version", it.version).toString()) },
                { ToolResult.Error(it.message.orEmpty(), ErrorType.VALIDATION_ERROR) }
            )
            else -> ToolResult.Error("Unsupported agent memory operation: $operation", ErrorType.VALIDATION_ERROR)
        }
    }
}
