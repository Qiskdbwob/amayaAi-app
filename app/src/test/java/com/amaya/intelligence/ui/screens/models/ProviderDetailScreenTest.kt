package com.amaya.intelligence.ui.screens.models

import com.amaya.intelligence.data.remote.api.ConfiguredModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderDetailScreenTest {
    @Test
    fun `merges discovered models while retaining saved configuration`() {
        val saved = ConfiguredModel(
            id = "model-b",
            displayName = "Saved model",
            maxOutputTokens = 1_024,
            enabled = false
        )

        val models = mergeConfiguredModels(
            savedModels = listOf(saved),
            refreshedModels = listOf(
                ConfiguredModel(id = "model-a", displayName = "A model"),
                ConfiguredModel(id = "model-b", displayName = "Provider model")
            )
        )

        assertEquals(listOf("model-a", "model-b"), models.map { it.id })
        assertEquals(saved, models.last())
    }
}
