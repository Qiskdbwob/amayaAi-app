package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.MemoryRepository
import com.amaya.intelligence.domain.memory.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured memory administration for model and explicit user requests. This
 * complements update_memory: update_memory proposes content; memory_manage can
 * inspect, remove, or update existing memory by stable id.
 */
@Singleton
class MemoryManageTool @Inject constructor(
    private val memoryRepository: MemoryRepository
) : Tool, ContextAwareTool {
    override val name = "memory_manage"
    override val description = "List, search, remove, or update saved memory by id. Use when the user asks what Amaya remembers, asks to remove/update a specific saved memory, or needs precise memory cleanup. For list/search, include title: a concise 3-5 word header explaining why memory is being opened, e.g. 'Review saved preferences' or 'Find memory to remove'. Never use for secrets."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
        execute(arguments, ToolExecutionContext())

    override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        val action = (arguments["action"] as? String)?.lowercase()
            ?: return@withContext ToolResult.Error("Missing required: action", ErrorType.VALIDATION_ERROR)
        when (action) {
            "list", "search" -> listOrSearch(arguments)
            "remove", "delete" -> remove(arguments, context.confirmed)
            "update", "replace" -> update(arguments, context.confirmed)
            else -> ToolResult.Error("Unsupported action: $action", ErrorType.VALIDATION_ERROR)
        }
    }

    private suspend fun listOrSearch(arguments: Map<String, Any?>): ToolResult {
        val query = arguments["query"] as? String
        val type = parseType(arguments["type"] as? String)
        val limit = ((arguments["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val records = memoryRepository.listMemoryRecords(type = type, query = query, limit = limit)
        return ToolResult.Success(
            output = JSONObject()
                .put("results", JSONArray(records.map { record ->
                    memoryJson(record.id, record.content, record.type)
                }))
                .toString(),
            metadata = mapOf("count" to records.size)
        )
    }

    private suspend fun remove(arguments: Map<String, Any?>, confirmed: Boolean): ToolResult {
        val id = arguments["id"] as? String
            ?: return ToolResult.Error("Missing required: id", ErrorType.VALIDATION_ERROR)
        val record = memoryRepository.listMemoryRecords(limit = 100).firstOrNull { it.id == id }
        if (!confirmed) {
            val preview = record?.content.orEmpty()
            return ToolResult.RequiresConfirmation(
                reason = "Remove saved memory '$id'?",
                details = preview.ifBlank { "This permanently removes the memory from active recall." }
            )
        }
        return memoryRepository.removeMemoryById(id).fold(
            onSuccess = { ToolResult.Success(memoryJson(id, record?.content.orEmpty(), record?.type).toString()) },
            onFailure = { ToolResult.Error("Remove memory failed: ${it.message}", ErrorType.EXECUTION_ERROR) }
        )
    }

    private suspend fun update(arguments: Map<String, Any?>, confirmed: Boolean): ToolResult {
        val id = arguments["id"] as? String
            ?: return ToolResult.Error("Missing required: id", ErrorType.VALIDATION_ERROR)
        val content = arguments["content"] as? String
            ?: return ToolResult.Error("Missing required: content", ErrorType.VALIDATION_ERROR)
        val record = memoryRepository.listMemoryRecords(limit = 100).firstOrNull { it.id == id }
        if (!confirmed) {
            val preview = record?.content.orEmpty()
            return ToolResult.RequiresConfirmation(
                reason = "Update saved memory '$id'?",
                details = buildString {
                    if (preview.isNotBlank()) appendLine("Current: $preview")
                    append("New: ${content.take(500)}")
                }
            )
        }
        return memoryRepository.updateMemoryById(id, content).fold(
            onSuccess = { ToolResult.Success(memoryJson(id, content.trim(), record?.type ?: parseType(arguments["type"] as? String)).toString()) },
            onFailure = { ToolResult.Error("Update memory failed: ${it.message}", ErrorType.EXECUTION_ERROR) }
        )
    }

    private fun memoryJson(id: String, content: String, type: MemoryType?): JSONObject = JSONObject()
        .put("id", id)
        .put("content", content)
        .put("type", type?.name?.lowercase().orEmpty())

    private fun parseType(raw: String?): MemoryType? = when (raw?.lowercase()) {
        "user_profile", "user" -> MemoryType.USER_PROFILE
        "long_term_memory", "long", "memory", "important" -> MemoryType.LONG_TERM_MEMORY
        "daily_log", "daily" -> MemoryType.DAILY_LOG
        "workspace_fact", "workspace", "project" -> MemoryType.WORKSPACE_FACT
        else -> null
    }
}
