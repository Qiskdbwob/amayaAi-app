package com.amaya.intelligence.impl.local.browser

internal object BrowserRuntimeLimits {
    const val DEFAULT_NAVIGATION_TIMEOUT_MS = 30_000L
    const val DEFAULT_BRIDGE_STALE_TIMEOUT_MS = 2_000L
    const val DEFAULT_EVALUATION_TIMEOUT_MS = 10_000L
    const val MAX_EVALUATION_TIMEOUT_MS = 10_000L
    const val MAX_HTML_CHARS = 200_000
    const val MAX_DOM_CHARS = 50_000
    const val MAX_EVALUATION_RESULT_CHARS = 65_536
    const val INTERACTION_SETTLE_TIMEOUT_MS = 1_400L
    const val HEADLESS_WIDTH_PX = 1_080
    const val HEADLESS_HEIGHT_PX = 1_920
    const val COPY_BUFFER_BYTES = 8_192
}
