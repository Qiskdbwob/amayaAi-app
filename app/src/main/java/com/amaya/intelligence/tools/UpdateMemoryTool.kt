package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.BrainSettingsRepository
import com.amaya.intelligence.data.repository.MemoryRepository
import com.amaya.intelligence.data.repository.PendingProposalRepository
import com.amaya.intelligence.data.repository.SelfImprovementPolicy
import com.amaya.intelligence.data.repository.SelfImprovementRoute
import com.amaya.intelligence.data.repository.toPendingProposal
import com.amaya.intelligence.domain.memory.MemoryAction
import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.MemoryScope
import com.amaya.intelligence.domain.memory.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proposal-based memory tool. It classifies and deduplicates before writing to USER.md,
 * MEMORY.md, PROJECT.md, or daily notes. Skills and reminders are managed by their own domains.
 */
@Singleton
class UpdateMemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryClassifier: MemoryClassifier,
    private val brainSettingsRepository: BrainSettingsRepository,
    private val pendingProposalRepository: PendingProposalRepository,
    private val policy: SelfImprovementPolicy
) : Tool {

    override val name = "update_memory"

    override val description = """
        Store explicit durable memory requested by the user. This tool classifies, safety-checks, and deduplicates before writing through MemoryRepository.

        Use only when the user explicitly asks Amaya to remember an important preference, stable fact, daily log entry, or workspace fact. Do not use for inferred guesses.
        Do not store passwords, API keys, access tokens, refresh tokens, OTPs, session cookies, private credentials, payment data, or temporary guesses.
        Use create_reminder for reminders instead of writing reminders to memory.

        Arguments:
        - title (string, optional): Short professional header, 2-7 words. Good: "Response language preference".
        - content (string, required): Final durable memory text, written like a concise summary. Do not copy the user's command. Do not include phrases such as "remember", "tolong ingat", "user asked/discussed", or "user preference/profile". Good: "The user prefers English for responses." / "The user works at an office." Bad: "tolong ingat pakai bahasa Inggris".
        - type (string, optional): user_profile, long_term_memory, daily_log, reminder, workspace_fact.
        - action (string, optional): add, replace, remove, ignore. Default add.
        - scope (string, optional): global, user, persona, workspace, session.
        - reason (string, optional): Specific reason this memory is durable, e.g. "The user explicitly asked Amaya to remember their response-language preference." Do not use a generic reason.
        - confidence (number, optional): 0.0-1.0. Low-confidence proposals are ignored.
        - importance (number, optional): 0.0-1.0.

        Legacy compatibility:
        - target="daily" maps to type="daily_log".
        - target="long" maps to type="long_term_memory".
    """.trimIndent()

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val content = arguments["content"] as? String
            ?: return@withContext ToolResult.Error("Missing required: content", ErrorType.VALIDATION_ERROR)
        val proposal = memoryClassifier.classify(
            content = content,
            requestedType = parseType(arguments),
            requestedAction = parseAction(arguments["action"] as? String),
            requestedScope = parseScope(arguments["scope"] as? String),
            requestedTitle = arguments["title"] as? String,
            reason = arguments["reason"] as? String ?: "Agent requested memory update",
            confidence = (arguments["confidence"] as? Number)?.toDouble() ?: 0.8,
            importance = (arguments["importance"] as? Number)?.toDouble() ?: 0.5
        )

        if (proposal.type == MemoryType.SKILL_CANDIDATE) {
            return@withContext ToolResult.Error("Skills are managed by skill tools, not update_memory.", ErrorType.VALIDATION_ERROR)
        }
        val settings = brainSettingsRepository.getBrainSettings()
        val decision = policy.decideMemory(proposal, settings.memory)
        val result = when (decision.route) {
            SelfImprovementRoute.APPLY_NOW -> memoryRepository.applyProposal(proposal)
            SelfImprovementRoute.REQUIRE_APPROVAL -> pendingProposalRepository
                .addProposal(proposal.toPendingProposal("tool-update-memory"))
                .map { decision.message }
            SelfImprovementRoute.IGNORE -> Result.success(decision.message)
        }
        result.fold(
            onSuccess = { message -> successOutput(proposal, decision.route, message) },
            onFailure = { error -> ToolResult.Error("Failed to update memory: ${error.message}", ErrorType.EXECUTION_ERROR) }
        )
    }

    private fun successOutput(
        proposal: com.amaya.intelligence.domain.memory.MemoryProposal,
        route: SelfImprovementRoute,
        message: String
    ): ToolResult.Success {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val status = when (route) {
            SelfImprovementRoute.APPLY_NOW -> "applied"
            SelfImprovementRoute.REQUIRE_APPROVAL -> "pending_review"
            SelfImprovementRoute.IGNORE -> "ignored"
        }
        return ToolResult.Success(
            output = JSONObject()
                .put("id", proposal.id)
                .put("status", status)
                .put("message", message)
                .put("type", proposal.type.name.lowercase())
                .put("action", proposal.action.name.lowercase())
                .put("scope", proposal.scope.name.lowercase())
                .put("date", today)
                .put("title", proposal.title)
                .put("content", proposal.content)
                .put("reason", proposal.reason)
                .toString(),
            metadata = mapOf(
                "status" to status,
                "message" to message,
                "type" to proposal.type.name.lowercase(),
                "action" to proposal.action.name.lowercase(),
                "title" to proposal.title,
                "content" to proposal.content,
                "reason" to proposal.reason
            )
        )
    }

    private fun parseType(arguments: Map<String, Any?>): MemoryType? {
        val raw = (arguments["type"] as? String)?.lowercase()
            ?: when ((arguments["target"] as? String)?.lowercase()) {
                "daily" -> "daily_log"
                "long" -> "long_term_memory"
                else -> null
            }
        return when (raw) {
            "user_profile", "user" -> MemoryType.USER_PROFILE
            "long_term_memory", "long", "memory" -> MemoryType.LONG_TERM_MEMORY
            "daily_log", "daily" -> MemoryType.DAILY_LOG
            "skill_candidate", "skill" -> MemoryType.SKILL_CANDIDATE
            "reminder" -> MemoryType.REMINDER
            "workspace_fact", "workspace" -> MemoryType.WORKSPACE_FACT
            else -> null
        }
    }

    private fun parseAction(raw: String?): MemoryAction = when (raw?.lowercase()) {
        "replace" -> MemoryAction.REPLACE
        "remove" -> MemoryAction.REMOVE
        "ignore" -> MemoryAction.IGNORE
        else -> MemoryAction.ADD
    }

    private fun parseScope(raw: String?): MemoryScope? = when (raw?.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        "user" -> MemoryScope.USER
        "persona" -> MemoryScope.PERSONA
        "workspace" -> MemoryScope.WORKSPACE
        "session" -> MemoryScope.SESSION
        else -> null
    }
}
