package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN default_model_keys_json TEXT NOT NULL DEFAULT '[]'")
    }
}
