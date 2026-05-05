package com.amaya.intelligence.domain.memory

import java.util.UUID

/**
 * A proposed memory update. Proposals are classified and deduplicated before any write happens.
 */
data class MemoryProposal(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoryType,
    val action: MemoryAction,
    val scope: MemoryScope,
    val title: String,
    val content: String,
    val reason: String,
    val confidence: Double,
    val importance: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
)

enum class MemoryType {
    USER_PROFILE,
    LONG_TERM_MEMORY,
    DAILY_LOG,
    SKILL_CANDIDATE,
    REMINDER,
    WORKSPACE_FACT
}

enum class MemoryAction {
    ADD,
    REPLACE,
    REMOVE,
    IGNORE
}

enum class MemoryScope {
    GLOBAL,
    USER,
    PERSONA,
    WORKSPACE,
    SESSION
}
