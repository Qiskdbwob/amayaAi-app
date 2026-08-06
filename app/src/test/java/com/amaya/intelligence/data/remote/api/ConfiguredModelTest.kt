package com.amaya.intelligence.data.remote.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfiguredModelTest {
    @Test
    fun acceptsBoundedTokenConfiguration() {
        val model = normalizeConfiguredModel(
            ConfiguredModel(
                id = " model ",
                displayName = "",
                contextWindowTokens = 32_768,
                maxInputTokens = 24_576,
                maxOutputTokens = 8_192
            )
        )

        assertEquals("model", model.id)
        assertEquals("model", model.displayName)
    }

    @Test
    fun rejectsTokenLimitsBeyondContextWindow() {
        assertFailsWith<IllegalArgumentException> {
            normalizeConfiguredModel(
                ConfiguredModel(
                    id = "model",
                    contextWindowTokens = 8_192,
                    maxInputTokens = 6_000,
                    maxOutputTokens = 4_000
                )
            )
        }
    }
}
