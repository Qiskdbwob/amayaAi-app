package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryProposal
import com.amaya.intelligence.domain.memory.MemoryType
import javax.inject.Inject
import javax.inject.Singleton

enum class SelfImprovementRoute {
    APPLY_NOW,
    REQUIRE_APPROVAL,
    IGNORE
}

data class SelfImprovementDecision(
    val route: SelfImprovementRoute,
    val message: String
)

@Singleton
class SelfImprovementPolicy @Inject constructor(
    private val classifier: MemoryClassifier
) {
    fun decideMemory(proposal: MemoryProposal, settings: MemoryBehaviorSettings): SelfImprovementDecision {
        if (proposal.action == MemoryAction.IGNORE) {
            return SelfImprovementDecision(SelfImprovementRoute.IGNORE, proposal.reason)
        }
        return when (proposal.type) {
            MemoryType.REMINDER -> SelfImprovementDecision(
                SelfImprovementRoute.IGNORE,
                "Reminder-like content must use create_reminder instead of memory."
            )
            MemoryType.SKILL_CANDIDATE -> SelfImprovementDecision(
                SelfImprovementRoute.IGNORE,
                "Skill suggestions are handled by Skills, not Memory."
            )
            MemoryType.DAILY_LOG -> {
                if (settings.dailyNotesEnabled) {
                    SelfImprovementDecision(SelfImprovementRoute.APPLY_NOW, "Daily note saved.")
                } else {
                    SelfImprovementDecision(SelfImprovementRoute.IGNORE, "Daily notes are disabled.")
                }
            }
            MemoryType.USER_PROFILE,
            MemoryType.LONG_TERM_MEMORY,
            MemoryType.WORKSPACE_FACT -> decideDurableMemory(proposal, settings)
        }
    }


    private fun decideDurableMemory(proposal: MemoryProposal, settings: MemoryBehaviorSettings): SelfImprovementDecision {
        if (!settings.suggestNewMemories && proposal.action != MemoryAction.REMOVE) {
            return SelfImprovementDecision(SelfImprovementRoute.IGNORE, "New memory suggestions are disabled.")
        }
        if (!isSafeStructuredMemory(proposal)) {
            return SelfImprovementDecision(SelfImprovementRoute.IGNORE, "Skipped noisy, unsafe, or low-confidence memory candidate.")
        }
        if (proposal.action == MemoryAction.REMOVE) {
            return if (settings.autoSaveSafeMemory) {
                SelfImprovementDecision(SelfImprovementRoute.APPLY_NOW, "Safe memory removal was applied automatically.")
            } else {
                SelfImprovementDecision(SelfImprovementRoute.REQUIRE_APPROVAL, "Safe memory removal is waiting for approval.")
            }
        }
        return if (settings.autoSaveSafeMemory) {
            SelfImprovementDecision(SelfImprovementRoute.APPLY_NOW, "Safe structured memory was saved automatically.")
        } else {
            SelfImprovementDecision(SelfImprovementRoute.REQUIRE_APPROVAL, "Safe structured memory is waiting for approval.")
        }
    }

    private fun isSafeStructuredMemory(proposal: MemoryProposal): Boolean {
        val safety = classifier.checkSafety(proposal.content)
        val content = proposal.content.trim()
        val looksLikeRawTranscript = content.lines().size > 6 || content.length > 600
        val minImportance = if (proposal.action == MemoryAction.REMOVE) 0.40 else 0.60
        return safety.safe &&
            !looksLikeRawTranscript &&
            proposal.confidence >= 0.75 &&
            proposal.importance >= minImportance
    }
}
