package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "model_routes",
    primaryKeys = ["alias", "provider_id", "model_id"],
    indices = [Index("alias")]
)
data class ModelRouteEntity(
    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "priority")
    val priority: Int,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true
)
