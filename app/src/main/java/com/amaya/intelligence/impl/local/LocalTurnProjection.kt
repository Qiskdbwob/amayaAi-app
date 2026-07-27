package com.amaya.intelligence.impl.local

import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager

import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.entity.ConversationEntity
import com.amaya.intelligence.data.repository.AiRepository
import com.amaya.intelligence.data.repository.AgentEvent
import com.amaya.intelligence.data.repository.SessionMemoryRepository

import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.impl.local.tools.LocalToolMapper
import com.amaya.intelligence.di.ApplicationScope
import com.amaya.intelligence.util.LocalStreamPerfLog
import com.amaya.intelligence.tools.ConfirmationRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.amaya.intelligence.tools.SubagentResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context


internal fun LocalIntelligenceService.updateTurnMessage(turn: LocalIntelligenceService.LocalTurn, transform: (UiMessage) -> UiMessage) {
        val context = turn.state.contextMessages.toMutableList()
        val visible = turn.state.messages.toMutableList()
        var index = turn.assistantMessageId?.let { id -> context.indexOfLast { it.id == id } } ?: -1
        if (index < 0) {
            val message = UiMessage(role = MessageRole.ASSISTANT, content = "", metadata = mapOf("source" to "local"))
            turn.assistantMessageId = message.id
            context += message
            if (visible.isNotEmpty()) visible += message
            index = context.lastIndex
        }
        val updated = transform(context[index])
        context[index] = updated
        turn.assistantMessageId?.let { id ->
            visible.indexOfLast { it.id == id }.takeIf { it >= 0 }?.let { visibleIndex ->
                visible[visibleIndex] = updated
            }
        }
        turn.state = turn.state.copy(messages = visible, contextMessages = context)
    }

internal fun LocalIntelligenceService.markActiveTurnToolsStopped(turn: LocalIntelligenceService.LocalTurn, reason: String = "Stopped by user") {
        updateTurnMessage(turn) { message ->
            fun stop(tool: ToolExecution) = if (tool.status == ToolStatus.RUNNING || tool.status == ToolStatus.PENDING) {
                tool.copy(status = ToolStatus.ERROR, result = tool.result ?: reason)
            } else tool
            message.copy(
                toolExecutions = message.toolExecutions.map(::stop),
                steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = stop(it.execution)) else it }
            )
        }
    }

internal fun LocalIntelligenceService.finalizeTurnThinking(turn: LocalIntelligenceService.LocalTurn, status: String) {
        val now = System.currentTimeMillis()
        // Commit any assistant prose still buffered, so the persisted model context ends with the
        // text the user actually saw.
        val pendingText = turn.drainCanonicalText()
        LocalStreamPerfLog.endTurn(
            reason = status,
            totalMessages = turn.state.messages.size,
            assistantChars = turn.state.messages.lastOrNull()?.content?.length ?: 0
        )
        updateTurnMessage(turn) { message ->
            val steps = message.steps.finishThinking(now)
            message.copy(
                canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText),
                isThinking = false,
                thinkingDurationMs = message.thinkingDurationMs
                    ?: (steps.lastOrNull { it is MessageStep.Thinking } as? MessageStep.Thinking)?.durationMs,
                steps = steps,
                metadata = message.metadata + mapOf("turnStatus" to status, "completedAt" to now.toString())
            )
        }
    }

