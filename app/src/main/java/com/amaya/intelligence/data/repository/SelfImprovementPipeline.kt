package com.amaya.intelligence.data.repository

import com.amaya.intelligence.domain.memory.MemoryClassifier
import com.amaya.intelligence.domain.memory.PendingProposal
import com.amaya.intelligence.domain.memory.PendingProposalAction
import com.amaya.intelligence.domain.memory.PendingProposalStatus
import com.amaya.intelligence.domain.memory.PendingProposalType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CompletedInteractionContext(
    val sessionId: String,
    val userMessages: List<String>,
    val assistantMessages: List<String>,
    val toolCalls: List<String>,
    val toolResults: List<String>,
    val timestamp: Long,
    val workspacePath: String? = null,
    val workspaceId: String? = null,
    val successful: Boolean = true
)

data class SelfImprovementResult(
    val skillProposals: List<PendingProposal> = emptyList()
)

internal data class WorkflowEvidence(
    val sessionId: String,
    val fingerprint: String,
    val trigger: String,
    val tools: List<String>,
    val successful: Boolean,
    val activeSkill: String? = null,
    /** First observed failure reason for this workflow, used to learn reusable pitfalls. */
    val failureHint: String? = null,
    val timestamp: Long,
    val workspacePath: String?
)

@Singleton
class SelfImprovementPipeline @Inject constructor(
    private val classifier: MemoryClassifier,
    private val pendingProposalRepository: PendingProposalRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val evidenceFile: File get() = File(context.filesDir, "skills/workflow-evidence.jsonl")
    private val evidenceLock = Any()

    suspend fun analyzeAndImprove(context: CompletedInteractionContext): SelfImprovementResult {
        val skillProposals = extractSkillCandidates(context)
        skillProposals.forEach { pendingProposalRepository.addProposal(it) }
        return SelfImprovementResult(skillProposals)
    }

    internal fun extractSkillCandidates(context: CompletedInteractionContext): List<PendingProposal> {
        val tools = context.toolCalls.map { it.substringBefore(':').trim() }
            .filter { it.isNotBlank() && it !in SELF_IMPROVEMENT_TOOLS }
        val explicitTeach = context.userMessages.any { message -> TEACH_TERMS.any { it in message.lowercase() } }
        val failedResults = context.toolResults.filter { result -> FAILURE_TERMS.any { it in result.lowercase() } }
        val successful = context.successful &&
            failedResults.isEmpty() &&
            (explicitTeach || context.assistantMessages.isNotEmpty())
        if (tools.isEmpty() && !explicitTeach) return emptyList()

        val trigger = sanitizeEvidence(context.userMessages.firstOrNull().orEmpty()).take(220)
        val sequence = tools.distinct()
        val fingerprint = if (sequence.isEmpty()) "explicit:${trigger.lowercase()}" else sequence.joinToString("|").lowercase()
        val failureHint = sanitizeEvidence(failedResults.firstOrNull().orEmpty()).take(180).takeIf(String::isNotBlank)
        val evidence = WorkflowEvidence(
            sessionId = context.sessionId,
            fingerprint = fingerprint,
            trigger = trigger,
            tools = sequence,
            successful = successful,
            activeSkill = viewedSkillName(context),
            failureHint = failureHint,
            timestamp = context.timestamp,
            workspacePath = context.workspacePath
        )
        val previousEvidence = readEvidence().filter { it.fingerprint == fingerprint }.distinctBy { it.sessionId }
        saveEvidence(evidence)
        val proposals = mutableListOf<PendingProposal>()

        // Hermes-style learning from mistakes: when the same workflow keeps failing with the
        // same error and never succeeds, propose a durable "pitfall" lesson for user approval
        // (the write-approval gate) instead of silently forgetting the failure.
        if (failureHint != null) {
            val allMatches = (previousEvidence + evidence).distinctBy { it.sessionId }
            val sameFailures = allMatches.filter { !it.successful && it.failureHint == failureHint }
            if (allMatches.none { it.successful } && sameFailures.size >= REQUIRED_FAILURE_SESSIONS) {
                proposals += buildFailureLessonProposal(context, sequence, fingerprint, trigger, failureHint, allMatches)
            }
        }

        if (!successful) return proposals

        // A skill that failed repeatedly then completed successfully gets a recovery patch.
        val viewedSkill = evidence.activeSkill
        val failedSessions = previousEvidence.filter { !it.successful && it.activeSkill == viewedSkill }.map { it.sessionId }
        if (viewedSkill != null && failedSessions.size >= REQUIRED_FAILURE_SESSIONS) {
            val sourceSessions = (failedSessions.takeLast(REQUIRED_FAILURE_SESSIONS) + context.sessionId).distinct()
            proposals += buildSkillPatchProposal(viewedSkill, sequence, sourceSessions, context)
        }

        val matching = (previousEvidence + evidence).filter { it.successful }.distinctBy { it.sessionId }
        if (!explicitTeach && matching.size < REQUIRED_SUCCESSFUL_SESSIONS) return proposals
        val sourceSessions = matching.map { it.sessionId }.takeLast(REQUIRED_SUCCESSFUL_SESSIONS).ifEmpty { listOf(context.sessionId) }
        val name = skillName(sequence, trigger)
        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: Reviewed workflow candidate based on verified successful sessions.")
            appendLine("version: 0.1.0")
            appendLine("createdBy: self-improvement")
            appendLine("---")
            appendLine()
            appendLine("# Workflow")
            appendLine()
            appendLine("## Trigger")
            appendLine("- $trigger")
            appendLine()
            appendLine(if (sequence.isEmpty()) "## Source Procedure" else "## Verified Sequence")
            if (sequence.isEmpty()) appendLine("- $trigger") else sequence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Evidence")
            sourceSessions.forEach { appendLine("- Successful session $it") }
            appendLine()
            appendLine("Review scope, procedure details, and task-specific assumptions before activation.")
        }.trim()
        proposals += PendingProposal(
            id = "skill_${name}_${sourceSessions.joinToString("_")}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(180),
            sourceSessionId = sourceSessions.last(),
            type = PendingProposalType.SKILL_CREATE,
            target = name,
            action = PendingProposalAction.CREATE,
            title = "Review reusable workflow: $name",
            content = content,
            reason = if (explicitTeach) "User explicitly asked to save this successful workflow." else "Same workflow succeeded across ${sourceSessions.size} sessions.",
            confidence = if (explicitTeach) 0.9 else 0.78,
            createdAt = context.timestamp,
            status = PendingProposalStatus.PENDING,
            workspacePath = context.workspacePath,
            workspaceId = context.workspaceId,
            sourceSessionIds = sourceSessions,
            evidence = sourceSessions.map {
                if (sequence.isEmpty()) "Explicitly taught in session $it" else "Successful session $it with sequence: ${sequence.joinToString(" → ")}"
            }
        )
        return proposals
    }

    private fun buildFailureLessonProposal(
        context: CompletedInteractionContext,
        sequence: List<String>,
        fingerprint: String,
        trigger: String,
        failureHint: String,
        matches: List<WorkflowEvidence>
    ): PendingProposal {
        val failedCount = matches.count { !it.successful }
        val target = "avoid-" + fingerprint
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(50)
            .ifBlank { "tool-pitfall" }
        val sourceSessions = matches.map { it.sessionId }.takeLast(REQUIRED_FAILURE_SESSIONS + 2)
        val content = buildString {
            appendLine("---")
            appendLine("name: $target")
            appendLine("description: Lesson learned from repeated tool failures. Read before attempting this workflow.")
            appendLine("version: 0.1.0")
            appendLine("createdBy: self-improvement")
            appendLine("---")
            appendLine()
            appendLine("# Pitfall")
            appendLine()
            appendLine("This workflow failed $failedCount times with the same error and never completed successfully:")
            appendLine()
            appendLine("## Trigger")
            appendLine("- ${trigger.ifBlank { sequence.joinToString(" → ") }}")
            appendLine()
            appendLine("## Failing Sequence")
            if (sequence.isEmpty()) appendLine("- $trigger") else sequence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Observed Error")
            appendLine("> $failureHint")
            appendLine()
            appendLine("Do not repeat this sequence unchanged. Verify tool arguments, available tools, and prerequisites first; prefer smaller steps.")
            appendLine()
            appendLine("## Evidence")
            sourceSessions.forEach { appendLine("- Failed session $it") }
        }.trim()
        return PendingProposal(
            id = "lesson_${fingerprint}_${failureHint.hashCode()}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(180),
            sourceSessionId = sourceSessions.last(),
            type = PendingProposalType.SKILL_CREATE,
            target = target,
            action = PendingProposalAction.CREATE,
            title = "Tool pitfall to avoid: ${sequence.take(3).joinToString(" → ").ifBlank { trigger.take(40) }}",
            content = content,
            reason = "The same tool workflow failed $failedCount times with the same error and never succeeded.",
            confidence = 0.7,
            createdAt = context.timestamp,
            status = PendingProposalStatus.PENDING,
            workspacePath = context.workspacePath,
            sourceSessionIds = sourceSessions,
            evidence = sourceSessions.map { "Failed session $it: $failureHint" }
        )
    }

    private fun buildSkillPatchProposal(
        skillName: String,
        sequence: List<String>,
        sourceSessions: List<String>,
        context: CompletedInteractionContext
    ): PendingProposal {
        val patch = buildString {
            appendLine("# Verified Recovery")
            appendLine()
            appendLine("After repeated failures, this sequence completed successfully:")
            sequence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Validate the recovery steps against the existing skill before applying.")
        }.trim()
        return PendingProposal(
            id = "skill_patch_${skillName}_${sequence.joinToString("|").hashCode()}".replace(Regex("[^A-Za-z0-9_-]"), "_"),
            sourceSessionId = context.sessionId,
            type = PendingProposalType.SKILL_PATCH,
            target = skillName,
            action = PendingProposalAction.PATCH,
            title = "Review recovery for $skillName",
            content = patch,
            reason = "The same skill workflow failed repeatedly, then completed successfully.",
            confidence = 0.78,
            createdAt = context.timestamp,
            status = PendingProposalStatus.PENDING,
            workspacePath = context.workspacePath,
            workspaceId = context.workspaceId,
            sourceSessionIds = sourceSessions,
            evidence = sourceSessions.mapIndexed { index, session ->
                if (index == sourceSessions.lastIndex) "Successful recovery session $session" else "Failed session $session"
            }
        )
    }

    private fun viewedSkillName(context: CompletedInteractionContext): String? = context.toolCalls.firstNotNullOfOrNull { call ->
        if (!call.startsWith("skill:")) return@firstNotNullOfOrNull null
        Regex("(?:skill_id|name)=([^,}]+)").find(call)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    }

    private fun saveEvidence(evidence: WorkflowEvidence) = synchronized(evidenceLock) {
        val existing = readEvidence()
        if (existing.any { it.sessionId == evidence.sessionId && it.fingerprint == evidence.fingerprint }) return@synchronized
        evidenceFile.parentFile?.mkdirs()
        evidenceFile.appendText(evidence.toJson().toString() + "\n")
        compactEvidence(existing + evidence)
    }

    private fun compactEvidence(records: List<WorkflowEvidence>) {
        val cutoff = System.currentTimeMillis() - EVIDENCE_MAX_AGE_MS
        val kept = records.filter { it.timestamp >= cutoff }
            .filter { it.trigger.isNotBlank() }
            .distinctBy { "${it.sessionId}:${it.fingerprint}" }
            .takeLast(MAX_EVIDENCE_RECORDS)
        val tmp = File(evidenceFile.parentFile, "${evidenceFile.name}.tmp")
        tmp.writeText(kept.joinToString("\n") { it.toJson().toString() } + if (kept.isEmpty()) "" else "\n")
        if (!tmp.renameTo(evidenceFile)) {
            evidenceFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun readEvidence(): List<WorkflowEvidence> = synchronized(evidenceLock) {
        runCatching {
            if (!evidenceFile.exists()) return@synchronized emptyList()
            evidenceFile.readLines().mapNotNull { line -> runCatching { JSONObject(line).toWorkflowEvidence() }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun WorkflowEvidence.toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("fingerprint", fingerprint)
        .put("trigger", trigger)
        .put("tools", JSONArray(tools))
        .put("successful", successful)
        .put("activeSkill", activeSkill)
        .put("failureHint", failureHint)
        .put("timestamp", timestamp)
        .put("workspacePath", workspacePath)

    private fun JSONObject.toWorkflowEvidence(): WorkflowEvidence = WorkflowEvidence(
        sessionId = optString("sessionId"),
        fingerprint = optString("fingerprint"),
        trigger = optString("trigger"),
        tools = optJSONArray("tools")?.let { array -> List(array.length()) { array.optString(it) } } ?: emptyList(),
        successful = optBoolean("successful"),
        activeSkill = optString("activeSkill").takeIf(String::isNotBlank),
        failureHint = optString("failureHint").takeIf(String::isNotBlank),
        timestamp = optLong("timestamp"),
        workspacePath = optString("workspacePath").takeIf(String::isNotBlank)
    )

    private fun sanitizeEvidence(text: String): String {
        if (!classifier.checkSafety(text).safe) return ""
        return text.replace(Regex("(?is)<think>.*?</think>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun skillName(tools: List<String>, trigger: String): String =
        (tools.take(3).joinToString("-") + "-" + trigger.split(Regex("\\s+")).take(3).joinToString("-"))
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "learned-workflow" }

    companion object {
        private const val REQUIRED_SUCCESSFUL_SESSIONS = 2
        private const val REQUIRED_FAILURE_SESSIONS = 2
        private const val MAX_EVIDENCE_RECORDS = 500
        private const val EVIDENCE_MAX_AGE_MS = 90L * 24L * 60L * 60L * 1000L
        private val TEACH_TERMS = listOf("save this workflow", "remember this workflow", "teach this workflow", "simpan workflow", "pelajari workflow", "jadikan skill")
        private val FAILURE_TERMS = listOf("error", "failed", "timeout", "cancelled", "gagal")
        private val SELF_IMPROVEMENT_TOOLS = setOf("memory", "skill", "update_memory", "memory_manage", "skill_view", "skill_manage", "session_search", "update_todo")
    }
}
