package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "model_catalog",
    indices = [Index(value = ["provider_id", "model_id"], unique = true)]
)
data class ModelCatalogEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "capabilities_csv")
    val capabilitiesCsv: String = "",

    @ColumnInfo(name = "input_price_per_million_tokens")
    val inputPricePerMillionTokens: Double? = null,

    @ColumnInfo(name = "output_price_per_million_tokens")
    val outputPricePerMillionTokens: Double? = null,

    @ColumnInfo(name = "context_window")
    val contextWindow: Int? = null,

    @ColumnInfo(name = "max_output_tokens")
    val maxOutputTokens: Int? = null,

    @ColumnInfo(name = "release_date")
    val releaseDate: String? = null,

    @ColumnInfo(name = "knowledge_cutoff")
    val knowledgeCutoff: String? = null,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "{}"
)
