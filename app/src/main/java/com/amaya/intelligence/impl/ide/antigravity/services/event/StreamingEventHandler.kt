package com.amaya.intelligence.impl.ide.antigravity.services.event

import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.*
import com.amaya.intelligence.impl.ide.antigravity.AntigravityProtocol
import com.amaya.intelligence.impl.ide.antigravity.client.*
import com.amaya.intelligence.impl.ide.antigravity.event.*
import com.amaya.intelligence.impl.ide.antigravity.services.mapper.AntigravityMessageMapper
import com.amaya.intelligence.impl.ide.antigravity.services.mapper.AntigravityTimelineMetadata
import com.amaya.intelligence.impl.ide.antigravity.services.streaming.StreamingStateManager

/**
 * Handles streaming events (text delta, AI thinking, stream done) from Antigravity.
 */
class StreamingEventHandler(
    private val stateManager: StreamingStateManager,
    private val onUiStateUpdate: ((ChatUiState) -> ChatUiState) -> Unit
) {
    companion object {
        private const val TEXT_UI_EMIT_INTERVAL_MS = 120L
        private const val TEXT_UI_EMIT_MIN_CHARS = 160
    }

    private var lastTextUiEmitAt = 0L
    private var lastTextUiEmitLength = 0
    private var pendingTextContent: String? = null
    private var pendingTextStepIndex: String? = null

    fun handleTextDelta(event: RemoteEvent.TextDelta, currentConversationId: String?): Boolean {
        if (!isForActiveConversation(event.conversationId, currentConversationId)) {
            com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerDrop("TextDelta", event.conversationId, currentConversationId)
            return false
        }

        stateManager.setPhase(StreamingStateManager.StreamPhase.TEXT)
        val textLength = if (event.stepIndex != null) {
            pendingTextContent = event.text
            event.text.length
        } else {
            pendingTextContent = null
            stateManager.mergeStreamingSegmentInPlace(event.text)
        }
        pendingTextStepIndex = event.stepIndex

        val now = System.currentTimeMillis()
        val shouldEmit = now - lastTextUiEmitAt >= TEXT_UI_EMIT_INTERVAL_MS ||
            kotlin.math.abs(textLength - lastTextUiEmitLength) >= TEXT_UI_EMIT_MIN_CHARS
        if (!shouldEmit) {
            com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("TEXT_DELTA_BUFFER", "len=$textLength step=${event.stepIndex ?: "-"} pendingEmit=false")
            return true
        }

        com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("TEXT_DELTA_EMIT", "len=$textLength step=${event.stepIndex ?: "-"}")
        emitPendingText(forceNewBubbleAfterThinking = true)
        return true
    }

    fun handleAiThinking(event: RemoteEvent.AiThinking, currentConversationId: String?): Boolean {
        if (!isForActiveConversation(event.conversationId, currentConversationId)) {
            com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerDrop("AiThinking", event.conversationId, currentConversationId)
            return false
        }

        stateManager.setPhase(StreamingStateManager.StreamPhase.THINKING)

        if (event.stepIndex.isNotBlank() && event.stepIndex != stateManager.currentStepIndex) {
            stateManager.clearThinking()
            stateManager.setStepIndex(event.stepIndex)
        }

        if (event.text.isNotBlank()) {
            stateManager.setThinking(event.text)
        }

        val currentThinking = stateManager.currentThinking
        onUiStateUpdate { state ->
            state.copy(messages = upsertThinkingToolOnLastAssistant(
                messages = state.messages,
                thinkingText = currentThinking,
                isRunning = thinkingIsRunning(currentThinking, event.isRunning),
                stepIndex = stateManager.currentStepIndex ?: ""
            ))
        }
        return true
    }

    fun handleStreamDone(event: RemoteEvent.StreamDone, currentConversationId: String?): Boolean {
        if (!isForActiveConversation(event.conversationId, currentConversationId)) {
            com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerDrop("StreamDone", event.conversationId, currentConversationId)
            return false
        }
        com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("STREAM_DONE", "beforePendingText=${pendingTextContent?.length ?: stateManager.currentText.length} step=${pendingTextStepIndex ?: "-"}")
        emitPendingText(forceNewBubbleAfterThinking = true)
        onUiStateUpdate { state ->
            val mergedTurn = mergeTrailingAssistantTurn(state.messages)
            val finalizedMsgs = markLastAssistantCompleted(finalizeRunningThinkingOnLastAssistant(mergedTurn))
            state.copy(isStreaming = false, isLoading = false, messages = finalizedMsgs)
        }
        pendingTextContent = null
        pendingTextStepIndex = null
        lastTextUiEmitAt = 0L
        lastTextUiEmitLength = 0
        stateManager.clearAll()
        return true
    }

    private fun emitPendingText(forceNewBubbleAfterThinking: Boolean) {
        val content = pendingTextContent ?: stateManager.currentText
        if (content.isBlank()) return
        val stepIndex = pendingTextStepIndex
        onUiStateUpdate { state ->
            val lastAssistant = state.messages.lastOrNull()
            val needsNewBubble = forceNewBubbleAfterThinking &&
                lastAssistant?.role == MessageRole.ASSISTANT &&
                lastAssistant.content.isBlank() &&
                hasSyntheticThinkingTool(lastAssistant)

            val baseMessages = if (needsNewBubble) {
                ensureAssistantMessage(finalizeRunningThinkingOnLastAssistant(state.messages), force = true)
            } else {
                ensureAssistantMessage(state.messages, force = false)
            }

            state.copy(messages = updateLastAssistantContent(baseMessages, content, null, false, null, stepIndex))
        }
        lastTextUiEmitAt = System.currentTimeMillis()
        lastTextUiEmitLength = content.length
    }

    private fun markLastAssistantCompleted(messages: List<UiMessage>): List<UiMessage> {
        val idx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (idx == -1) return messages
        val msg = messages[idx]
        if (msg.metadata["completedAt"] != null) return messages
        val nowMs = System.currentTimeMillis()
        val durationMs = msg.thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
        return messages.toMutableList().apply {
            this[idx] = msg.copy(
                metadata = msg.metadata + ("completedAt" to nowMs.toString()),
                thinkingDurationMs = msg.thinkingDurationMs ?: durationMs
            )
        }
    }

    private fun mergeTrailingAssistantTurn(messages: List<UiMessage>): List<UiMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val tailStart = lastUserIndex + 1
        if (tailStart < 0 || tailStart >= messages.size) return messages

        val tail = messages.drop(tailStart)
        if (tail.count { it.role == MessageRole.ASSISTANT } <= 1) return messages
        if (tail.any { it.role != MessageRole.ASSISTANT }) return messages

        val merged = tail.reduce { acc, msg ->
            val combinedContent = when {
                acc.content.isBlank() -> msg.content
                msg.content.isBlank() -> acc.content
                else -> "${acc.content}\n\n${msg.content}"
            }
            acc.copy(
                content = combinedContent,
                intent = acc.intent ?: msg.intent,
                timestamp = minOf(acc.timestamp, msg.timestamp),
                toolExecutions = acc.toolExecutions + msg.toolExecutions,
                steps = acc.steps + msg.steps,
                attachments = acc.attachments + msg.attachments,
                metadata = AntigravityTimelineMetadata.mergeMetadata(acc.metadata, msg.metadata)
            )
        }

        return messages.take(tailStart) + merged
    }

    private fun thinkingIsRunning(text: String, fallback: Boolean): Boolean {
        val open = Regex("<think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        val close = Regex("</think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        return if (open != null || close != null) open != null && (close == null || open > close) else fallback
    }

    private fun isForActiveConversation(eventConversationId: String?, currentConversationId: String?): Boolean {
        if (eventConversationId.isNullOrBlank()) return true
        if (currentConversationId.isNullOrBlank()) return true
        return eventConversationId == currentConversationId
    }

    private fun hasSyntheticThinkingTool(message: UiMessage): Boolean {
        return message.toolExecutions.any {
            it.metadata[AntigravityProtocol.ToolMarkers.THINKING_TOOL_META_KEY] == "true" ||
                it.name.equals(AntigravityProtocol.ToolMarkers.THINKING_TOOL_NAME, ignoreCase = true)
        }
    }

    private fun finalizeRunningThinkingOnLastAssistant(messages: List<UiMessage>): List<UiMessage> {
        val idx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (idx == -1) return messages

        val msg = messages[idx]
        if (!hasSyntheticThinkingTool(msg)) {
            // No synthetic thinking tool in the timeline, but the message
            // can still be marked isThinking=true (e.g. from AiThinking
            // events). Forcing isThinking=false here would break providers
            // that drive reasoning purely via UiMessage.thinking without a
            // matching tool call — local LLM/DeepSeek/etc. rely on the
            // isThinking flag to drive the ThinkingCard shimmer until the
            // agent stream itself ends. So only flip when we *actually*
            // observed the thinking tool reach a terminal status.
            return messages
        }

        val updatedTools = msg.toolExecutions.map { tool ->
            if ((tool.metadata[AntigravityProtocol.ToolMarkers.THINKING_TOOL_META_KEY] == "true" ||
                    tool.name.equals(AntigravityProtocol.ToolMarkers.THINKING_TOOL_NAME, ignoreCase = true)) &&
                tool.status == ToolStatus.RUNNING
            ) {
                tool.copy(status = ToolStatus.SUCCESS)
            } else {
                tool
            }
        }

        val updatedSteps = msg.steps.map { step ->
            if (step is MessageStep.ToolCall &&
                (step.execution.metadata[AntigravityProtocol.ToolMarkers.THINKING_TOOL_META_KEY] == "true" ||
                 step.execution.name.equals(AntigravityProtocol.ToolMarkers.THINKING_TOOL_NAME, ignoreCase = true)) &&
                step.execution.status == ToolStatus.RUNNING
            ) {
                step.copy(execution = step.execution.copy(status = ToolStatus.SUCCESS))
            } else step
        }
        val nowMs = System.currentTimeMillis()
        val durationMs = msg.thinkingStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }
        return messages.toMutableList().apply {
            this[idx] = msg.copy(
                toolExecutions = updatedTools,
                steps = updatedSteps,
                // The synthetic thinking tool reached a terminal status, so
                // the ThinkingCard must stop shimmering — otherwise the
                // reasoning block stays in "pending" until the chat stream
                // itself ends, which is exactly the race condition the UI
                // contract should avoid.
                isThinking = msg.isThinking && updatedTools.any {
                    it.name.equals(AntigravityProtocol.ToolMarkers.THINKING_TOOL_NAME, ignoreCase = true) &&
                        it.status == ToolStatus.RUNNING
                },
                thinkingDurationMs = msg.thinkingDurationMs ?: durationMs
            )
        }
    }

    private fun ensureAssistantMessage(messages: List<UiMessage>, force: Boolean): List<UiMessage> {
        val lastMsg = messages.lastOrNull()
        if (lastMsg?.role == MessageRole.ASSISTANT) return messages

        val hasAssistantPlaceholder = lastMsg?.role == MessageRole.ASSISTANT &&
            lastMsg.content.isBlank() &&
            lastMsg.thinking.isNullOrBlank() &&
            lastMsg.steps.isEmpty()

        if (!force && hasAssistantPlaceholder) return messages

        return messages + UiMessage(role = MessageRole.ASSISTANT, content = "")
    }

    private fun updateLastAssistantContent(
        messages: List<UiMessage>,
        content: String,
        thinking: String?,
        isThinking: Boolean,
        thinkingStartedAt: Long?,
        stepIndex: String? = null
    ): List<UiMessage> {
        val idx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (idx == -1) return messages

        val updated = messages.toMutableList()
        val msg = updated[idx]

        val updatedSteps = msg.steps.toMutableList()
        val targetId = if (stepIndex != null) "text-$stepIndex" else "text-legacy"
        val lastTextIdx = if (stepIndex != null) {
            updatedSteps.indexOfLast { it is MessageStep.Text && it.id == targetId }
        } else {
            updatedSteps.indexOfLast { it is MessageStep.Text }
        }

        if (lastTextIdx >= 0) {
            val lastText = updatedSteps[lastTextIdx] as MessageStep.Text
            updatedSteps[lastTextIdx] = lastText.copy(content = content)
        } else {
            // Retrieve default UUID logic but override if we have explicit id
            val newText = MessageStep.Text(content = content).let { if (stepIndex != null) it.copy(id = targetId) else it }
            updatedSteps.add(newText)
        }

        val mergedContent = if (stepIndex != null) {
            mergeTextFragments((listOf(msg.content) + updatedSteps.filterIsInstance<MessageStep.Text>().map { it.content }))
        } else {
            content
        }

        updated[idx] = msg.copy(
            content = mergedContent,
            thinking = thinking,
            isThinking = isThinking,
            thinkingStartedAt = thinkingStartedAt,
            steps = updatedSteps
        )
        return updated
    }

    private fun mergeTextFragments(fragments: List<String>): String {
        val cleaned = fragments
            .map { it.trim() }
            .filter { it.isNotBlank() }
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

    private fun upsertThinkingToolOnLastAssistant(
        messages: List<UiMessage>,
        thinkingText: String,
        isRunning: Boolean,
        stepIndex: String
    ): List<UiMessage> {
        val sanitized = thinkingText.trim()
        if (sanitized.isEmpty()) return messages

        val searchId = if (stepIndex.isNotBlank()) "thinking-$stepIndex" else null

        // Search backwards for the most appropriate thinking tool to update in-place
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role != MessageRole.ASSISTANT) continue

            val existingIdx = msg.toolExecutions.indexOfLast {
                (searchId != null && it.toolCallId == searchId) ||
                    (searchId == null && it.metadata[StreamingStateManager.THINKING_TOOL_META_KEY] == "true" && it.status == ToolStatus.RUNNING)
            }

            if (existingIdx >= 0) {
                val updatedTools = msg.toolExecutions.toMutableList()
                val thoughtTitle = AntigravityMessageMapper.extractThoughtTitle(sanitized)
                val updatedMeta = updatedTools[existingIdx].metadata.toMutableMap()
                if (thoughtTitle != null) {
                    updatedMeta["thoughtTitle"] = thoughtTitle
                }

                val updatedUiMeta = updatedTools[existingIdx].uiMetadata?.copy(
                    label = thoughtTitle ?: updatedTools[existingIdx].uiMetadata?.label ?: "Thinking"
                )

                val updatedTool = updatedTools[existingIdx].copy(
                    result = sanitized,
                    status = if (isRunning) ToolStatus.RUNNING else ToolStatus.SUCCESS,
                    metadata = updatedMeta,
                    uiMetadata = updatedUiMeta
                )
                updatedTools[existingIdx] = updatedTool

                val updatedSteps = msg.steps.map { step ->
                    if (step is MessageStep.ToolCall && step.execution.toolCallId == updatedTool.toolCallId) {
                        step.copy(execution = updatedTool)
                    } else step
                }

                return messages.toMutableList().apply { this[i] = msg.copy(toolExecutions = updatedTools, steps = updatedSteps) }
            }
        }

        // Not found, add to last assistant message
        val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx >= 0) {
            val msg = messages[lastAssistantIdx]
            val updatedTools = msg.toolExecutions.toMutableList()
            val thinkingToolId = searchId ?: "thinking:${msg.id}:${System.currentTimeMillis()}"

            val thoughtTitle = AntigravityMessageMapper.extractThoughtTitle(sanitized)
            val meta = mutableMapOf(
                AntigravityProtocol.ToolMarkers.THINKING_TOOL_META_KEY to "true",
                "source" to "remote"
            )
            if (thoughtTitle != null) {
                meta["thoughtTitle"] = thoughtTitle
            }

            val newTool = ToolExecution(
                toolCallId = thinkingToolId,
                name = StreamingStateManager.THINKING_TOOL_NAME,
                arguments = mapOf("source" to "ai_thinking_stream"),
                result = sanitized,
                status = if (isRunning) ToolStatus.RUNNING else ToolStatus.SUCCESS,
                metadata = meta,
                uiMetadata = ToolUiMetadata(
                    category = ToolCategory.TASK_MANAGEMENT,
                    label = thoughtTitle ?: "Thinking",
                    actionIcon = ToolInfoIcon.LIGHTBULB,
                    targetIcon = ToolInfoIcon.GENERATE,
                    badges = listOf("THINKING")
                )
            )
            updatedTools.add(newTool)
            val updatedSteps = msg.steps + MessageStep.ToolCall(execution = newTool)

            return messages.toMutableList().apply { this[lastAssistantIdx] = msg.copy(toolExecutions = updatedTools, steps = updatedSteps) }
        }

        return messages
    }
}
