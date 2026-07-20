package com.amaya.intelligence.data.repository

import javax.inject.Inject
import javax.inject.Singleton

interface SessionSummarizer {
    suspend fun summarize(sessionId: String): Result<SessionSummary>
}

@Singleton
class DeterministicSessionSummarizer @Inject constructor(
    private val sessionMemoryRepository: SessionMemoryRepository
) : SessionSummarizer {
    override suspend fun summarize(sessionId: String): Result<SessionSummary> = runCatching {
        val summaryText = sessionMemoryRepository.summarizeSession(sessionId, forceRebuild = true)
        val now = System.currentTimeMillis()
        val summary = SessionSummary(
            sessionId = sessionId,
            summary = summaryText,
            tags = inferTags(summaryText),
            createdAt = now,
            updatedAt = now,
            workspacePath = sessionMemoryRepository.sessionWorkspacePath(sessionId),
            workspaceId = sessionMemoryRepository.sessionWorkspaceId(sessionId),
            assistantMode = sessionMemoryRepository.sessionAssistantMode(sessionId) ?: com.amaya.intelligence.domain.models.AssistantMode.CHAT.name,
            ownerId = sessionMemoryRepository.sessionOwnerId(sessionId)
        )
        sessionMemoryRepository.saveSummary(summary)
        summary
    }

    private fun inferTags(text: String): List<String> {
        val lower = text.lowercase()
        return listOf("android", "webview", "oauth", "browser", "skill", "memory", "reminder", "kotlin", "gradle")
            .filter { it in lower }
    }
}
