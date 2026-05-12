package com.amaya.intelligence.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConversationScope(val wireName: String) {
    LOCAL("local"),
    WINDOWS_BRIDGE("windows_bridge"),
    OPENCODE("opencode");

    companion object {
        fun fromWireName(value: String?): ConversationScope =
            entries.firstOrNull { it.wireName == value } ?: LOCAL
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "workspace_path")
    val workspacePath: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "messages_json")
    val messagesJson: String,

    @ColumnInfo(name = "scope")
    val scope: String = ConversationScope.LOCAL.wireName
)
