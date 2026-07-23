package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryStatus
import com.amaya.intelligence.domain.memory.MemoryType

data class WorkspaceMemoryBinding(
    val id: String,
    val root: String,
    val recordCount: Int,
    val rootExists: Boolean
)

data class MemoryRecord(
    val id: String,
    val type: MemoryType,
    val action: MemoryAction,
    val scope: MemoryScope,
    val target: String,
    val label: String,
    val title: String,
    val content: String,
    val reason: String,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val expiresAt: Long? = null,
    val source: String = "index",
    val version: Int = 1,
    val workspacePath: String? = null,
    val workspaceId: String? = null,
    val subject: String = "memory",
    val attribute: String = "",
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val sourceConversationId: String? = null
)

interface MemoryRepository {
    suspend fun applyProposal(proposal: MemoryProposal): Result<String>
    suspend fun readUserProfile(): String
    suspend fun readWorkspaceFacts(workspacePath: String? = null): String
    suspend fun listWorkspaceBindings(): List<WorkspaceMemoryBinding>
    suspend fun remapWorkspace(workspaceId: String, newRoot: String): Result<Unit>
    suspend fun compactStoredMemory(): Result<Unit>
    suspend fun listMemoryRecords(
        type: MemoryType? = null,
        query: String? = null,
        limit: Int = 50,
        workspacePath: String? = null
    ): List<MemoryRecord>
    suspend fun updateMemoryById(id: String, content: String, expectedVersion: Int, workspacePath: String? = null): Result<String>
    suspend fun deleteMemoryById(id: String, expectedVersion: Int, workspacePath: String? = null): Result<String>
}
