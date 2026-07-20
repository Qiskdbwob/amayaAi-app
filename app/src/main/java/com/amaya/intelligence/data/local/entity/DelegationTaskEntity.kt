package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "delegation_tasks",
    foreignKeys = [
        ForeignKey(entity = AgentGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AgentEntity::class, parentColumns = ["id"], childColumns = ["agent_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("group_id"), Index("agent_id")]
)
data class DelegationTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "agent_id") val agentId: Long,
    @ColumnInfo(name = "request") val request: String,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "result") val result: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
