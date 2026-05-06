package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_profiles")
data class AgentProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "provider_connection_id")
    val providerConnectionId: String,

    @ColumnInfo(name = "default_model_id")
    val defaultModelId: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int = 8192,

    @ColumnInfo(name = "max_iterations")
    val maxIterations: Int = 10,

    @ColumnInfo(name = "capability_overrides_json")
    val capabilityOverridesJson: String = "{}",

    @ColumnInfo(name = "legacy_agent_config_json")
    val legacyAgentConfigJson: String = "{}",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
