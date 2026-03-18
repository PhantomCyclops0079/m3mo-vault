package com.m3mo.vault.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.m3mo.vault.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * SettingsDao - Data Access Object for app settings
 *
 * Provides simple key-value storage with type hints.
 */
@Dao
interface SettingsDao {

    /**
     * Insert or update a setting
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingsEntity)

    /**
     * Insert or update multiple settings
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(settings: List<SettingsEntity>)

    /**
     * Get a setting by key
     */
    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun get(key: String): SettingsEntity?

    /**
     * Observe a setting by key
     */
    @Query("SELECT * FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<SettingsEntity?>

    /**
     * Get all settings
     */
    @Query("SELECT * FROM settings ORDER BY `key`")
    suspend fun getAll(): List<SettingsEntity>

    /**
     * Observe all settings
     */
    @Query("SELECT * FROM settings ORDER BY `key`")
    fun observeAll(): Flow<List<SettingsEntity>>

    /**
     * Get settings matching a prefix
     * e.g., "decay." returns all decay settings
     */
    @Query("SELECT * FROM settings WHERE `key` LIKE :prefix || '%' ORDER BY `key`")
    suspend fun getByPrefix(prefix: String): List<SettingsEntity>

    /**
     * Delete a setting
     */
    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)

    /**
     * Delete settings by prefix
     */
    @Query("DELETE FROM settings WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    /**
     * Clear all settings
     */
    @Query("DELETE FROM settings")
    suspend fun deleteAll()

    /**
     * Check if a setting exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE `key` = :key)")
    suspend fun exists(key: String): Boolean

    /**
     * Get setting value as string (shortcut)
     */
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?
}
