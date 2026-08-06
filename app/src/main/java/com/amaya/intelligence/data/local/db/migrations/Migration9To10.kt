package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN assistant_mode TEXT NOT NULL DEFAULT 'CHAT'")
        db.execSQL("ALTER TABLE conversations ADD COLUMN owner_id TEXT")
        db.execSQL(
            """
            UPDATE conversations
            SET assistant_mode = 'PROJECT',
                owner_id = COALESCE(
                    (SELECT CAST(projects.id AS TEXT) FROM projects WHERE projects.root_path = conversations.workspace_path),
                    workspace_path
                )
            WHERE workspace_path IS NOT NULL AND workspace_path != ''
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agents (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                group_name TEXT NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                instructions TEXT NOT NULL,
                workspace_path TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agents_group_name_name ON agents(group_name, name)")
    }
}