internal fun LocalIntelligenceService.handleTurnEvent(turn: LocalIntelligenceService.LocalTurn, event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                LocalStreamPerfLog.onFirstToken()
                if (currentConversationId == turn.conversationId && turn.state.assistantMode == AssistantMode.AGENT) {
                    browserSessionManager.onAssistantTextDelta(event.text)
                }
                turn.pendingCanonicalText.append(event.text)
                updateTurnMessage(turn) { message ->
                    val steps = message.steps.finishThinking().appendText(event.text)
                    message.copy(
                        content = message.content + event.text,
                        isThinking = false,
                        steps = steps
                    )
                }
                publishTurn(turn, "Streaming", event.text.takeLast(120))
            }
            is AgentEvent.ThinkingDelta -> {
                updateTurnMessage(turn) { message -> message.appendThinkingStep(event.text) }
                publishTurn(turn, "Thinking", event.text.takeLast(120))
            }
            is AgentEvent.ToolCallStart -> {
                val displayName = LocalToolMapper.mapDisplayToolName(event.name, event.arguments)
                if (displayName == "delegate_agent") turn.delegationActive = true
                val execution = ToolExecution(event.toolCallId, displayName, LocalToolMapper.mapToolArgs(event.name, event.arguments), status = ToolStatus.RUNNING, metadata = event.metadata + ("source" to "local"), uiMetadata = LocalToolMapper.getUiMetadata(event.name, event.arguments))
                // Record the provider's own tool name and arguments, not the display-mapped ones:
                // this list is replayed as model context on later turns.
                val canonicalCall = JSONObject()
                    .put("kind", "assistant_tool_call")
                    .put("id", event.toolCallId)
                    .put("name", event.name)
                    .put("arguments", JSONObject(event.arguments))
                    .put("metadata", JSONObject(event.metadata))
                    .toString()
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) { message ->
                    val steps = message.steps.finishThinking()
                    message.finishThinking().copy(
                        toolExecutions = message.toolExecutions + execution,
                        steps = steps + MessageStep.ToolCall(execution = execution),
                        canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalCall
                    )
                }
                publishTurn(turn, "Tools: ${LocalToolMapper.displayLabel(event.name, event.arguments)}", toolEventDetail(event.name, event.arguments), urgent = true)
            }
            is AgentEvent.ToolCallResult -> {
                if (LocalToolMapper.mapToolName(event.toolName) == "delegate_agent") turn.delegationActive = false
                fun complete(tool: ToolExecution) = if (tool.toolCallId == event.toolCallId) tool.copy(result = event.result, status = if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS) else tool
                val canonicalResult = JSONObject()
                    .put("kind", "tool_result")
                    .put("id", event.toolCallId)
                    .put("name", event.toolName)
                    .put("result", event.result)
                    .put("isError", event.isError)
                    .toString()
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) { message -> message.finishThinking().copy(
                    toolExecutions = message.toolExecutions.map(::complete),
                    steps = message.steps.finishThinking().map { if (it is MessageStep.ToolCall) it.copy(execution = complete(it.execution)) else it },
                    canonicalHistory = message.canonicalHistory.appendCanonicalText(pendingText) + canonicalResult
                ) }
                publishTurn(turn, if (event.isError) "Tool failed" else "Tool completed", event.result.takeLast(120), urgent = true)
            }
            is AgentEvent.ResponseItem -> {
                val pendingText = turn.drainCanonicalText()
                updateTurnMessage(turn) {
                    // The buffered prose is committed even when the response item is a duplicate —
                    // draining it and then returning the message unchanged would lose that run.
                    if (event.json in it.responseItems) {
                        it.copy(canonicalHistory = it.canonicalHistory.appendCanonicalText(pendingText))
                    } else it.copy(
                        responseItems = it.responseItems + event.json,
                        canonicalHistory = it.canonicalHistory.appendCanonicalText(pendingText) + JSONObject()
                            .put("kind", "response_item")
                            .put("item", JSONObject(event.json))
                            .toString()
                    )
                }
            }
            is AgentEvent.Usage -> turn.state = turn.state.copy(totalInputTokens = turn.state.totalInputTokens + event.inputTokens, totalOutputTokens = turn.state.totalOutputTokens + event.outputTokens)
            is AgentEvent.Incomplete -> {
                val stopped = event.reason == "Stopped"
                // Repaired on every terminal path, not just an explicit stop: a tool left RUNNING
                // replays as a tool_call with no result and makes the next request invalid.
                markActiveTurnToolsStopped(turn, if (stopped) "Stopped by user" else "Interrupted: ${event.reason}")
                finalizeTurnThinking(turn, if (stopped) "cancelled" else "incomplete")
                turn.state = turn.state.copy(error = if (stopped) null else event.reason, isLoading = false, isStreaming = false, isAutoCompacting = false)
                publishTurn(turn, if (stopped) "Stopped" else "Incomplete", event.reason, urgent = true)
            }
            is AgentEvent.Error -> {
                markActiveTurnToolsStopped(turn, "Interrupted: ${event.message}")
                finalizeTurnThinking(turn, "failed")
                turn.state = turn.state.copy(error = event.message, isLoading = false, isStreaming = false, isAutoCompacting = false)
                publishTurn(turn, "Failed", event.message, urgent = true)
            }
            is AgentEvent.Done -> {
                finalizeTurnThinking(turn, "completed")
                turn.state = turn.state.copy(isLoading = false, isStreaming = false, isAutoCompacting = false)
                publishTurn(turn, "Completed", turn.state.messages.lastOrNull()?.content.orEmpty().takeLast(120), urgent = true)
            }
            is AgentEvent.SubagentUpdate -> {
                turn.delegateTotal = maxOf(turn.delegateTotal, event.index + 1)
                turn.activeDelegateName = if (event.isComplete) null else event.taskName
                if (event.isComplete) turn.delegateCompleted = minOf(turn.delegateTotal, turn.delegateCompleted + 1)
                fun child(tool: ToolExecution): ToolExecution {
                    if (tool.toolCallId != event.parentToolCallId) return tool
                    val execution = SubagentExecution(event.index, event.taskName, event.prompt, event.result, if (!event.isComplete) ToolStatus.RUNNING else if (event.isError) ToolStatus.ERROR else ToolStatus.SUCCESS)
                    return tool.copy(children = (tool.children.filterNot { it.index == event.index } + execution).sortedBy { it.index })
                }
                updateTurnMessage(turn) { message -> message.copy(toolExecutions = message.toolExecutions.map(::child), steps = message.steps.map { if (it is MessageStep.ToolCall) it.copy(execution = child(it.execution)) else it }) }
                publishTurn(turn, "Delegating", "${turn.delegateCompleted}/${turn.delegateTotal} · ${event.taskName}", urgent = true)
            }
            is AgentEvent.NewIteration -> publishTurn(turn, "Continuing", "Processing tool results")
            is AgentEvent.Compacting -> {
                LocalStreamPerfLog.onCompactionStart(event.evictedMessages, event.evictedTokens)
                turn.state = turn.state.copy(isAutoCompacting = true)
                publishTurn(turn, "Compacting", "Compressing ${event.evictedMessages} older messages", urgent = true)
            }
            is AgentEvent.Compacted -> {
                LocalStreamPerfLog.onCompactionEnd(event.usedFallback, event.reclaimedTokens)
                // Fold the ledger into the model context so the next turn inherits it instead of
                // re-deriving it. Only the previous automatic record is replaced — a summary the
                // user asked for with /compact survives. The visible transcript is left untouched.
                // Match on the content prefix as well as the tag, so conversations stored before the
                // two sources were told apart cannot accumulate one ledger per turn.
                // The legacy content match is restricted to actual compaction records. Matching on
                // content alone would delete any real message whose text happens to start with the
                // marker — including a user request quoting one.
                val retained = turn.state.contextMessages.filterNot {
                    it.metadata["compactionSource"] == "auto" ||
                        (it.role == MessageRole.SYSTEM &&
                            it.metadata["compressed"] == "true" &&
                            it.content.startsWith(AUTO_COMPACTED_CONTEXT_PREFIX))
                }
                turn.state = turn.state.copy(
                    isAutoCompacting = false,
                    contextMessages = compressedSessionContext(event.ledger, auto = true) + retained
                )
                publishTurn(
                    turn,
                    "Streaming",
                    if (event.usedFallback) "Context compacted locally" else "Context compacted",
                    urgent = true
                )
            }
        }
    }

