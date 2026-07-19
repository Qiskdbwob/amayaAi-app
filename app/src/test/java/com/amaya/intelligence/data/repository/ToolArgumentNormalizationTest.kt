package com.amaya.intelligence.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgumentNormalizationTest {
    @Test
    fun `integer strings and whole doubles normalize`() {
        assertEquals(30_000L, normalizeIntegerArgument("30000"))
        assertEquals(5, normalizeIntegerArgument(5.0))
    }

    @Test
    fun `invalid integer values remain invalid`() {
        assertEquals("3.5", normalizeIntegerArgument("3.5"))
        assertEquals("many", normalizeIntegerArgument("many"))
        assertEquals(3.5, normalizeIntegerArgument(3.5))
    }
}
