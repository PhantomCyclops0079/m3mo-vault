package com.m3mo.vault.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DatabaseMigrations - Room database migration definitions
 *
 * Each migration handles schema changes between versions.
 * Migrations are applied automatically when database version increases.
 *
 * Best practices:
 * - Always test migrations with real data
 * - Keep migrations simple and atomic
 * - Add indexes after data migration
 * - Never remove columns in SQLite (add new, deprecate old)
 */
object DatabaseMigrations {

    /**
     * All migrations in order
     * Add new migrations to this array
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    )

    /**
     * Example: Migration from version 1 to 2
     * Uncomment and modify when needed
     */
    /*
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Example: Add a new column
            database.execSQL(
                "ALTER TABLE memories ADD COLUMN newField TEXT DEFAULT NULL"
            )
            
            // Example: Add an index
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memories_newField ON memories(newField)"
            )
        }
    }
    */

    /**
     * Migration helper: Add column if not exists
     * SQLite doesn't support IF NOT EXISTS for ALTER TABLE
     */
    fun SupportSQLiteDatabase.addColumnIfNotExists(
        tableName: String,
        columnName: String,
        columnDefinition: String
    ) {
        val cursor = query("PRAGMA table_info($tableName)")
        val columns = mutableListOf<String>()
        while (cursor.moveToNext()) {
            columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        cursor.close()

        if (!columns.contains(columnName)) {
            execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
        }
    }

    /**
     * Migration helper: Create index if not exists
     */
    fun SupportSQLiteDatabase.createIndexIfNotExists(
        indexName: String,
        tableName: String,
        columns: String
    ) {
        execSQL("CREATE INDEX IF NOT EXISTS $indexName ON $tableName($columns)")
    }

    /**
     * Migration helper: Check if table exists
     */
    fun SupportSQLiteDatabase.tableExists(tableName: String): Boolean {
        val cursor = query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    /**
     * Migration helper: Get current table schema
     */
    fun SupportSQLiteDatabase.getTableSchema(tableName: String): List<ColumnInfo> {
        val cursor = query("PRAGMA table_info($tableName)")
        val columns = mutableListOf<ColumnInfo>()
        while (cursor.moveToNext()) {
            columns.add(
                ColumnInfo(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                    primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) > 0
                )
            )
        }
        cursor.close()
        return columns
    }

    /**
     * Column info for schema inspection
     */
    data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Boolean
    )
}

/**
 * Example migrations for future use
 *
 * Version 2: Add summary field for memories
 * ```
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(database: SupportSQLiteDatabase) {
 *         database.execSQL(
 *             "ALTER TABLE memories ADD COLUMN summary TEXT DEFAULT NULL"
 *         )
 *     }
 * }
 * ```
 *
 * Version 3: Add reminder table
 * ```
 * val MIGRATION_2_3 = object : Migration(2, 3) {
 *     override fun migrate(database: SupportSQLiteDatabase) {
 *         database.execSQL("""
 *             CREATE TABLE IF NOT EXISTS reminders (
 *                 id INTEGER PRIMARY KEY NOT NULL,
 *                 memoryId INTEGER NOT NULL,
 *                 triggerTime INTEGER NOT NULL,
 *                 isActive INTEGER NOT NULL DEFAULT 1,
 *                 FOREIGN KEY(memoryId) REFERENCES memories(id) ON DELETE CASCADE
 *             )
 *         """)
 *         database.execSQL(
 *             "CREATE INDEX IF NOT EXISTS index_reminders_memoryId ON reminders(memoryId)"
 *         )
 *     }
 * }
 * ```
 */
