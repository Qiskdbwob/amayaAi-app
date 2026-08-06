package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillViewTool @Inject constructor(
    private val skillRepository: SkillRepository
) : Tool {
    override val name = "skill_view"
    override val description = "Load the full content of a relevant reusable skill. Use after checking the skill index in the system prompt."

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val name = arguments["name"] as? String
            ?: return@withContext ToolResult.Error("Missing required: name", ErrorType.VALIDATION_ERROR)
        val skill = skillRepository.getSkill(name)
            ?: return@withContext ToolResult.Error("Skill not found: $name", ErrorType.NOT_FOUND)
        skillRepository.recordSkillViewed(skill.metadata.name)
        val refreshedSkill = skillRepository.getSkill(skill.metadata.name) ?: skill
        val metadata = refreshedSkill.metadata
        ToolResult.Success(
            output = JSONObject()
                .put("name", metadata.name)
                .put("content", refreshedSkill.content)
                .put("metadata", JSONObject()
                    .put("name", metadata.name)
                    .put("description", metadata.description)
                    .put("status", metadata.status.name.lowercase())
                    .put("usageCount", metadata.usageCount)
                    .put("successCount", metadata.successCount)
                    .put("failureCount", metadata.failureCount)
                    .put("createdAt", metadata.createdAt)
                    .put("updatedAt", metadata.updatedAt)
                    .put("lastUsedAt", metadata.lastUsedAt)
                    .put("createdBy", metadata.createdBy)
                    .put("version", metadata.version)
                    .put("tags", JSONArray(metadata.tags))
                    .put("enabled", metadata.enabled)
                    .put("needsReview", metadata.needsReview)
                    .put("reviewReason", metadata.reviewReason)
                )
                .toString(),
            metadata = mapOf("skill" to metadata.name)
        )
    }
}
