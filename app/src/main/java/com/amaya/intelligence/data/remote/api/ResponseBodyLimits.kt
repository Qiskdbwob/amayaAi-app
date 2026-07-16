package com.amaya.intelligence.data.remote.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_REMOTE_BODY_BYTES = 2L * 1024 * 1024
internal const val MAX_ERROR_BODY_BYTES = 64L * 1024

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

internal fun ResponseBody.readUtf8Limited(maxBytes: Long): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val sink = Buffer()
    val source = source()
    var remaining = maxBytes + 1
    while (remaining > 0) {
        val read = source.read(sink, minOf(remaining, 8_192))
        if (read == -1L) break
        remaining -= read
    }
    require(sink.size <= maxBytes) { "Response body exceeds $maxBytes bytes" }
    return sink.readUtf8()
}
