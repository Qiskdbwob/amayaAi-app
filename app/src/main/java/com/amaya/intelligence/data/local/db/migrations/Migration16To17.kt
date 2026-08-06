package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separates rendered history from model-visible context. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN context_messages_json TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE conversations SET context_messages_json = messages_json")
    }
}
