package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalAction
import com.amaya.intelligence.domain.memory.PendingProposalStatus
import com.amaya.intelligence.domain.memory.PendingProposalType

fun MemoryProposal.toPendingProposal(sessionId: String): PendingProposal = PendingProposal(
    id = id,
    sourceSessionId = sessionId,
    type = type.toPendingProposalType(),
    target = type.memoryTarget(),
    action = action.toPendingProposalAction(),
    title = title,
    content = content,
    reason = reason,
    confidence = confidence,
    importance = importance,
    createdAt = createdAt,
    status = PendingProposalStatus.PENDING
)


fun PendingProposal.toMemoryProposal(): MemoryProposal = MemoryProposal(
    id = id,
    type = type.toMemoryType(),
    action = action.toMemoryAction(),
    scope = type.toMemoryScope(),
    title = title,
    content = content,
    reason = reason,
    confidence = confidence,
    importance = importance,
    createdAt = createdAt
)

private fun MemoryType.toPendingProposalType(): PendingProposalType = when (this) {
    MemoryType.USER_PROFILE -> PendingProposalType.USER_PROFILE
    MemoryType.LONG_TERM_MEMORY -> PendingProposalType.LONG_TERM_MEMORY
    MemoryType.DAILY_LOG -> PendingProposalType.DAILY_LOG
    MemoryType.SKILL_CANDIDATE -> PendingProposalType.SKILL_CREATE
    MemoryType.REMINDER -> PendingProposalType.REMINDER
    MemoryType.WORKSPACE_FACT -> PendingProposalType.WORKSPACE_FACT
}

private fun MemoryType.memoryTarget(): String = when (this) {
    MemoryType.USER_PROFILE -> "USER.md"
    MemoryType.LONG_TERM_MEMORY -> "MEMORY.md"
    MemoryType.DAILY_LOG -> "memory/YYYY-MM-DD.md"
    MemoryType.SKILL_CANDIDATE -> "agent-learned-skill"
    MemoryType.REMINDER -> "reminder database"
    MemoryType.WORKSPACE_FACT -> "PROJECT.md"
}

private fun MemoryAction.toPendingProposalAction(): PendingProposalAction = when (this) {
    MemoryAction.ADD -> PendingProposalAction.ADD
    MemoryAction.REPLACE -> PendingProposalAction.REPLACE
    MemoryAction.REMOVE -> PendingProposalAction.REMOVE
    MemoryAction.IGNORE -> PendingProposalAction.IGNORE
}

private fun PendingProposalType.toMemoryType(): MemoryType = when (this) {
    PendingProposalType.USER_PROFILE -> MemoryType.USER_PROFILE
    PendingProposalType.LONG_TERM_MEMORY -> MemoryType.LONG_TERM_MEMORY
    PendingProposalType.DAILY_LOG -> MemoryType.DAILY_LOG
    PendingProposalType.WORKSPACE_FACT -> MemoryType.WORKSPACE_FACT
    PendingProposalType.SKILL_CREATE,
    PendingProposalType.SKILL_PATCH,
    PendingProposalType.SKILL_UPDATE -> MemoryType.SKILL_CANDIDATE
    PendingProposalType.REMINDER -> MemoryType.REMINDER
}

private fun PendingProposalAction.toMemoryAction(): MemoryAction = when (this) {
    PendingProposalAction.REPLACE,
    PendingProposalAction.UPDATE -> MemoryAction.REPLACE
    PendingProposalAction.REMOVE -> MemoryAction.REMOVE
    PendingProposalAction.IGNORE -> MemoryAction.IGNORE
    PendingProposalAction.ADD,
    PendingProposalAction.CREATE,
    PendingProposalAction.PATCH -> MemoryAction.ADD
}

private fun PendingProposalType.toMemoryScope(): MemoryScope = when (this) {
    PendingProposalType.USER_PROFILE -> MemoryScope.USER
    PendingProposalType.WORKSPACE_FACT -> MemoryScope.WORKSPACE
    PendingProposalType.DAILY_LOG -> MemoryScope.SESSION
    PendingProposalType.REMINDER -> MemoryScope.USER
    PendingProposalType.LONG_TERM_MEMORY,
    PendingProposalType.SKILL_CREATE,
    PendingProposalType.SKILL_PATCH,
    PendingProposalType.SKILL_UPDATE -> MemoryScope.GLOBAL
}
