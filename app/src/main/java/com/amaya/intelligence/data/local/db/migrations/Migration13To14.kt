package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cron_jobs ADD COLUMN agent_id INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cron_jobs_agent_id ON cron_jobs(agent_id)")
    }
}
