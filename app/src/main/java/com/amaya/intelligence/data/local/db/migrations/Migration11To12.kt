package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN capability_profile TEXT NOT NULL DEFAULT 'workspace=true;terminal=true;browser=true;subagents=true'")
        db.execSQL(
            """
            UPDATE agents
            SET capability_profile = (
                SELECT agent_groups.capability_profile
                FROM agent_groups
                WHERE agent_groups.id = agents.group_id
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE conversations ADD COLUMN agent_id INTEGER")
        db.execSQL(
            """
            UPDATE conversations
            SET agent_id = (
                SELECT MIN(agents.id)
                FROM agents
                WHERE CAST(agents.group_id AS TEXT) = conversations.owner_id
            )
            WHERE assistant_mode = 'AGENT'
            """.trimIndent()
        )
    }
}
