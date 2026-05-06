package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_aliases")
data class ModelAliasEntity(
    @PrimaryKey
    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "strategy")
    val strategy: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
