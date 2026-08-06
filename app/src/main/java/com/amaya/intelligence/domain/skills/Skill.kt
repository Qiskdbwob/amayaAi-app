package com.amaya.intelligence.domain.skills

data class Skill(
    val metadata: SkillMetadata,
    val content: String
)

data class SkillMetadata(
    val name: String,
    val description: String,
    val status: SkillStatus,
    val usageCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val createdBy: String,
    val version: String,
    val tags: List<String>,
    val enabled: Boolean = true,
    val needsReview: Boolean = false,
    val reviewReason: String? = null
)

enum class SkillStatus {
    ACTIVE,
    STALE,
    ARCHIVED
}
