package com.amaya.intelligence.util

import android.util.Log
import com.amaya.intelligence.BuildConfig
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.tools.ToolResult
import java.util.concurrent.atomic.AtomicLong

/** Debug-only tool lifecycle trace. Payloads are summarized; secrets and file contents never log. */
object ToolDebugLog {
    private const val TAG = "AmayaTool"
    private val sequence = AtomicLong()

    fun start(callId: String?, name: String, arguments: Map<String, Any?>, conversationId: String?, ownerId: String?, agentId: Long?, mode: AssistantMode) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "START seq=${sequence.incrementAndGet()} call=${callId.orEmpty()} tool=$name conversation=${conversationId.orEmpty()} owner=${ownerId.orEmpty()} agent=${agentId ?: ""} mode=$mode args=${summarize(arguments)}")
    }

    fun finish(callId: String?, name: String, result: ToolResult, startedAtNs: Long) {
        if (!BuildConfig.DEBUG) return
        val outcome = when (result) {
            is ToolResult.Success -> "success outputChars=${result.output.length}"
            is ToolResult.Deferred -> "deferred taskId=${result.taskId} outputChars=${result.output.length}"
            is ToolResult.Error -> "error type=${result.errorType} recoverable=${result.recoverable} message=${result.message.take(160)}"
            is ToolResult.RequiresConfirmation -> "approval reason=${result.reason.take(160)}"
        }
        Log.i(TAG, "END call=${callId.orEmpty()} tool=$name durationMs=${(System.nanoTime() - startedAtNs) / 1_000_000} $outcome")
    }

    fun cancel(callId: String?, name: String, startedAtNs: Long) {
        if (BuildConfig.DEBUG) Log.w(TAG, "CANCEL call=${callId.orEmpty()} tool=$name durationMs=${(System.nanoTime() - startedAtNs) / 1_000_000}")
    }

    fun crash(callId: String?, name: String, error: Throwable, startedAtNs: Long) {
        if (BuildConfig.DEBUG) Log.e(TAG, "CRASH call=${callId.orEmpty()} tool=$name durationMs=${(System.nanoTime() - startedAtNs) / 1_000_000} error=${error.message}", error)
    }

    private fun summarize(arguments: Map<String, Any?>): String = arguments.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        val safe = if (key.contains("token", true) || key.contains("password", true) || key.contains("secret", true) || key.contains("base64", true)) "<redacted>" else when (value) {
            is String -> if (key.contains("path", true) || value.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)) "chars=${value.length} value=<path>" else "chars=${value.length} value=${value.take(80).replace('\n', ' ')}"
            is Map<*, *> -> "objectKeys=${value.keys.size}"
            is List<*> -> "items=${value.size}"
            else -> value?.toString()?.take(80).orEmpty()
        }
        "$key=$safe"
    }
}

object StreamDebugLog {
    private const val TAG = "AmayaStream"
    private val sequence = AtomicLong()

    fun event(conversationId: Long?, turnId: Long?, event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "seq=${sequence.incrementAndGet()} conversation=${conversationId ?: ""} turn=${turnId ?: ""} event=$event${detail.takeIf(String::isNotBlank)?.let { " detail=${it.take(240)}" }.orEmpty()}")
    }
}
