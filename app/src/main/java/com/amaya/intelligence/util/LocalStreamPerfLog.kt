package com.amaya.intelligence.util

import android.os.SystemClock
import android.util.Log
import com.amaya.intelligence.BuildConfig

/**
 * Local streaming profiler. Filter with:
 * adb logcat -s LocalStreamPerf
 *
 * Debug builds only — the callers sit on hot paths (every inbound delta, every markdown
 * parse), so this must never cost anything in release.
 */
object LocalStreamPerfLog {
    private const val TAG = "LocalStreamPerf"
    val ENABLED = BuildConfig.DEBUG
    private const val SUMMARY_INTERVAL_MS = 1_000L
    private const val SLOW_RENDER_MS = 12L

    private var turnId = 0
    private var turnStartedAt = 0L
    private var lastSummaryAt = 0L
    private var lastUiFlushAt = 0L
    private var inboundChunks = 0
    private var inboundChars = 0
    private var uiFlushes = 0
    private var uiChars = 0
    private var chatListCompositions = 0
    private var markdownParses = 0
    private var markdownParseMs = 0L
    private var slowMarkdownParses = 0
    private var firstTokenLogged = false
    private var compactionStartedAt = 0L

    fun startTurn(messageChars: Int, historyMessages: Int, model: String?) {
        if (!ENABLED) return
        val now = SystemClock.elapsedRealtime()
        turnId += 1
        turnStartedAt = now
        lastSummaryAt = now
        lastUiFlushAt = now
        inboundChunks = 0
        inboundChars = 0
        uiFlushes = 0
        uiChars = 0
        chatListCompositions = 0
        markdownParses = 0
        markdownParseMs = 0L
        slowMarkdownParses = 0
        firstTokenLogged = false
        compactionStartedAt = 0L
        Log.i(TAG, "turn#$turnId START messageChars=$messageChars historyMessages=$historyMessages model=${model.orEmpty()}")
    }

    /** First token reached the UI. This is the number the user actually feels. */
    fun onFirstToken() {
        if (!ENABLED || firstTokenLogged) return
        firstTokenLogged = true
        Log.i(TAG, "turn#$turnId FIRST_TOKEN elapsedMs=${elapsed()}")
    }

    fun onCompactionStart(evictedMessages: Int, evictedTokens: Int) {
        if (!ENABLED) return
        compactionStartedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "turn#$turnId COMPACT_START elapsedMs=${elapsed()} evictedMessages=$evictedMessages evictedTokens=$evictedTokens")
    }

    fun onCompactionEnd(usedFallback: Boolean, reclaimedTokens: Int) {
        if (!ENABLED) return
        val took = if (compactionStartedAt == 0L) -1L else SystemClock.elapsedRealtime() - compactionStartedAt
        compactionStartedAt = 0L
        Log.i(TAG, "turn#$turnId COMPACT_END elapsedMs=${elapsed()} compactionMs=$took fallback=$usedFallback reclaimedTokens=$reclaimedTokens")
    }

    fun onInboundDelta(chars: Int, bufferChars: Int) {
        if (!ENABLED) return
        inboundChunks += 1
        inboundChars += chars
        maybeSummary("inbound", bufferChars = bufferChars)
    }

    fun onUiFlush(chunkChars: Int, totalAssistantChars: Int, messages: Int, steps: Int, updateMs: Long) {
        if (!ENABLED) return
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastUiFlushAt
        lastUiFlushAt = now
        uiFlushes += 1
        uiChars += chunkChars
        Log.d(
            TAG,
            "turn#$turnId UI_FLUSH chunkChars=$chunkChars totalAssistantChars=$totalAssistantChars messages=$messages steps=$steps updateMs=$updateMs gapMs=$gap thread=${Thread.currentThread().name}"
        )
        maybeSummary("ui_flush")
    }

    fun onChatListComposed(messages: Int, lastChars: Int, visibleItems: Int, totalItems: Int, isStreaming: Boolean) {
        if (!ENABLED || !isStreaming) return
        chatListCompositions += 1
        maybeSummary(
            reason = "compose",
            extra = "messages=$messages lastChars=$lastChars visibleItems=$visibleItems totalItems=$totalItems"
        )
    }

    fun onMarkdownParsed(textChars: Int, blocks: Int, elapsedMs: Long, compact: Boolean) {
        if (!ENABLED) return
        markdownParses += 1
        markdownParseMs += elapsedMs
        if (elapsedMs >= SLOW_RENDER_MS) {
            slowMarkdownParses += 1
            Log.w(TAG, "turn#$turnId MD_SLOW chars=$textChars blocks=$blocks parseMs=$elapsedMs compact=$compact")
        }
        maybeSummary("markdown")
    }

    fun endTurn(reason: String, totalMessages: Int, assistantChars: Int) {
        if (!ENABLED) return
        Log.i(
            TAG,
            "turn#$turnId END reason=$reason elapsedMs=${elapsed()} inboundChunks=$inboundChunks inboundChars=$inboundChars uiFlushes=$uiFlushes uiChars=$uiChars chatCompositions=$chatListCompositions markdownParses=$markdownParses markdownMs=$markdownParseMs slowMarkdown=$slowMarkdownParses totalMessages=$totalMessages assistantChars=$assistantChars"
        )
    }

    private fun maybeSummary(reason: String, bufferChars: Int = -1, extra: String = "") {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSummaryAt < SUMMARY_INTERVAL_MS) return
        lastSummaryAt = now
        Log.i(
            TAG,
            "turn#$turnId SUMMARY reason=$reason elapsedMs=${elapsed()} inboundChunks=$inboundChunks inboundChars=$inboundChars bufferChars=$bufferChars uiFlushes=$uiFlushes uiChars=$uiChars chatCompositions=$chatListCompositions markdownParses=$markdownParses markdownMs=$markdownParseMs slowMarkdown=$slowMarkdownParses $extra"
        )
    }

    private fun elapsed(): Long = if (turnStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - turnStartedAt
}
