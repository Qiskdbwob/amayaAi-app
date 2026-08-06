package com.amaya.intelligence.impl.local.browser

import kotlinx.coroutines.flow.update

internal fun BrowserConversationSession.onAssistantStreamingChanged(streaming: Boolean) {
        if (!streaming) flushAssistantStreamBuffer()
        if (streaming) {
            assistantStreamBuffer.clear()
            lastAssistantStreamUiEmitAt = 0L
            controller?.hideSoftKeyboard()
        }
        this._uiState.update { state ->
            val finalizedLogs = if (!streaming) {
                state.logs.map { log ->
                    if (log.toolName.equals("thinking", ignoreCase = true) && log.status == "running") {
                        log.copy(status = "completed")
                    } else log
                }
            } else state.logs
            state.copy(
                logs = finalizedLogs,
                isAssistantStreaming = streaming,
                browserAccessActive = if (streaming) state.browserAccessActive else false,
                agentTouchX = if (streaming) state.agentTouchX else null,
                agentTouchY = if (streaming) state.agentTouchY else null,
                // Clear stream text when a new turn starts so the pill
                // only shows text from the current response, not old text.
                assistantStreamText = if (streaming) "" else state.assistantStreamText,
                assistantStreamUpdatedAt = if (streaming) 0L else state.assistantStreamUpdatedAt
            )
        }
    }
internal fun BrowserConversationSession.onAssistantTextDelta(delta: String) {
        if (delta.isEmpty()) return
        assistantStreamBuffer.append(delta)
        flushAssistantStreamBuffer(System.currentTimeMillis())
    }
internal fun BrowserConversationSession.flushAssistantStreamBuffer(now: Long = System.currentTimeMillis()) {
        if (assistantStreamBuffer.isEmpty()) return
        val chunk = assistantStreamBuffer.toString()
        assistantStreamBuffer.clear()
        lastAssistantStreamUiEmitAt = now
        this._uiState.update { state ->
            state.copy(
                assistantStreamText = (state.assistantStreamText + chunk).takeLast(4000),
                assistantStreamUpdatedAt = now,
                isAssistantStreaming = true
            )
        }
    }
internal fun BrowserConversationSession.hasOpenThinkingTag(text: String): Boolean {
        val open = Regex("<think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        val close = Regex("</think>", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.range?.first
        return open != null && (close == null || open > close)
    }
