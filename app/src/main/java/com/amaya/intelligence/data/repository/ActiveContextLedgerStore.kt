package com.amaya.intelligence.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime cache of the active [TaskLedger] per session.
 *
 * Compaction state used to live in a local `var` inside the chat flow, so it died the moment the
 * turn finished and every following turn re-paid a full summarization round-trip from scratch.
 * Holding it here lets a turn reuse a ledger a previous turn — or the post-turn warm-up — already
 * produced, and [Entry.coveredThrough] lets the next turn summarize only what is newly evicted.
 *
 * Writes are guarded two ways because the post-turn warm-up runs on an application-scoped coroutine
 * that can outlive its turn: an [epoch] stamp so a warm-up cannot resurrect a ledger that was
 * invalidated while it was in flight, and a monotonic [Entry.coveredThrough] so a stale writer
 * cannot roll back a newer, wider ledger.
 */
@Singleton
class ActiveContextLedgerStore @Inject constructor() {

    internal data class Entry(
        val ledger: TaskLedger,
        /** Number of evicted messages this ledger already accounts for. */
        val coveredThrough: Int,
        val epoch: Int,
        val updatedAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val epochs = ConcurrentHashMap<String, Int>()

    internal fun get(sessionId: String): Entry? = entries[sessionId]

    internal fun epoch(sessionId: String): Int = epochs[sessionId] ?: 0

    /** Returns true when the write was accepted. */
    internal fun put(sessionId: String, ledger: TaskLedger, coveredThrough: Int, epoch: Int): Boolean {
        if (sessionId.isBlank()) return false
        var accepted = false
        entries.compute(sessionId) { _, existing ->
            // The epoch is re-read inside the atomic block: checking it beforehand leaves a window
            // in which an invalidate lands and an in-flight warm-up resurrects the stale ledger.
            when {
                epoch != epoch(sessionId) -> existing
                existing != null && existing.coveredThrough > coveredThrough -> existing
                else -> {
                    accepted = true
                    Entry(ledger, coveredThrough, epoch, System.currentTimeMillis())
                }
            }
        }
        if (entries.size > MAX_SESSIONS) {
            entries.entries
                .sortedBy { it.value.updatedAt }
                .take(entries.size - MAX_SESSIONS)
                .forEach { entries.remove(it.key) }
        }
        return accepted
    }

    /** Drops the cached ledger and retires the epoch, so in-flight writers cannot restore it. */
    fun invalidate(sessionId: String) {
        if (sessionId.isBlank()) return
        epochs.compute(sessionId) { _, current -> (current ?: 0) + 1 }
        entries.remove(sessionId)
        if (epochs.size > MAX_EPOCHS) {
            // Epochs must outlive their entry (that is the point), but not forever.
            epochs.keys.filterNot(entries::containsKey).take(epochs.size - MAX_SESSIONS).forEach(epochs::remove)
        }
    }

    private companion object {
        const val MAX_SESSIONS = 32
        const val MAX_EPOCHS = 256
    }
}
