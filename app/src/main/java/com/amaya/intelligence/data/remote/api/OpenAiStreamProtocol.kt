package com.amaya.intelligence.data.remote.api

internal class OpenAiDeltaCoalescer {
    private enum class Kind { TEXT, THINKING }

    private var kind: Kind? = null
    private val buffer = StringBuilder()

    /** Appends a delta; returns the previous kind when event order requires flushing it. */
    fun append(response: ChatResponse): ChatResponse? {
        val nextKind = when (response) {
            is ChatResponse.TextDelta -> Kind.TEXT
            is ChatResponse.ThinkingDelta -> Kind.THINKING
            else -> error("Only delta responses can be coalesced")
        }
        val previous = if (kind != null && kind != nextKind) flush() else null
        kind = nextKind
        buffer.append(when (response) {
            is ChatResponse.TextDelta -> response.text
            is ChatResponse.ThinkingDelta -> response.text
            else -> error("Only delta responses can be coalesced")
        })
        return previous
    }

    fun flush(): ChatResponse? {
        val previousKind = kind ?: return null
        val text = buffer.toString()
        kind = null
        buffer.clear()
        return when (previousKind) {
            Kind.TEXT -> ChatResponse.TextDelta(text)
            Kind.THINKING -> ChatResponse.ThinkingDelta(text)
        }
    }

    fun clear() {
        kind = null
        buffer.clear()
    }
}

internal data class CompletedOpenAiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** Correlates Chat Completions tool deltas strictly by protocol index. */
internal class OpenAiToolCallAccumulator {
    private data class Pending(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private val pending = sortedMapOf<Int, Pending>()

    fun append(index: Int?, id: String?, name: String?, argumentsDelta: String?) {
        require(index != null && index >= 0) { "Missing or invalid tool_call.index" }
        val call = pending.getOrPut(index) { Pending() }
        id?.let {
            require(call.id.isBlank() || call.id == it) { "Tool call ID changed at index $index" }
            call.id = it
        }
        name?.let {
            require(call.name.isBlank() || call.name == it) { "Tool name changed at index $index" }
            call.name = it
        }
        call.arguments.append(argumentsDelta.orEmpty())
    }

    fun complete(): List<CompletedOpenAiToolCall> = pending.map { (index, call) ->
        require(call.id.isNotBlank()) { "Missing tool call ID at index $index" }
        require(call.name.isNotBlank()) { "Missing tool name at index $index" }
        CompletedOpenAiToolCall(call.id, call.name, call.arguments.toString())
    }

    fun clear() = pending.clear()
    fun isNotEmpty(): Boolean = pending.isNotEmpty()
}

internal fun normalizeOpenAiFinishReason(reason: String?): String? = when (reason?.trim()?.lowercase()) {
    "toolcall", "tool_call", "tool_calls" -> "tool_calls"
    "stop" -> "stop"
    else -> reason?.trim()?.lowercase()
}

internal fun isOpenAiCompletedFinishReason(reason: String?): Boolean =
    normalizeOpenAiFinishReason(reason) in setOf("stop", "tool_calls")

internal enum class OpenAiTerminalState { OPEN, COMPLETED, INCOMPLETE, FAILED }

internal class OpenAiTerminalGuard {
    var state: OpenAiTerminalState = OpenAiTerminalState.OPEN
        private set

    fun complete() = transition(OpenAiTerminalState.COMPLETED)
    fun incomplete() = transition(OpenAiTerminalState.INCOMPLETE)
    fun fail() = transition(OpenAiTerminalState.FAILED)
    fun eofIsFailure(): Boolean = state == OpenAiTerminalState.OPEN

    private fun transition(next: OpenAiTerminalState) {
        require(state == OpenAiTerminalState.OPEN) { "Duplicate terminal event: $state then $next" }
        state = next
    }
}
