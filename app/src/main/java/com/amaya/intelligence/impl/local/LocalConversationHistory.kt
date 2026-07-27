package com.amaya.intelligence.impl.local

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.*
import org.json.JSONObject

private const val COMPRESSED_SESSION_CONTEXT_PREFIX = "[COMPRESSED SESSION CONTEXT]"
internal const val AUTO_COMPACTED_CONTEXT_PREFIX = "[AUTO-COMPACTED ACTIVE CONTEXT]"

/**
 * Close out a turn that was still running when the process died.
 *
 * Returns null when the list holds no stalled turn, so callers can tell "nothing to do" apart from
 * "recovered" and leave the stored column untouched.
 */
internal fun markInterruptedTurn(messages: List<UiMessage>): List<UiMessage>? {
    val index = messages.indexOfLast { message ->
        message.role == MessageRole.ASSISTANT &&
            message.metadata["turnStatus"].isNullOrBlank() &&
            (message.isThinking || message.toolExecutions.any { it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING })
    }
    if (index < 0) return null
    fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
        tool.copy(
            status = ToolStatus.ERROR,
            result = tool.result ?: "Interrupted when the app process stopped",
            metadata = tool.metadata + mapOf("approvalRequired" to "false", "approvalState" to "cancelled")
        )
    } else tool
    val interrupted = messages[index].let { message ->
        message.copy(
            isThinking = false,
            toolExecutions = message.toolExecutions.map(::stop),
            steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = stop(it.execution)) else it },
            metadata = message.metadata + mapOf(
                "turnStatus" to "interrupted",
                "completedAt" to System.currentTimeMillis().toString(),
                "retryable" to "true"
            )
        )
    }
    return messages.toMutableList().also { it[index] = interrupted }
}

/** Above this, the stored model context is carrying more tool payload than it can ever replay. */
private const val MAX_STORED_TOOL_PAYLOAD_CHARS = 2_000_000
private const val KEEP_VERBATIM_TAIL_MESSAGES = 12
private const val STORED_TOOL_RESULT_MAX_CHARS = 4_000

/**
 * Bound the stored model context by shrinking old tool payloads, never by removing messages.
 *
 * The term that actually grows without limit is tool-result bytes — a DOM dump or a file read can be
 * hundreds of kilobytes and is kept verbatim forever. Removing whole messages would be the obvious
 * fix and is the wrong one: `ledgerCoverage` is an index into this list, so deleting entries
 * silently closes the compaction gate, and anything removed past the ledger's boundary would have no
 * record anywhere. Rewriting bodies in place keeps every index, every tool_call/tool_result pairing
 * and every message; only text the model has long stopped receiving is replaced, and it is replaced
 * with a line that says so. The visible transcript is a separate column and keeps the full text.
 */
internal fun digestOldToolPayloads(
    context: List<UiMessage>,
    maxTotalChars: Int = MAX_STORED_TOOL_PAYLOAD_CHARS,
    keepNewest: Int = KEEP_VERBATIM_TAIL_MESSAGES
): List<UiMessage> {
    // Every tool result is stored three times — toolExecutions, steps, and canonicalHistory — and
    // all three are serialized. Counting and trimming only one would leave the ceiling unenforced.
    fun trim(execution: ToolExecution): ToolExecution {
        val result = execution.result
        return if (result == null || !needsTrimming(result)) execution
        else execution.copy(result = storedToolDigest(execution.name, result))
    }

    val totalPayload = context.sumOf { message ->
        message.toolExecutions.sumOf { it.result?.length ?: 0 } +
            message.steps.filterIsInstance<MessageStep.ToolCall>().sumOf { it.execution.result?.length ?: 0 } +
            message.canonicalHistory.sumOf { it.length }
    }
    if (totalPayload <= maxTotalChars) return context

    val cutoff = (context.size - keepNewest).coerceAtLeast(0)
    return context.mapIndexed { index, message ->
        if (index >= cutoff) return@mapIndexed message
        val executions = message.toolExecutions.map(::trim)
        val steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = trim(it.execution)) else it }
        val canonical = message.canonicalHistory.map(::digestCanonicalToolResult)
        if (executions == message.toolExecutions && steps == message.steps && canonical == message.canonicalHistory) {
            message
        } else {
            message.copy(toolExecutions = executions, steps = steps, canonicalHistory = canonical)
        }
    }
}

