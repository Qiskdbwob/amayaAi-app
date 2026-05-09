package com.amaya.intelligence.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Template for Room Migration.
 * 
 * Rules for SAFE Migration:
 * 1. Always use version numbers in the name (e.g., MIGRATION_1_2).
 * 2. Use raw SQL for transformations.
 * 3. Handle NULL vs NOT NULL carefully.
 * 4. Provide DEFAULT values for new columns.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Example: Adding a new column to projects table
        // db.execSQL("ALTER TABLE projects ADD COLUMN description TEXT DEFAULT '' NOT NULL")
        
        // Example: Creating a new table
        /*
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `new_table` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `data` TEXT NOT NULL
            )
        """)
        */
    }
}

/**
 * Migration for Version 5 to 6.
 * Standardizing column names to snake_case.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LogMigration.d("Starting migration 5 -> 6: Renaming columns to snake_case")
        
        // 1. Conversations: workspacePath -> workspace_path, etc.
        db.execSQL("ALTER TABLE conversations RENAME COLUMN workspacePath TO workspace_path")
        db.execSQL("ALTER TABLE conversations RENAME COLUMN createdAt TO created_at")
        db.execSQL("ALTER TABLE conversations RENAME COLUMN updatedAt TO updated_at")
        db.execSQL("ALTER TABLE conversations RENAME COLUMN messagesJson TO messages_json")

        // 2. Cron Jobs: triggerTimeMillis -> trigger_time_millis, etc.
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN triggerTimeMillis TO trigger_time_millis")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN recurringType TO recurring_type")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN isActive TO is_active")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN createdAt TO created_at")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN conversationId TO conversation_id")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN fireCount TO fire_count")
        db.execSQL("ALTER TABLE cron_jobs RENAME COLUMN sessionMode TO session_mode")

        LogMigration.d("Migration 5 -> 6 completed successfully")
    }
}

/**
 * Migration for Version 6 to 7.
 * Adds the provider/model catalog tables used by the new provider system while
 * preserving legacy AgentConfig DataStore settings for compatibility.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LogMigration.d("Starting migration 6 -> 7: Adding provider/model catalog tables")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `provider_connections` (
                `id` TEXT NOT NULL,
                `provider_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `base_url` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `config_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `model_catalog` (
                `id` TEXT NOT NULL,
                `provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `capabilities_csv` TEXT NOT NULL,
                `input_price_per_million_tokens` REAL,
                `output_price_per_million_tokens` REAL,
                `context_window` INTEGER,
                `max_output_tokens` INTEGER,
                `release_date` TEXT,
                `knowledge_cutoff` TEXT,
                `last_synced_at` INTEGER,
                `metadata_json` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_model_catalog_provider_id_model_id` ON `model_catalog` (`provider_id`, `model_id`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `provider_model_availability` (
                `provider_connection_id` TEXT NOT NULL,
                `catalog_model_id` TEXT NOT NULL,
                `provider_model_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `region` TEXT,
                `deployment_name` TEXT,
                `error_message` TEXT,
                `last_checked_at` INTEGER,
                PRIMARY KEY(`provider_connection_id`, `catalog_model_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_model_availability_provider_connection_id` ON `provider_model_availability` (`provider_connection_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_provider_model_availability_catalog_model_id` ON `provider_model_availability` (`catalog_model_id`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `manual_model_overrides` (
                `provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `display_name_override` TEXT,
                `input_price_override` REAL,
                `output_price_override` REAL,
                `context_window_override` INTEGER,
                `capabilities_override_csv` TEXT,
                `metadata_override_json` TEXT NOT NULL,
                PRIMARY KEY(`provider_id`, `model_id`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `model_aliases` (
                `alias` TEXT NOT NULL,
                `strategy` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`alias`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `model_routes` (
                `alias` TEXT NOT NULL,
                `provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `priority` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                PRIMARY KEY(`alias`, `provider_id`, `model_id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_routes_alias` ON `model_routes` (`alias`)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `agent_profiles` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `provider_connection_id` TEXT NOT NULL,
                `default_model_id` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `max_tokens` INTEGER NOT NULL,
                `max_iterations` INTEGER NOT NULL,
                `capability_overrides_json` TEXT NOT NULL,
                `legacy_agent_config_json` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        LogMigration.d("Migration 6 -> 7 completed successfully")
    }
}

/**
 * Migration for Version 7 to 8.
 * Adds a conversation scope so Local chat and Windows Bridge chat can share the
 * same table/sidebar UI without mixing histories.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LogMigration.d("Starting migration 7 -> 8: Adding conversation scope")
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE conversations ADD COLUMN scope TEXT NOT NULL DEFAULT 'local'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_scope_updated_at` ON `conversations` (`scope`, `updated_at`)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        LogMigration.d("Migration 7 -> 8 completed successfully")
    }
}

object LogMigration {
    fun d(message: String) {
        android.util.Log.d("RoomMigration", message)
    }
}
