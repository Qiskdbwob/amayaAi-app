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
    /** Host of the site an automated browser workflow operated on (site-scoped learning). */
    val siteHost: String? = null,
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
        val factProposals = extractDurableFacts(context)
        (skillProposals + factProposals).forEach { pendingProposalRepository.addProposal(it) }
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
        val siteHost = siteHostFromContext(context)
        // Browser workflows are fingerprinted per site so pitfalls and skill candidates are
        // learned per host (e.g. browser|login.example.com) instead of one bucket for all sites.
        val fingerprint = when {
            sequence.isEmpty() -> "explicit:${trigger.lowercase()}"
            siteHost != null -> (sequence + listOf("site:$siteHost")).joinToString("|").lowercase()
            else -> sequence.joinToString("|").lowercase()
        }
        val failureHint = sanitizeEvidence(failedResults.firstOrNull().orEmpty()).take(180).takeIf(String::isNotBlank)
        val evidence = WorkflowEvidence(
            sessionId = context.sessionId,
            fingerprint = fingerprint,
            trigger = trigger,
            tools = sequence,
            successful = successful,
            activeSkill = viewedSkillName(context),
            failureHint = failureHint,
            siteHost = siteHost,
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
                proposals += buildFailureLessonProposal(context, sequence, fingerprint, trigger, failureHint, allMatches, siteHost)
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
        matches: List<WorkflowEvidence>,
        siteHost: String?
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
            siteHost?.let {
                appendLine()
                appendLine("## Site")
                appendLine("- $it")
            }
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
            evidence = sourceSessions.map { "Failed session $it${if (siteHost != null) " on $siteHost" else ""}: $failureHint" }
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

    // ====================================================================
    // PROACTIVE PITFALL RECALL (the retrieval side of self-learning)
    // ====================================================================

    /**
     * Turn-start retrieval: match the current user message against stored failed-workflow evidence
     * and return short "known pitfall" lines so the model avoids repeating a sequence that already
     * failed. Site-scoped: same-site evidence ranks highest, then workspace match, then overlapping
     * tools, then recency. Conservative — only explicitly failed sessions with a failure hint count.
     */
    suspend fun matchingPitfalls(
        userMessage: String,
        siteHost: String?,
        workspacePath: String?,
        limit: Int = 3
    ): List<String> {
        if (userMessage.isBlank()) return emptyList()
        val lower = userMessage.lowercase()
        val matched = readEvidence()
            .filter { !it.successful && !it.failureHint.isNullOrBlank() }
            .mapNotNull { evidence ->
                val toolOverlap = evidence.tools.count { tool -> tool.isNotBlank() && lower.contains(tool.lowercase()) }
                var score = 0.0
                if (siteHost != null && evidence.siteHost == siteHost) score += 3.0
                else if (evidence.siteHost != null && lower.contains(evidence.siteHost)) score += 2.5
                if (workspacePath != null && evidence.workspacePath == workspacePath) score += 1.5
                score += toolOverlap * 1.5
                val ageDays = (System.currentTimeMillis() - evidence.timestamp).coerceAtLeast(0L) / (24L * 60L * 60L * 1000L)
                score += 1.0 / (1.0 + ageDays / 14.0)
                if (score >= PITFALL_MIN_MATCH_SCORE) evidence to score else null
            }
            .sortedByDescending { it.second }
            .take(limit)
        return matched.map { (evidence, _) ->
            val site = evidence.siteHost?.let { " on $it" }.orEmpty()
            val tools = evidence.tools.take(4).joinToString(" → ").ifBlank { evidence.trigger.take(80) }
            "- $tools$site failed with: ${evidence.failureHint.orEmpty().take(160)}"
        }
    }

    /**
     * Learn from user corrections. The user's follow-up says the previous outcome was wrong:
     * mark the previous turn's evidence as failed (so `successful` reflects reality) and propose
     * a correction lesson for approval. Approval-gated like every other durable write.
     */
    suspend fun recordUserCorrection(sessionId: String, correction: String, workspacePath: String?): SelfImprovementResult {
        val hint = sanitizeEvidence(correction).take(160).ifBlank { "User corrected the outcome" }
        val updated = synchronized(evidenceLock) {
            val records = readEvidence()
            var changed = false
            val rewritten = records.map { record ->
                if (record.sessionId == sessionId && record.successful) {
                    changed = true
                    record.copy(successful = false, failureHint = hint, timestamp = System.currentTimeMillis())
                } else record
            }
            if (changed) writeEvidenceLines(rewritten)
            rewritten
        }
        val failed = updated.filter { it.sessionId == sessionId }
        if (failed.isEmpty()) return SelfImprovementResult()
        val proposal = PendingProposal(
            id = "corr_${sessionId}_${correction.hashCode()}".replace(Regex("[^A-Za-z0-9_-]"), "_").take(180),
            sourceSessionId = sessionId,
            type = PendingProposalType.SKILL_CREATE,
            target = "avoid-user-corrected-outcome",
            action = PendingProposalAction.CREATE,
            title = "Outcome was corrected by the user",
            content = buildString {
                appendLine("---")
                appendLine("name: avoid-user-corrected-outcome")
                appendLine("description: Lesson from an outcome the user explicitly corrected.")
                appendLine("version: 0.1.0")
                appendLine("createdBy: self-improvement")
                appendLine("---")
                appendLine()
                appendLine("# Pitfall")
                appendLine()
                appendLine("The previous result was wrong and the user corrected it:")
                appendLine()
                appendLine("> $hint")
                appendLine()
                appendLine("Re-verify the actual output/state before claiming success; re-read the request and the tool evidence.")
            }.trim(),
            reason = "The user corrected the previous outcome; treat that workflow as failed until proven otherwise.",
            confidence = 0.65,
            createdAt = System.currentTimeMillis(),
            status = PendingProposalStatus.PENDING,
            workspacePath = workspacePath,
            workspaceId = null,
            sourceSessionIds = failed.map { it.sessionId }.distinct(),
            evidence = failed.map { "Corrected in session ${it.sessionId}: $hint" }
        )
        pendingProposalRepository.addProposal(proposal)
        return SelfImprovementResult(listOf(proposal))
    }

    private fun writeEvidenceLines(records: List<WorkflowEvidence>) {
        val content = records.joinToString("\n") { it.toJson().toString() } + if (records.isEmpty()) "" else "\n"
        evidenceFile.parentFile?.mkdirs()
        val tmp = File(evidenceFile.parentFile, "${evidenceFile.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(evidenceFile)) {
            evidenceFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    // ====================================================================
    // AUTO MEMORY CONSOLIDATION (Hermes-style self-learning)
    // ====================================================================

    /**
     * Hermes-style memory consolidation. After a successful interaction, scan the user's own
     * messages for explicit durable preferences and workspace/environment facts and propose them
     * for approval (the write-approval gate), so memory accumulates even when the model never
     * called a memory tool. Conservative: only explicit markers, short declarative sentences,
     * and never questions or task requests.
     */
    internal fun extractDurableFacts(context: CompletedInteractionContext): List<PendingProposal> {
        if (!context.successful || context.assistantMessages.isEmpty()) return emptyList()
        val workspace = context.workspacePath?.takeIf(String::isNotBlank)
        val proposals = mutableListOf<PendingProposal>()
        val seen = mutableSetOf<String>()
        for (message in context.userMessages) {
            if (proposals.size >= MAX_FACTS_PER_TURN) break
            val lower = message.lowercase()
            for ((kind, markers) in DURABLE_FACT_PATTERNS) {
                if (proposals.size >= MAX_FACTS_PER_TURN) break
                val marker = markers.firstOrNull { it in lower } ?: continue
                val fact = extractFactSentence(message, lower.indexOf(marker))
                    ?.let(::cleanFact)
                    ?.takeIf { seen.add(it.lowercase()) }
                    ?.let { buildFactProposal(it, kind, workspace, context) }
                    ?: continue
                proposals += fact
            }
        }
        return proposals
    }

    /** Sentence containing [index]; stops at sentence punctuation or the length cap. */
    private fun extractFactSentence(message: String, index: Int): String? {
        val sentenceEnd = message.indexOfAny(charArrayOf('.', '!', '\n', '?'), index)
            .let { if (it < 0) message.length else it + 1 }
        return message.substring(index, sentenceEnd.coerceAtMost(index + MAX_FACT_LENGTH)).trim()
    }

    /** Strip politeness/reminder prefixes and reject tasks, questions, URLs, and secrets. */
    private fun cleanFact(sentence: String): String? {
        val text = sentence.trim()
            .trimEnd('.', '!', '?')
            .replace(Regex("^(?i)(please always|tolong selalu|remember that|please|tolong|remember|ingat bahwa|ingat)\\s*"), "")
            .trim()
        if (text.length !in MIN_FACT_LENGTH..MAX_FACT_LENGTH) return null
        val lower = text.lowercase()
        if (lower.contains('?')) return null
        if (FACT_TASK_VERBS.any { lower.contains(it) }) return null
        if (lower.contains("http://") || lower.contains("https://")) return null
        if (!classifier.checkSafety(text).safe) return null
        return text
    }

    private fun buildFactProposal(
        fact: String,
        kind: FactKind,
        workspacePath: String?,
        context: CompletedInteractionContext
    ): PendingProposal? {
        if (kind == FactKind.WORKSPACE && workspacePath == null) return null
        val isWorkspace = kind == FactKind.WORKSPACE
        val type = if (isWorkspace) PendingProposalType.WORKSPACE_FACT else PendingProposalType.USER_PROFILE
        val lower = fact.lowercase()
        val title = when {
            !isWorkspace && listOf("name", "call me", "panggil", "nama").any { it in lower } -> "User name"
            !isWorkspace && listOf("language", "bahasa", "respond", "reply", "jawab", "speak").any { it in lower } -> "Language preference"
            isWorkspace && listOf("build", "gradle", "maven", "npm", "bun", "yarn", "package manager").any { it in lower } -> "Build tooling"
            isWorkspace && "test" in lower -> "Testing convention"
            else -> if (isWorkspace) "Learned workspace fact" else "Learned user preference"
        }
        val target = (if (isWorkspace) "fact-" else "profile-") + lower
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { if (isWorkspace) "workspace" else "user" }
        return PendingProposal(
            id = "mem_${java.util.UUID.randomUUID().toString().take(12)}",
            sourceSessionId = context.sessionId,
            type = type,
            target = target,
            action = PendingProposalAction.ADD,
            title = title,
            content = fact,
            reason = if (isWorkspace) {
                "Detected durable workspace fact from a successful interaction (auto-consolidation)."
            } else {
                "Detected durable user preference from a successful interaction (auto-consolidation)."
            },
            confidence = 0.7,
            createdAt = context.timestamp,
            status = PendingProposalStatus.PENDING,
            workspacePath = if (isWorkspace) workspacePath else null,
            workspaceId = if (isWorkspace) context.workspaceId else null,
            sourceSessionIds = listOf(context.sessionId),
            evidence = listOf("Detected from user message in session ${context.sessionId}")
        )
    }

    // ====================================================================
    // SITE-SCOPED BROWSER SELF-LEARNING
    // ====================================================================

    /** Host of the site an automated browser workflow operated on, for site-scoped learning. */
    private fun siteHostFromContext(context: CompletedInteractionContext): String? =
        context.toolCalls.firstNotNullOfOrNull(::siteHostFromToolCall)

    private fun siteHostFromToolCall(call: String): String? {
        if (!call.startsWith("browser:")) return null
        val url = Regex("(?:url|active_url)=(\"?)([^,\"}]+)\\1")
            .find(call)?.groupValues?.getOrNull(2)?.trim() ?: return null
        if (url.isBlank()) return null
        return runCatching {
            val raw = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            java.net.URI(raw).host?.removePrefix("www.")
        }.getOrNull()
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
        .put("siteHost", siteHost)
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
        siteHost = optString("siteHost").takeIf(String::isNotBlank),
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

    private enum class FactKind { USER, WORKSPACE }

    companion object {
        private const val REQUIRED_SUCCESSFUL_SESSIONS = 2
        private const val REQUIRED_FAILURE_SESSIONS = 2
        private const val MAX_EVIDENCE_RECORDS = 500
        /** Minimum relevance for a pitfall to be injected at turn start (site match alone passes). */
        private const val PITFALL_MIN_MATCH_SCORE = 2.5
        private const val EVIDENCE_MAX_AGE_MS = 90L * 24L * 60L * 60L * 1000L
        private const val MAX_FACTS_PER_TURN = 2
        private const val MIN_FACT_LENGTH = 8
        private const val MAX_FACT_LENGTH = 180
        private val TEACH_TERMS = listOf("save this workflow", "remember this workflow", "teach this workflow", "simpan workflow", "pelajari workflow", "jadikan skill")
        private val FAILURE_TERMS = listOf("error", "failed", "timeout", "cancelled", "gagal")
        private val SELF_IMPROVEMENT_TOOLS = setOf("memory", "skill", "update_memory", "memory_manage", "skill_view", "skill_manage", "session_search", "update_todo")
        /** Imperative/task phrases that disqualify a sentence from being a durable fact. */
        private val FACT_TASK_VERBS = listOf(
            " fix ", " implement ", " create ", " add ", " write ", " edit ", " update ",
            " delete ", " remove ", " refactor ", " build ", " compile ", " install ", " explain ",
            " show ", " compare ", " generate ", " buat ", " buatkan ", " tulis ", " perbaiki ",
            " tambahkan ", " hapus ", " ubah ", " instal ", " jelaskan ", " bantu ",
            "please fix", "tolong buat", "tolong perbaiki", "can you", "bisa tolong"
        )
        /** Marker phrases that signal a durable fact, grouped by target memory type (longest first). */
        private val DURABLE_FACT_PATTERNS = listOf(
            FactKind.WORKSPACE to listOf(
                "this repository", "this repo", "the repository", "the workspace", "the project",
                "uses gradle", "uses maven", "uses npm", "uses bun", "uses yarn", "package manager",
                "build command", "test command", "build system", "ci uses", "the app is built"
            ),
            FactKind.USER to listOf(
                "please always", "tolong selalu", "i'd prefer", "i would prefer", "i prefer",
                "jangan pernah", "remember that", "ingat bahwa", "call me", "my name is",
                "respond in", "reply in", "my language", "i speak", "panggil aku", "panggil saya",
                "nama saya", "saya lebih suka", "saya suka", "saya biasanya", "i usually",
                "prefer to", "i like", "i love", "i work at", "my email", "i am a", "i am an",
                "bahasa indonesia", "bahasa inggris", "preferens", "selalu", "always ", "never "
            )
        )
    }
}
