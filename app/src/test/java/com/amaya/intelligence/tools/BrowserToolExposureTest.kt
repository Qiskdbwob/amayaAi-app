package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserToolExposureTest {
    @Test
    fun `browser toolset exposes only canonical parent`() {
        assertEquals(setOf("browser"), BrowserUseToolset.MODEL_TOOL_NAMES)
        assertFalse(BrowserUseToolset.MODEL_TOOL_NAMES.contains("evaluate_script"))
    }
}