private const val STORED_TRIM_MARKER = "result trimmed in stored context after"
private const val STORED_TRIM_SUFFIX = "chars; full text remains in the transcript]"

/**
 * Idempotent: an already-trimmed body must not be re-cut, which would shred its own marker.
 *
 * Anchored to the end of the string rather than searched for anywhere in it — a `read_file` or
 * `browser` result can legitimately quote this marker, and a substring test would exempt that
 * payload from the ceiling forever.
 */
private fun needsTrimming(result: String): Boolean =
    result.length > STORED_TOOL_RESULT_MAX_CHARS && !result.trimEnd().endsWith(STORED_TRIM_SUFFIX)

private fun storedToolDigest(toolName: String, result: String): String =
    result.take(STORED_TOOL_RESULT_MAX_CHARS).trimEnd() +
        "\n… [$toolName $STORED_TRIM_MARKER ${result.length} $STORED_TRIM_SUFFIX"

private fun digestCanonicalToolResult(entry: String): String {
    val json = runCatching { JSONObject(entry) }.getOrNull() ?: return entry
    if (json.optString("kind") != "tool_result") return entry
    val result = json.optString("result")
    if (!needsTrimming(result)) return entry
    return json.put("result", storedToolDigest(json.optString("name").ifBlank { "tool" }, result)).toString()
}

internal fun contextAfterHistoryClear(context: List<UiMessage>, deleteContext: Boolean): List<UiMessage> =
    if (deleteContext) emptyList() else context

/**
 * The compaction record carried in the model context.
 *
 * The two sources are tagged apart on purpose: automatic compaction replaces only its own record,
 * so a summary the user asked for with `/compact` is never overwritten by the host.
 */
internal fun compressedSessionContext(summary: String, auto: Boolean = false): List<UiMessage> = listOf(
    UiMessage(
        role = MessageRole.SYSTEM,
        content = "${if (auto) AUTO_COMPACTED_CONTEXT_PREFIX else COMPRESSED_SESSION_CONTEXT_PREFIX}\n${summary.trim()}",
        metadata = mapOf("compressed" to "true", "compactionSource" to if (auto) "auto" else "manual")
    )
)

// Extension to map domain to repository model
internal fun UiMessage.toChatMessages(): List<ChatMessage> {
    if (role == MessageRole.ASSISTANT && canonicalHistory.isNotEmpty()) {
        canonicalHistoryToChatMessages(canonicalHistory).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val calls = toolExecutions.map { execution ->
        com.amaya.intelligence.data.remote.api.ToolCallMessage(
            id = execution.toolCallId,
            name = execution.name,
            arguments = execution.arguments,
            metadata = execution.metadata.filterKeys { it == "thoughtSignature" }
        )
    }
    val message = ChatMessage(
        role = role,
        // Blank must become null: an empty string is rendered as an empty text content block, which
        // Anthropic rejects outright. An assistant turn that was pure tool calls, or that was
        // stopped before any prose, legitimately has no text.
        content = content.takeIf { it.isNotBlank() },
        images = attachments.filter { it.mimeType.startsWith("image/") }.map {
            com.amaya.intelligence.data.remote.api.ChatImage(it.dataBase64, it.mimeType, it.fileName)
        },
        toolCalls = calls.takeIf { it.isNotEmpty() },
        responseItems = responseItems
    )
    // An assistant turn that produced nothing at all — a turn that failed before the first delta —
    // must not be replayed. It has no text, no tool call and no image, so every provider sees an
    // empty content block and rejects the request; dropping it here also heals conversations
    // already stored in that state.
    if (role == MessageRole.ASSISTANT && message.content == null && calls.isEmpty() &&
        message.images.isEmpty() && responseItems.isEmpty()
    ) return emptyList()
    if (role != MessageRole.ASSISTANT || calls.isEmpty()) return listOf(message)
    return buildList {
        add(message)
        toolExecutions.forEach { execution ->
            // Every advertised tool call needs a matching result. A turn that died between
            // ToolCallStart and ToolCallResult leaves the execution pending, and replaying that as
            // a bare tool_call orphans the id — which every provider rejects on the next request.
            add(ChatMessage(
                role = MessageRole.TOOL,
                toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                    toolCallId = execution.toolCallId,
                    content = execution.result ?: UNFINISHED_TOOL_RESULT,
                    isError = execution.status == ToolStatus.ERROR || execution.result == null,
                    metadata = execution.metadata.filterKeys { it == "thoughtSignature" } +
                        ("toolName" to execution.name)
                )
            ))
        }
    }
}

