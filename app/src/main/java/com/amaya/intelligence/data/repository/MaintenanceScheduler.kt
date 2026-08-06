package com.amaya.intelligence.data.repository

import javax.inject.Inject
import javax.inject.Singleton

interface MaintenanceScheduler {
    suspend fun runMaintenanceNow(): MaintenanceResult
}

data class MaintenanceResult(
    val memoryCompactorRan: Boolean,
    val expiredPendingProposals: Int,
    val summarizedSessions: Int,
    val lastRunAt: Long
)

@Singleton
class ManualMaintenanceScheduler @Inject constructor(
    private val pendingProposalRepository: PendingProposalRepository,
    private val memoryRepository: MemoryRepository,
    private val sessionMemoryRepository: SessionMemoryRepository,
    private val sessionSummarizer: SessionSummarizer
) : MaintenanceScheduler {
    private var lastRunAt: Long = 0L

    override suspend fun runMaintenanceNow(): MaintenanceResult {
        val expired = pendingProposalRepository.expireOldProposals().getOrDefault(0)
        memoryRepository.compactStoredMemory().getOrDefault(Unit)
        val summarized = sessionMemoryRepository.listSessionsNeedingSummary(limit = 25).count { sessionId ->
            sessionSummarizer.summarize(sessionId).isSuccess
        }
        lastRunAt = System.currentTimeMillis()
        return MaintenanceResult(
            memoryCompactorRan = true,
            expiredPendingProposals = expired,
            summarizedSessions = summarized,
            lastRunAt = lastRunAt
        )
    }
}
