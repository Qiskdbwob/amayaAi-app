package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_groups",
    indices = [Index(value = ["name"], unique = true)]
)
data class AgentGroupEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "instructions") val instructions: String = "",
    @ColumnInfo(name = "workspace_path") val workspacePath: String,
    @ColumnInfo(name = "capability_profile") val capabilityProfile: String = com.amaya.intelligence.domain.models.AgentCapabilityProfile().encode(),
    @ColumnInfo(name = "reference_paths_json") val referencePathsJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
