package com.amaya.intelligence.impl.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class ToolConfirmationRegistry {
    private data class Pending(val turnId: Long, val decision: CompletableDeferred<Boolean>)

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    suspend fun await(callId: String, turnId: Long, onRegistered: () -> Unit): Boolean {
        val entry = Pending(turnId, CompletableDeferred())
        synchronized(lock) {
            if (pending.putIfAbsent(callId, entry) != null) return false
        }
        return try {
            onRegistered()
            entry.decision.await()
        } finally {
            synchronized(lock) { pending.remove(callId, entry) }
        }
    }

    fun resolve(callId: String, turnId: Long, confirmed: Boolean, onResolved: () -> Unit): Boolean =
        synchronized(lock) {
            val entry = pending[callId] ?: return@synchronized false
            if (entry.turnId != turnId || !entry.decision.isActive) return@synchronized false
            pending.remove(callId, entry)
            onResolved()
            entry.decision.complete(confirmed)
        }

    fun cancelAll() {
        synchronized(lock) {
            val cancellation = CancellationException("Tool confirmation cancelled")
            pending.values.forEach { it.decision.cancel(cancellation) }
            pending.clear()
        }
    }

    internal fun size(): Int = synchronized(lock) { pending.size }
}
