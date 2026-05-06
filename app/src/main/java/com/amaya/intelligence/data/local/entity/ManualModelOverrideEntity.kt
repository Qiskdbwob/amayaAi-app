package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "manual_model_overrides",
    primaryKeys = ["provider_id", "model_id"]
)
data class ManualModelOverrideEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "display_name_override")
    val displayNameOverride: String? = null,

    @ColumnInfo(name = "input_price_override")
    val inputPriceOverride: Double? = null,

    @ColumnInfo(name = "output_price_override")
    val outputPriceOverride: Double? = null,

    @ColumnInfo(name = "context_window_override")
    val contextWindowOverride: Int? = null,

    @ColumnInfo(name = "capabilities_override_csv")
    val capabilitiesOverrideCsv: String? = null,

    @ColumnInfo(name = "metadata_override_json")
    val metadataOverrideJson: String = "{}"
)
