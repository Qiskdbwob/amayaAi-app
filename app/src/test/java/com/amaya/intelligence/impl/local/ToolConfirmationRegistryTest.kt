package com.amaya.intelligence.impl.local

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolConfirmationRegistryTest {
    @Test
    fun `pending is registered before UI callback and resolves once`() = runTest {
        val registry = ToolConfirmationRegistry()
        val result = async {
            registry.await("call-1", 7) {
                assertEquals(1, registry.size())
            }
        }
        yield()

        assertTrue(registry.resolve("call-1", 7, true) {})
        assertFalse(registry.resolve("call-1", 7, false) {})
        assertTrue(result.await())
        assertEquals(0, registry.size())
    }

    @Test
    fun `stale turn cannot resolve current approval`() = runTest {
        val registry = ToolConfirmationRegistry()
        val result = async { registry.await("call-1", 8) {} }
        yield()

        assertFalse(registry.resolve("call-1", 7, true) {})
        assertTrue(registry.resolve("call-1", 8, false) {})
        assertFalse(result.await())
    }

    @Test
    fun `cancel all clears pending approval`() = runTest {
        val registry = ToolConfirmationRegistry()
        var resolvedUi = false
        val result = async { registry.await("call-1", 1) {} }
        yield()
        registry.cancelAll()

        assertFalse(registry.resolve("call-1", 1, true) { resolvedUi = true })
        assertFalse(resolvedUi)
        assertEquals(0, registry.size())
        assertTrue(result.isCancelled || runCatching { result.await() }.isFailure)
        result.cancelAndJoin()
    }
}
