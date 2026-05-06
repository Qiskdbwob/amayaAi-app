package com.amaya.intelligence.data.local.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.amaya.intelligence.data.local.dao.*
import com.amaya.intelligence.data.local.entity.*
import com.amaya.intelligence.data.local.db.migrations.MIGRATION_5_6
import com.amaya.intelligence.data.local.db.migrations.MIGRATION_6_7

@TypeConverters(CronJobTypeConverters::class)
@Database(
    entities = [
        ProjectEntity::class,
        FileEntity::class,
        FileFtsEntity::class,
        FileMetadataEntity::class,
        ConversationEntity::class,
        CronJobEntity::class,
        ProviderConnectionEntity::class,
        ModelCatalogEntity::class,
        ProviderModelAvailabilityEntity::class,
        ManualModelOverrideEntity::class,
        ModelAliasEntity::class,
        ModelRouteEntity::class,
        AgentProfileEntity::class
    ],
    version = AppDatabase.DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun fileDao(): FileDao
    abstract fun fileMetadataDao(): FileMetadataDao
    abstract fun conversationDao(): ConversationDao
    abstract fun cronJobDao(): CronJobDao
    abstract fun providerConnectionDao(): ProviderConnectionDao
    abstract fun modelCatalogDao(): ModelCatalogDao
    abstract fun agentProfileDao(): AgentProfileDao

    companion object {
        const val DATABASE_VERSION = 7
        private const val DATABASE_NAME = "Amaya_db"
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        Log.d(TAG, "Database created strategy: version $DATABASE_VERSION")
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        Log.d(TAG, "Database opened: version ${db.version}")
                    }
                })
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                .build()
        }
    }
}
