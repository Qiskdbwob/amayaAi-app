package com.amaya.intelligence.impl.bridge.windows

import android.util.Log

/**
 * Thin logging facade for the Windows Bridge client. Centralized so that every log
 * line carries the same tag and so we can swap the sink later (e.g. route to a
 * structured audit log) without touching callers.
 *
 * Rules:
 *  - Never log raw payloads. Log type/tool/id only.
 *  - Never log tokens or full auth metadata.
 */
internal object WindowsBridgeLogger {
    private const val TAG = "WinBridgeClient"

    fun connectRequested(host: String, port: Int) {
        Log.i(TAG, "connect requested host=$host port=$port")
    }

    fun connected(host: String, port: Int) {
        Log.i(TAG, "connected host=$host port=$port")
    }

    fun disconnected(code: Int?, reason: String?, remote: Boolean) {
        Log.w(TAG, "disconnected code=${code ?: -1} reason=${reason ?: "-"} remote=$remote")
    }

    fun reconnectAttempt(attempt: Int, delayMs: Long) {
        Log.i(TAG, "reconnect attempt=$attempt delayMs=$delayMs")
    }

    fun inbound(type: String, id: String?, tool: String?, seq: Long) {
        Log.v(TAG, "inbound type=$type id=${id ?: "-"} tool=${tool ?: "-"} seq=$seq")
    }

    fun outbound(type: String, id: String?, tool: String?, seq: Long, queued: Boolean) {
        Log.v(TAG, "outbound type=$type id=${id ?: "-"} tool=${tool ?: "-"} seq=$seq queued=$queued")
    }

    fun parseError(message: String) {
        Log.w(TAG, "parse error: $message")
    }

    fun protocolError(message: String) {
        Log.w(TAG, "protocol error: $message")
    }

    fun sessionClosed(sessionId: String?, reason: String?) {
        Log.i(TAG, "session closed sessionId=${sessionId ?: "-"} reason=${reason ?: "-"}")
    }

    fun seqGap(previous: Long, received: Long) {
        Log.w(TAG, "seq gap previous=$previous received=$received")
    }

    fun duplicateSeq(seq: Long) {
        Log.v(TAG, "duplicate seq=$seq ignored")
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
