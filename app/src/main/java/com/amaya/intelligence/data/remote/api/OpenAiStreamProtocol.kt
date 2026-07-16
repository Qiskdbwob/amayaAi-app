package com.amaya.intelligence.data.remote.api

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
