package com.amaya.intelligence.impl.ide.antigravity.services.streaming

import com.amaya.intelligence.impl.ide.antigravity.AntigravityProtocol

/**
 * Manages streaming state for Antigravity intelligence service.
 * Tracks current phase, accumulated text, and thinking content during streaming.
 */
class StreamingStateManager {
    
    enum class StreamPhase {
        NONE, THINKING, TEXT, TOOL
    }
    
    private val streamingText = StringBuilder()
    private val streamingThinking = StringBuilder()
    private var streamingMessageId: String? = null
    private var currentStreamPhase: StreamPhase = StreamPhase.NONE
    private var lastStreamingStepIndex: String? = null
    private var lastStreamingActivityAt: Long = 0L
    
    val currentText: String get() = streamingText.toString()
    val currentThinking: String get() = streamingThinking.toString()
    val currentPhase: StreamPhase get() = currentStreamPhase
    val currentStepIndex: String? get() = lastStreamingStepIndex
    
    fun setPhase(phase: StreamPhase) {
        if (phase == StreamPhase.TOOL && currentStreamPhase != StreamPhase.TOOL) {
            streamingText.clear()
        }
        currentStreamPhase = phase
        if (phase != StreamPhase.NONE) markStreamingActivity()
    }

    fun markStreamingActivity() {
        lastStreamingActivityAt = System.currentTimeMillis()
    }

    fun hasRecentStreamingActivity(graceMs: Long = TRANSIENT_IDLE_GRACE_MS): Boolean {
        val last = lastStreamingActivityAt
        if (last <= 0L) return false
        return System.currentTimeMillis() - last <= graceMs
    }
    
    fun setStepIndex(index: String?) {
        lastStreamingStepIndex = index
    }
    
    fun appendText(text: String) {
        streamingText.append(text)
    }
    
    fun setText(text: String) {
        streamingText.clear()
        streamingText.append(text)
    }
    
    fun setThinking(text: String) {
        streamingThinking.clear()
        streamingThinking.append(text)
    }
    
    fun clearThinking() {
        streamingThinking.clear()
    }
    
    fun clearText() {
        streamingText.clear()
    }
    
    fun clearAll() {
        streamingText.clear()
        streamingThinking.clear()
        streamingMessageId = null
        currentStreamPhase = StreamPhase.NONE
        lastStreamingStepIndex = null
        lastStreamingActivityAt = 0L
    }
    
    fun mergeStreamingSegment(incoming: String): String {
        mergeStreamingSegmentInPlace(incoming)
        return streamingText.toString()
    }

    fun mergeStreamingSegmentInPlace(incoming: String): Int {
        if (incoming.isBlank()) return streamingText.length
        if (streamingText.isEmpty()) {
            streamingText.append(incoming)
            return streamingText.length
        }

        // If the incoming text contains the entirety of the current text (pure replacement delta mode).
        // Compare directly against the builder to avoid copying the full stream on every tiny chunk.
        if (incoming.length >= streamingText.length && incomingStartsWithCurrent(incoming)) {
            streamingText.clear()
            streamingText.append(incoming)
            return streamingText.length
        }

        // Find if incoming overlaps exactly with the end of current.
        var overlapLength = 0
        val maxPossibleOverlap = minOf(streamingText.length, incoming.length)
        for (i in maxPossibleOverlap downTo 1) {
            if (currentEndsWithIncomingPrefix(incoming, i)) {
                overlapLength = i
                break
            }
        }

        streamingText.append(incoming, overlapLength, incoming.length)
        return streamingText.length
    }

    private fun incomingStartsWithCurrent(incoming: String): Boolean {
        for (i in 0 until streamingText.length) {
            if (incoming[i] != streamingText[i]) return false
        }
        return true
    }

    private fun currentEndsWithIncomingPrefix(incoming: String, prefixLength: Int): Boolean {
        val currentStart = streamingText.length - prefixLength
        for (i in 0 until prefixLength) {
            if (streamingText[currentStart + i] != incoming[i]) return false
        }
        return true
    }
    
    companion object {
        const val THINKING_TOOL_NAME = AntigravityProtocol.ToolMarkers.THINKING_TOOL_NAME
        const val THINKING_TOOL_META_KEY = AntigravityProtocol.ToolMarkers.THINKING_TOOL_META_KEY
        const val TRANSIENT_IDLE_GRACE_MS = 60_000L
    }
}
