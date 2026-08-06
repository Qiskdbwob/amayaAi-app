package com.amaya.intelligence.ui.components.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun `formats seconds minutes and hours`() {
        assertEquals("0s", formatCompactDuration(0))
        assertEquals("1s", formatCompactDuration(1, minimumSeconds = 1))
        assertEquals("1m 5s", formatCompactDuration(65_000))
        assertEquals("2h 3m", formatCompactDuration(7_380_000))
    }
}
