package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "provider_model_availability",
    primaryKeys = ["provider_connection_id", "catalog_model_id"],
    indices = [Index("provider_connection_id"), Index("catalog_model_id")]
)
data class ProviderModelAvailabilityEntity(
    @ColumnInfo(name = "provider_connection_id")
    val providerConnectionId: String,

    @ColumnInfo(name = "catalog_model_id")
    val catalogModelId: String,

    @ColumnInfo(name = "provider_model_id")
    val providerModelId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "region")
    val region: String? = null,

    @ColumnInfo(name = "deployment_name")
    val deploymentName: String? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long? = null
)
