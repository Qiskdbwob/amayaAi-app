package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds a stable, group-local Agent ID used by prompts, mentions, and delegation. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE agents ADD COLUMN local_id INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            UPDATE agents
            SET local_id = (
                SELECT COUNT(*)
                FROM agents AS earlier
                WHERE earlier.group_id = agents.group_id
                  AND (earlier.created_at < agents.created_at
                    OR (earlier.created_at = agents.created_at AND earlier.id <= agents.id))
            )
            """.trimIndent()
        )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agents_group_id_local_id ON agents(group_id, local_id)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
