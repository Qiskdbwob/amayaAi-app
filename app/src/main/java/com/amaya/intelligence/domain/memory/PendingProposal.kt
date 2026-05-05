package com.amaya.intelligence.domain.memory

data class PendingProposal(
    val id: String,
    val sourceSessionId: String,
    val type: PendingProposalType,
    val target: String,
    val action: PendingProposalAction,
    val title: String,
    val content: String,
    val reason: String,
    val confidence: Double,
    val importance: Double,
    val createdAt: Long,
    val status: PendingProposalStatus
)

enum class PendingProposalType {
    USER_PROFILE,
    LONG_TERM_MEMORY,
    DAILY_LOG,
    SKILL_CREATE,
    SKILL_PATCH,
    SKILL_UPDATE,
    REMINDER,
    WORKSPACE_FACT
}

enum class PendingProposalAction {
    ADD,
    REPLACE,
    REMOVE,
    CREATE,
    PATCH,
    UPDATE,
    IGNORE
}

enum class PendingProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    APPLIED,
    EXPIRED
}