internal const val UNFINISHED_TOOL_RESULT =
    "[no result: the turn ended before this tool returned]"

/**
 * Commit a run of streamed assistant text to the canonical model history as one entry.
 *
 * Text is buffered on the turn and flushed here whenever a tool call, tool result, response item, or
 * the end of the turn establishes an ordering boundary — so the canonical list holds one entry per
 * run of prose rather than one per stream delta.
 */
internal fun List<String>.appendCanonicalText(chunk: String?): List<String> {
    if (chunk.isNullOrEmpty()) return this
    return this + JSONObject().put("kind", "assistant_text").put("text", chunk).toString()
}

internal fun canonicalHistoryToChatMessages(history: List<String>): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    val text = StringBuilder()
    val calls = mutableListOf<com.amaya.intelligence.data.remote.api.ToolCallMessage>()
    val responseItems = mutableListOf<String>()

    fun flushAssistant() {
        if (text.isEmpty() && calls.isEmpty() && responseItems.isEmpty()) return
        messages += ChatMessage(
            role = MessageRole.ASSISTANT,
            content = text.toString().takeIf { it.isNotBlank() },
            toolCalls = calls.toList().takeIf { it.isNotEmpty() },
            responseItems = responseItems.toList()
        )
        text.clear()
        calls.clear()
        responseItems.clear()
    }

    history.forEach { raw ->
        val item = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
        when (item.optString("kind")) {
            "assistant_text" -> text.append(item.optString("text"))
            "response_item" -> item.optJSONObject("item")?.let { responseItem ->
                responseItems += responseItem.toString()
            }
            "assistant_tool_call" -> calls += com.amaya.intelligence.data.remote.api.ToolCallMessage(
                id = item.optString("id"),
                name = item.optString("name"),
                arguments = item.optJSONObject("arguments")?.toAnyMap().orEmpty(),
                metadata = item.optJSONObject("metadata")?.toStringMap().orEmpty()
            )
            "tool_result" -> {
                flushAssistant()
                messages += ChatMessage(
                    role = MessageRole.TOOL,
                    toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                        toolCallId = item.optString("id"),
                        content = item.optString("result"),
                        isError = item.optBoolean("isError"),
                        metadata = mapOf("toolName" to item.optString("name"))
                    )
                )
            }
        }
    }
    flushAssistant()
    return messages.withSyntheticToolResults()
}

/**
 * Guarantee every advertised tool call has a matching result.
 *
 * A turn that died between the call and its result — a provider error, a dropped stream, a killed
 * process — persists a `tool_call` with nothing answering it, and every provider rejects the next
 * request in that conversation. Repairing it here also heals conversations that were already stored
 * in that state.
 */
internal fun List<ChatMessage>.withSyntheticToolResults(): List<ChatMessage> {
    val answered = mapNotNull { it.toolResult?.toolCallId }.toSet()
    if (none { message -> message.toolCalls.orEmpty().any { it.id !in answered } }) return this
    return flatMap { message ->
        val unanswered = message.toolCalls.orEmpty().filter { it.id !in answered }
        if (unanswered.isEmpty()) listOf(message) else listOf(message) + unanswered.map { call ->
            ChatMessage(
                role = MessageRole.TOOL,
                toolResult = com.amaya.intelligence.data.remote.api.ToolResultMessage(
                    toolCallId = call.id,
                    content = UNFINISHED_TOOL_RESULT,
                    isError = true,
                    metadata = mapOf("toolName" to call.name)
                )
            )
        }
    }
}

private fun JSONObject.toAnyMap(): Map<String, Any?> = buildMap {
    keys().forEach { key -> put(key, opt(key).takeUnless { it == JSONObject.NULL }) }
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, optString(key, "")) }
}
