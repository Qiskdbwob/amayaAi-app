package com.amaya.intelligence.impl.local.chat

import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.domain.models.MessageStep
import com.amaya.intelligence.domain.models.SubagentExecution
import com.amaya.intelligence.domain.models.appendText
import com.amaya.intelligence.domain.models.appendThinking
import com.amaya.intelligence.domain.models.finishThinking
import com.amaya.intelligence.domain.models.ToolExecution
import com.amaya.intelligence.domain.models.ToolStatus
import com.amaya.intelligence.domain.models.UiMessage
import com.amaya.intelligence.impl.local.appendCanonicalText
import org.json.JSONObject

/** Pure message projection for local agent events. Runtime side effects stay in the service. */
internal object LocalAgentEventReducer {
    fun text(message: UiMessage, delta: String): UiMessage = message.copy(
        content = message.content + delta,
        isThinking = false,
        steps = message.steps.finishThinking().appendText(delta)
    )

    fun thinking(message: UiMessage, delta: String): UiMessage = message.appendThinkingStep(delta)

    fun toolCall(message: UiMessage, event: AgentEvent.ToolCallStart, execution: ToolExecution, pendingText: String?): UiMessage {
        val canonicalCall = JSONObject()
            .put("kind", "assistant_tool_call")
            .put("id", event.toolCallId)
            .put("name", event.name)
            .put("arguments", JSONObject(event.arguments))
            .put("metadata", JSONObject(event.metadata))
            .toString()
        return message.finishThinkingState().copy(
            toolExecutions = message.toolExecutions + execution,
            steps = message.steps.finishThinking() + MessageStep.ToolCall(execution = execution),
            canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalCall
        )
    }

    fun toolResult(message: UiMessage, event: AgentEvent.ToolCallResult, pendingText: String?): UiMessage {
        fun complete(tool: ToolExecution) = if (tool.toolCallId == event.toolCallId) {
            tool.copy(
                result = event.result,
                status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS
            )
        } else tool

        val canonicalResult = JSONObject()
            .put("kind", "tool_result")
            .put("id", event.toolCallId)
            .put("name", event.toolName)
            .put("result", event.result)
            .put("isError", event.isError)
            .toString()
        return message.finishThinkingState().copy(
            toolExecutions = message.toolExecutions.map(::complete),
            steps = message.steps.finishThinking().map {
                if (it is MessageStep.ToolCall) it.copy(execution = complete(it.execution)) else it
            },
            canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalResult
        )
    }

    fun responseItem(message: UiMessage, event: AgentEvent.ResponseItem, pendingText: String?): UiMessage {
        if (event.json in message.responseItems) {
            return message.copy(canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText))
        }
        return message.copy(
            responseItems = message.responseItems + event.json,
            canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + JSONObject()
                .put("kind", "response_item")
                .put("item", JSONObject(event.json))
                .toString()
        )
    }

    fun stopTools(message: UiMessage, reason: String): UiMessage {
        fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
            tool.copy(status = ToolStatus.ERROR, result = tool.result ?: reason)
        } else tool
        return message.copy(
            toolExecutions = message.toolExecutions.map(::stop),
            steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = stop(it.execution)) else it }
        )
    }

    fun finish(message: UiMessage, status: String, pendingText: String?, now: Long): UiMessage {
        val steps = message.steps.finishThinking(now)
        return message.copy(
            canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText),
            isThinking = false,
            thinkingDurationMs = message.thinkingDurationMs
                ?: (steps.lastOrNull { it is MessageStep.Thinking } as? MessageStep.Thinking)?.durationMs,
            steps = steps,
            metadata = message.metadata + mapOf("turnStatus" to status, "completedAt" to now.toString())
        )
    }

    fun subagent(message: UiMessage, event: AgentEvent.SubagentUpdate): UiMessage {
        fun child(tool: ToolExecution): ToolExecution {
            if (tool.toolCallId != event.parentToolCallId) return tool
            val execution = SubagentExecution(
                event.index,
                event.taskName,
                event.prompt,
                event.result,
                if (!event.isComplete) ToolStatus.RUNNING
                else if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS
            )
            return tool.copy(children = (tool.children.filterNot { it.index == event.index } + execution).sortedBy { it.index })
        }
        return message.copy(
            toolExecutions = message.toolExecutions.map(::child),
            steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = child(it.execution)) else it }
        )
    }

    private fun UiMessage.appendThinkingStep(delta: String): UiMessage {
        val updatedSteps = steps.appendThinking(delta)
        val current = updatedSteps.last() as MessageStep.Thinking
        return copy(
            thinking = current.text,
            isThinking = true,
            thinkingStartedAt = current.startedAt,
            steps = updatedSteps
        )
    }

    private fun UiMessage.finishThinkingState(now: Long = System.currentTimeMillis()): UiMessage {
        val finishedSteps = steps.finishThinking(now)
        val duration = thinkingStartedAt?.let { (now - it).coerceAtLeast(0L) }
        return copy(
            isThinking = false,
            thinkingDurationMs = thinkingDurationMs ?: duration,
            steps = finishedSteps
        )
    }
}
