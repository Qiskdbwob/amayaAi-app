package com.amaya.intelligence.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveContextLedgerStoreTest {

    private val store = ActiveContextLedgerStore()
    private fun ledger(state: String) = TaskLedger(goal = "ship the feature", lastState = state)

    @Test
    fun `a ledger is readable with the coverage it was written with`() {
        val epoch = store.epoch("s1")
        assertTrue(store.put("s1", ledger("first"), coveredThrough = 10, epoch = epoch))

        val entry = store.get("s1")!!
        assertEquals(10, entry.coveredThrough)
        assertEquals("first", entry.ledger.lastState)
    }

    /**
     * The post-turn warm-up is a read-modify-write over a snapshot. Without a monotonic guard it
     * could land after a newer turn and roll the ledger back to a narrower version.
     */
    @Test
    fun `a narrower write cannot roll back a wider ledger`() {
        val epoch = store.epoch("s1")
        store.put("s1", ledger("wide"), coveredThrough = 40, epoch = epoch)

        assertFalse(store.put("s1", ledger("stale"), coveredThrough = 12, epoch = epoch))
        assertEquals("wide", store.get("s1")!!.ledger.lastState)
        assertEquals(40, store.get("s1")!!.coveredThrough)
    }

    @Test
    fun `an equal-coverage write is accepted so a re-derived ledger can refresh itself`() {
        val epoch = store.epoch("s1")
        store.put("s1", ledger("old"), coveredThrough = 20, epoch = epoch)

        assertTrue(store.put("s1", ledger("new"), coveredThrough = 20, epoch = epoch))
        assertEquals("new", store.get("s1")!!.ledger.lastState)
    }

    /**
     * Clearing a conversation or compacting it manually must not be undone by a summarization the
     * previous turn launched and never awaited.
     */
    @Test
    fun `an in-flight write cannot resurrect an invalidated ledger`() {
        val epochAtLaunch = store.epoch("s1")
        store.put("s1", ledger("before"), coveredThrough = 5, epoch = epochAtLaunch)

        store.invalidate("s1")
        assertNull(store.get("s1"))

        // The warm-up finally lands, still holding the epoch it read when it started.
        assertFalse(store.put("s1", ledger("resurrected"), coveredThrough = 30, epoch = epochAtLaunch))
        assertNull(store.get("s1"))

        // A turn that starts after the invalidate reads the new epoch and can write again.
        assertTrue(store.put("s1", ledger("fresh"), coveredThrough = 1, epoch = store.epoch("s1")))
        assertEquals("fresh", store.get("s1")!!.ledger.lastState)
    }

    @Test
    fun `sessions are independent and blank ids are rejected`() {
        store.put("a", ledger("a"), coveredThrough = 1, epoch = store.epoch("a"))
        store.put("b", ledger("b"), coveredThrough = 1, epoch = store.epoch("b"))
        store.invalidate("a")

        assertNull(store.get("a"))
        assertEquals("b", store.get("b")!!.ledger.lastState)
        assertFalse(store.put("", ledger("x"), coveredThrough = 1, epoch = 0))
    }
}
