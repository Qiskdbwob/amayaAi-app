package com.amaya.intelligence.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.amaya.intelligence.domain.models.AgentCapabilityProfile
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.impl.local.browser.BrowserActionCatalog
import org.junit.Test

class BrowserToolExposureTest {
    @Test
    fun `browser toolset exposes only canonical parent`() {
        assertEquals(setOf("browser"), BrowserUseToolset.MODEL_TOOL_NAMES)
        assertFalse(BrowserUseToolset.MODEL_TOOL_NAMES.contains("evaluate_script"))
    }

    @Test
    fun `browser exposes text search and evaluate action`() {
        assertTrue(BrowserActionCatalog.names.contains("find_text"))
        assertTrue(BrowserActionCatalog.names.contains("evaluate"))
    }

    @Test
    fun `browser requires agent mode and enabled agent capability`() {
        assertFalse(assistantModeAllowsCapability("browser", AssistantMode.CHAT))
        assertFalse(assistantModeAllowsCapability("browser", AssistantMode.PROJECT))
        assertFalse(assistantModeAllowsCapability("browser", AssistantMode.AGENT, AgentCapabilityProfile(browser = false)))
        assertTrue(assistantModeAllowsCapability("browser", AssistantMode.AGENT, AgentCapabilityProfile(browser = true)))
    }
}
