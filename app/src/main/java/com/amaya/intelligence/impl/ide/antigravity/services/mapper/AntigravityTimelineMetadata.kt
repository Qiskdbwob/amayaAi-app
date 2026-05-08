package com.amaya.intelligence.impl.ide.antigravity.services.mapper

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Remote Antigravity timeline helpers.
 *
 * Keeps server-sourced message/step metadata stable across live events and later
 * state_sync snapshots so UI affordances such as grouped work summaries do not
 * disappear after a reconciliation refresh.
 */
object AntigravityTimelineMetadata {
    private val isoFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd HH:mm:ss"
    )

    fun parseTimestampMillis(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        text.toLongOrNull()?.let { return it }
        val normalized = text.replace(Regex("(\\.\\d{3})\\d+(Z|[+-]\\d{2}:?\\d{2})$"), "$1$2")
        val candidates = if (normalized == text) listOf(text) else listOf(text, normalized)
        return candidates.firstNotNullOfOrNull { candidate ->
            isoFormats.firstNotNullOfOrNull { pattern ->
                runCatching { SimpleDateFormat(pattern, Locale.US).parse(candidate)?.time }.getOrNull()
            }
        }
    }

    fun messageTimestamp(metadata: Map<String, String>, fallback: Long = System.currentTimeMillis()): Long {
        return parseTimestampMillis(metadata["startedAt"])
            ?: parseTimestampMillis(metadata["firstCreatedAt"])
            ?: parseTimestampMillis(metadata["createdAt"])
            ?: parseTimestampMillis(metadata["timestamp"])
            ?: fallback
    }

    fun mergeMetadata(first: Map<String, String>, second: Map<String, String>): Map<String, String> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first

        val merged = (first + second).toMutableMap()
        mergeMinInt(first, second, "startStepIndex")?.let { merged["startStepIndex"] = it.toString() }
        mergeMaxInt(first, second, "endStepIndex")?.let { merged["endStepIndex"] = it.toString() }
        earliestTimestampString(first, second, "firstCreatedAt", "createdAt", "startedAt")?.let { value ->
            merged["firstCreatedAt"] = value
            merged.putIfAbsent("startedAt", value)
        }
        latestTimestampString(first, second, "lastCreatedAt", "createdAt", "completedAt")?.let { value ->
            merged["lastCreatedAt"] = value
        }
        latestTimestampString(first, second, "completedAt")?.let { value -> merged["completedAt"] = value }
        return merged
    }

    fun finalizedMetadata(
        metadata: Map<String, String>,
        role: MessageRole,
        isStreaming: Boolean,
        steps: List<MessageStep>
    ): Map<String, String> {
        if (role != MessageRole.ASSISTANT || isStreaming) return metadata
        if (metadata["completedAt"] != null) return metadata
        val hasVisibleTool = steps.any { step ->
            step is MessageStep.ToolCall && step.execution.name != "update_todo"
        }
        val hasRunningTool = steps.any { step ->
            step is MessageStep.ToolCall && step.execution.status == ToolStatus.RUNNING
        }
        if (!hasVisibleTool || hasRunningTool) return metadata
        val completedAt = metadata["lastCreatedAt"] ?: metadata["createdAt"] ?: return metadata
        return metadata + ("completedAt" to completedAt)
    }

    fun preserveLifecycle(local: List<UiMessage>, incoming: List<UiMessage>): List<UiMessage> {
        if (local.isEmpty() || incoming.isEmpty()) return incoming

        val localById = local.associateBy { it.id }
        val localByRange = local.mapNotNull { msg -> stableRangeKey(msg)?.let { it to msg } }.toMap()

        return incoming.map { message ->
            val match = localById[message.id]
                ?: stableRangeKey(message)?.let { localByRange[it] }
                ?: findRecentCompatibleLocal(local, message)

            if (match == null) {
                message
            } else {
                val metadata = message.metadata.toMutableMap()
                match.metadata["completedAt"]?.takeIf { it.isNotBlank() }?.let { completedAt ->
                    metadata.putIfAbsent("completedAt", completedAt)
                }
                match.metadata["startedAt"]?.takeIf { it.isNotBlank() }?.let { startedAt ->
                    metadata.putIfAbsent("startedAt", startedAt)
                }
                val timestamp = minOf(message.timestamp, match.timestamp)
                preserveMissingToolTimeline(
                    local = match,
                    incoming = message.copy(timestamp = timestamp, metadata = metadata)
                )
            }
        }
    }

    fun mergeStreamingTurn(local: List<UiMessage>, incoming: List<UiMessage>): List<UiMessage> {
        if (local.isEmpty() || incoming.isEmpty()) return incoming
        val localTurnStart = local.indexOfLast { it.role == MessageRole.USER }.let { if (it >= 0) it + 1 else 0 }
        val localAssistants = local.drop(localTurnStart).filter { it.role == MessageRole.ASSISTANT }
        if (localAssistants.isEmpty()) return incoming

        val mergedLocal = localAssistants.reduce { acc, msg -> mergeAssistant(acc, msg) }
        val incomingTurnStart = incoming.indexOfLast { it.role == MessageRole.USER }.let { if (it >= 0) it + 1 else 0 }
        val targetIndex = incoming.withIndex()
            .filter { it.index >= incomingTurnStart && it.value.role == MessageRole.ASSISTANT }
            .lastOrNull()?.index
            ?: incoming.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (targetIndex < 0) return incoming + mergedLocal

        val merged = mergeAssistant(incoming[targetIndex], mergedLocal)
        return incoming.toMutableList().apply { this[targetIndex] = merged }
    }

    private fun mergeAssistant(base: UiMessage, overlay: UiMessage): UiMessage {
        val first = if (base.timestamp <= overlay.timestamp) base else overlay
        val second = if (first === base) overlay else base
        val metadata = mergeMetadata(first.metadata, second.metadata)
        val content = mergeTextFragments(listOf(first.content, second.content))

        val toolMap = LinkedHashMap<String, com.amaya.intelligence.domain.models.ToolExecution>()
        (first.toolExecutions + second.toolExecutions).forEach { tool ->
            val existing = toolMap[tool.toolCallId]
            toolMap[tool.toolCallId] = if (existing == null) tool else mergeTool(existing, tool)
        }

        val stepMap = LinkedHashMap<String, MessageStep>()
        (first.steps + second.steps).forEach { step ->
            val key = when (step) {
                is MessageStep.Text -> step.id
                is MessageStep.ToolCall -> "tool:${step.execution.toolCallId}"
            }
            val existing = stepMap[key]
            stepMap[key] = when {
                existing is MessageStep.Text && step is MessageStep.Text -> if (step.content.length >= existing.content.length) step else existing
                existing is MessageStep.ToolCall && step is MessageStep.ToolCall -> MessageStep.ToolCall(id = existing.id, execution = mergeTool(existing.execution, step.execution))
                existing == null -> step
                else -> existing
            }
        }

        val merged = first.copy(
            content = content,
            intent = first.intent ?: second.intent,
            timestamp = minOf(first.timestamp, second.timestamp),
            toolExecutions = toolMap.values.toList(),
            steps = stepMap.values.toList(),
            attachments = first.attachments + second.attachments,
            metadata = metadata
        )
        return preserveMissingToolTimeline(first, preserveMissingToolTimeline(second, merged))
    }

    private fun mergeTool(
        first: com.amaya.intelligence.domain.models.ToolExecution,
        second: com.amaya.intelligence.domain.models.ToolExecution
    ): com.amaya.intelligence.domain.models.ToolExecution {
        val status = if (toolStatusRank(second.status) >= toolStatusRank(first.status)) second.status else first.status
        val result = when {
            !second.result.isNullOrBlank() && (first.result.isNullOrBlank() || second.result.length >= first.result.length) -> second.result
            else -> first.result
        }
        return first.copy(
            name = second.name.ifBlank { first.name },
            arguments = first.arguments + second.arguments,
            result = result,
            status = status,
            metadata = first.metadata + second.metadata,
            uiMetadata = second.uiMetadata ?: first.uiMetadata
        )
    }

    private fun toolStatusRank(status: ToolStatus): Int = when (status) {
        ToolStatus.PENDING -> 0
        ToolStatus.RUNNING -> 1
        ToolStatus.SUCCESS -> 2
        ToolStatus.ERROR -> 3
    }

    private fun mergeTextFragments(fragments: List<String>): String {
        val cleaned = fragments.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return ""
        val result = mutableListOf<String>()
        cleaned.forEach { candidate ->
            val candidateNorm = candidate.normalizedForMerge()
            if (result.any { existing -> existing.normalizedForMerge().contains(candidateNorm) }) return@forEach
            val removeContained = result.filter { existing -> candidateNorm.contains(existing.normalizedForMerge()) }
            result.removeAll(removeContained.toSet())
            result += candidate
        }
        return result.joinToString("\n\n")
    }

    private fun String.normalizedForMerge(): String = replace(Regex("\\s+"), " ").trim()

    private fun preserveMissingToolTimeline(local: UiMessage, incoming: UiMessage): UiMessage {
        if (local.role != MessageRole.ASSISTANT || incoming.role != MessageRole.ASSISTANT) return incoming

        val incomingToolIds = incoming.toolExecutions.map { it.toolCallId }.filter { it.isNotBlank() }.toSet()
        val missingTools = local.toolExecutions.filter { tool ->
            tool.toolCallId.isNotBlank() && tool.toolCallId !in incomingToolIds
        }
        if (missingTools.isEmpty()) return incoming

        val mergedToolExecutions = incoming.toolExecutions + missingTools
        val incomingStepToolIds = incoming.steps.mapNotNull { step ->
            (step as? MessageStep.ToolCall)?.execution?.toolCallId?.takeIf { it.isNotBlank() }
        }.toSet()
        val missingToolSteps = local.steps.mapNotNull { step ->
            val toolStep = step as? MessageStep.ToolCall ?: return@mapNotNull null
            val id = toolStep.execution.toolCallId
            toolStep.takeIf { id.isNotBlank() && id !in incomingStepToolIds }
        }
        if (missingToolSteps.isEmpty()) {
            return incoming.copy(toolExecutions = mergedToolExecutions)
        }

        val steps = incoming.steps.toMutableList()
        val insertAt = steps.indexOfLast { it is MessageStep.Text }.takeIf { it >= 0 } ?: steps.size
        steps.addAll(insertAt, missingToolSteps)
        return incoming.copy(toolExecutions = mergedToolExecutions, steps = steps)
    }

    private fun findRecentCompatibleLocal(local: List<UiMessage>, incoming: UiMessage): UiMessage? {
        val incomingToolIds = incoming.toolExecutions.map { it.toolCallId }.filter { it.isNotBlank() }.toSet()
        return local.asReversed().firstOrNull { candidate ->
            if (candidate.role != incoming.role) return@firstOrNull false
            if (incomingToolIds.isNotEmpty()) {
                candidate.toolExecutions.any { it.toolCallId in incomingToolIds }
            } else {
                candidate.content.isNotBlank() && incoming.content.isNotBlank() &&
                    candidate.content.take(160) == incoming.content.take(160)
            }
        }
    }

    private fun stableRangeKey(message: UiMessage): String? {
        val start = message.metadata["startStepIndex"]?.takeIf { it.isNotBlank() }
        val end = message.metadata["endStepIndex"]?.takeIf { it.isNotBlank() }
        if (start == null && end == null) return null
        return "${message.role.name}:$start:$end"
    }

    private fun mergeMinInt(first: Map<String, String>, second: Map<String, String>, key: String): Int? {
        val values = listOfNotNull(first[key]?.toIntOrNull(), second[key]?.toIntOrNull())
        return values.minOrNull()
    }

    private fun mergeMaxInt(first: Map<String, String>, second: Map<String, String>, key: String): Int? {
        val values = listOfNotNull(first[key]?.toIntOrNull(), second[key]?.toIntOrNull())
        return values.maxOrNull()
    }

    private fun earliestTimestampString(
        first: Map<String, String>,
        second: Map<String, String>,
        vararg keys: String
    ): String? = timestampCandidates(first, second, *keys).minByOrNull { it.second }?.first

    private fun latestTimestampString(
        first: Map<String, String>,
        second: Map<String, String>,
        vararg keys: String
    ): String? = timestampCandidates(first, second, *keys).maxByOrNull { it.second }?.first

    private fun timestampCandidates(
        first: Map<String, String>,
        second: Map<String, String>,
        vararg keys: String
    ): List<Pair<String, Long>> {
        return (first.asSequence() + second.asSequence())
            .filter { it.key in keys && it.value.isNotBlank() }
            .mapNotNull { entry -> parseTimestampMillis(entry.value)?.let { entry.value to it } }
            .toList()
    }
}
