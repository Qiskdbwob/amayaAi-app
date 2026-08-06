package com.amaya.intelligence.impl.local.browser

import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserEvaluatePolicyTest {
    @Test
    fun `local evaluate has no script allowlist`() {
        val source = java.io.File("src/main/java/com/amaya/intelligence/impl/local/browser/AndroidBrowserController.kt").readText()
        assertFalse(source.contains("BrowserEvaluatePolicy"))
        assertFalse(source.contains("blocked navigation"))
        assertFalse(source.contains("read-only DOM"))
    }
}
