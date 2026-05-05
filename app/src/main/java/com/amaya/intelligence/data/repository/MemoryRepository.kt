package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType

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
    val importance: Double,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val expiresAt: Long? = null,
    val source: String = "index"
)

interface MemoryRepository {
    suspend fun applyProposal(proposal: MemoryProposal): Result<String>
    suspend fun readUserProfile(): String
    suspend fun readHotMemory(): String
    suspend fun readWorkspaceFacts(): String
    suspend fun readRecentDailyNotes(limit: Int = 3): String
    suspend fun appendDailyLog(content: String): Result<Unit>
    suspend fun compactStoredMemory(): Result<Unit>
    suspend fun listMemoryRecords(type: MemoryType? = null, query: String? = null, limit: Int = 50): List<MemoryRecord>
    suspend fun removeMemoryById(id: String): Result<String>
    suspend fun updateMemoryById(id: String, content: String): Result<String>
}
