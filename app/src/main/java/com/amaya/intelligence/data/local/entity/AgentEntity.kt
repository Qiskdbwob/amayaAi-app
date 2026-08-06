package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agents",
    foreignKeys = [ForeignKey(
        entity = AgentGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["group_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["group_id", "name"], unique = true),
        Index(value = ["group_id", "local_id"], unique = true)
    ]
)
data class AgentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: Long,
    /** Stable only within group; exposed to models and UI. [id] remains the DB identity. */
    @ColumnInfo(name = "local_id", defaultValue = "0") val localId: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "role") val role: String = "",
    @ColumnInfo(name = "instructions") val instructions: String = "",
    @ColumnInfo(name = "capability_profile", defaultValue = "'workspace=true;terminal=true;browser=true;subagents=true'")
    val capabilityProfile: String = com.amaya.intelligence.domain.models.AgentCapabilityProfile().encode(),
    @ColumnInfo(name = "reference_paths_json", defaultValue = "'[]'") val referencePathsJson: String = "[]",
    @ColumnInfo(name = "default_model_keys_json", defaultValue = "'[]'") val defaultModelKeysJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
