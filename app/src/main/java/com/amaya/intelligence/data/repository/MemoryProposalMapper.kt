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
    createdAt = createdAt,
    status = PendingProposalStatus.PENDING,
    workspacePath = workspacePath,
    workspaceId = workspaceId,
    sourceSessionIds = listOf(sessionId),
    evidence = listOfNotNull(sourceConversationId?.let { "Explicit memory evidence from conversation $it" })
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
    createdAt = createdAt,
    workspacePath = workspacePath,
    workspaceId = workspaceId,
    sourceConversationId = sourceSessionId,
    subject = when (type) {
        PendingProposalType.USER_PROFILE -> "user"
        PendingProposalType.WORKSPACE_FACT -> "workspace"
        else -> "memory"
    },
    attribute = inferProposalAttribute(title, content)
)

private fun inferProposalAttribute(title: String, content: String): String = (title.ifBlank { content })
    .lowercase()
    .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(80)

private fun MemoryType.toPendingProposalType(): PendingProposalType = when (this) {
    MemoryType.USER_PROFILE -> PendingProposalType.USER_PROFILE
    MemoryType.WORKSPACE_FACT -> PendingProposalType.WORKSPACE_FACT
}

private fun MemoryType.memoryTarget(): String = when (this) {
    MemoryType.USER_PROFILE -> "records.jsonl#user"
    MemoryType.WORKSPACE_FACT -> "workspaces/<workspace-id>/records.jsonl"
}

private fun MemoryAction.toPendingProposalAction(): PendingProposalAction = when (this) {
    MemoryAction.ADD -> PendingProposalAction.ADD
    MemoryAction.REPLACE -> PendingProposalAction.REPLACE
    MemoryAction.IGNORE -> PendingProposalAction.IGNORE
}

private fun PendingProposalType.toMemoryType(): MemoryType = when (this) {
    PendingProposalType.USER_PROFILE -> MemoryType.USER_PROFILE
    PendingProposalType.WORKSPACE_FACT -> MemoryType.WORKSPACE_FACT
    PendingProposalType.SKILL_CREATE,
    PendingProposalType.SKILL_PATCH,
    PendingProposalType.SKILL_UPDATE -> throw IllegalArgumentException("Skill proposals are not memory proposals.")
}

private fun PendingProposalAction.toMemoryAction(): MemoryAction = when (this) {
    PendingProposalAction.REPLACE,
    PendingProposalAction.UPDATE -> MemoryAction.REPLACE
    PendingProposalAction.IGNORE -> MemoryAction.IGNORE
    PendingProposalAction.ADD,
    PendingProposalAction.CREATE,
    PendingProposalAction.PATCH -> MemoryAction.ADD
}

private fun PendingProposalType.toMemoryScope(): MemoryScope = when (this) {
    PendingProposalType.USER_PROFILE -> MemoryScope.USER
    PendingProposalType.WORKSPACE_FACT -> MemoryScope.WORKSPACE
    PendingProposalType.SKILL_CREATE,
    PendingProposalType.SKILL_PATCH,
    PendingProposalType.SKILL_UPDATE -> throw IllegalArgumentException("Skill proposals have no memory scope.")
}
