package com.amaya.intelligence.impl.common.mappers

import com.amaya.intelligence.data.remote.api.ActiveModelSelection

internal data class ModelSelectionKey(val connectionId: String, val modelId: String) {
    val value: String get() = "model|$connectionId|$modelId"

    fun toSelection(): ActiveModelSelection = ActiveModelSelection(connectionId, modelId)

    companion object {
        fun parse(value: String): ModelSelectionKey? {
            val parts = value.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != "model" || parts[1].isBlank() || parts[2].isBlank()) return null
            return ModelSelectionKey(parts[1], parts[2])
        }
    }
}
