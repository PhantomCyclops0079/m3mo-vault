package com.m3mo.vault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.m3mo.vault.data.dao.MemoryDao
import com.m3mo.vault.data.dao.SettingsDao
import com.m3mo.vault.data.entity.MemoryEntity
import com.m3mo.vault.data.entity.MemoryFtsEntity // ✅ 引入 FTS Entity
import com.m3mo.vault.data.entity.SettingsEntity

/**
 * AppDatabase - Main Room database for M3mo Vault
 *
 * Contains:
 * - memories: Core memory storage
 * - settings: App configuration
 * - memories_fts: Full-text search index (Virtual Table)
 *
 * Features:
 * - WAL mode for better concurrent access
 * - Auto migrations when possible
 * - Export schema for version control
 */
@Database(
    entities = [
        MemoryEntity::class,
        SettingsEntity::class,
        MemoryFtsEntity::class // ✅ 修复点 1: 必须在这里注册 FTS 表！
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        // ✅ 修复点 2: 版本升级 (1 -> 2)，触发数据库重建
        const val VERSION = 2
        const val DATABASE_NAME = "m3mo_vault.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Get database instance (singleton)
         *
         * Uses double-checked locking for thread safety.
         * Configures WAL mode for better performance.
         */
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
                // Enable Write-Ahead Logging for better concurrent access
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Add migrations
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                // Fallback to destructive migration if no migration path
                // ⚠️ 重要：因为我们改了 Version 但没写具体 Migration，
                // Room 会直接清空数据库重建。在开发阶段这是 OK 的。
                .fallbackToDestructiveMigration()
                // Build
                .build()
        }

        /**
         * Get in-memory database for testing
         */
        fun getInMemoryInstance(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            )
                .allowMainThreadQueries() // OK for tests
                .build()
        }

        /**
         * Close the database instance
         * Call when app is destroyed or for testing
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}

/**
 * Database configuration constants
 */
object DatabaseConfig {
    /**
     * Maximum number of memories to fetch in a single query
     * Prevents memory issues on low-end devices
     */
    const val MAX_QUERY_LIMIT = 1000

    /**
     * Batch size for bulk operations
     */
    const val BATCH_SIZE = 100

    /**
     * Checkpoint threshold for WAL (in KB)
     */
    const val WAL_CHECKPOINT_THRESHOLD_KB = 1024

    /**
     * Maximum database size (in MB) before warning
     */
    const val MAX_SIZE_WARNING_MB = 100
}