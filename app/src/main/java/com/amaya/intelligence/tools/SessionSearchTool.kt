package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.BrainSettingsRepository
import com.amaya.intelligence.data.repository.SessionMemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionSearchTool @Inject constructor(
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val brainSettingsRepository: BrainSettingsRepository
) : Tool, ContextAwareTool {
    override val name = "session_search"
    override val description = "Search previous sessions by query. Use for recall instead of injecting old sessions into the system prompt."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult =
        execute(arguments, ToolExecutionContext())

    override suspend fun execute(arguments: Map<String, Any?>, context: ToolExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        val query = arguments["query"] as? String
            ?: return@withContext ToolResult.Error("Missing required: query", ErrorType.VALIDATION_ERROR)
        val settings = brainSettingsRepository.getBrainSettings()
        if (!settings.context.pastChatRecallEnabled) {
            return@withContext ToolResult.Error("Previous chat recall is disabled in Context & Recall settings.", ErrorType.PERMISSION_ERROR)
        }
        val requestedLimit = (arguments["limit"] as? Number)?.toInt() ?: settings.context.maxRecallItems
        val limit = requestedLimit.coerceIn(1, settings.context.maxRecallItems.coerceIn(1, 20))
        val results = sessionMemoryRepository.searchSessions(query, limit, context.workspacePath)
        ToolResult.Success(
            output = JSONObject()
                .put("results", JSONArray(results.map { result ->
                    JSONObject()
                        .put("sessionId", result.sessionId)
                        .put("score", result.score)
                        .put("timestamp", Instant.ofEpochMilli(result.timestamp).toString())
                        .put("summary", result.summary)
                        .put("matchedText", result.matchedText)
                        .put("tags", JSONArray(result.tags))
                }))
                .toString(),
            metadata = mapOf("count" to results.size)
        )
    }
}
