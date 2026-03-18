package com.m3mo.vault.data.repository

import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.json.JsonCodec
import com.m3mo.vault.data.dao.SettingsDao
import com.m3mo.vault.data.entity.SettingsDefaults
import com.m3mo.vault.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SettingsRepositoryImpl - Repository for app settings
 *
 * Provides typed access to key-value settings with:
 * - Type-safe getters/setters
 * - Default values
 * - Reactive observation
 * - Batch operations
 */
class SettingsRepositoryImpl(
    private val settingsDao: SettingsDao,
    private val jsonCodec: JsonCodec,
    private val clock: Clock
) {
    // ========== String Settings ==========

    suspend fun getString(key: String, default: String? = null): String? {
        val entity = settingsDao.get(key) ?: return default
        return entity.value
    }

    suspend fun setString(key: String, value: String) {
        val entity = SettingsEntity(
            key = key,
            value = value,
            valueType = "string",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    fun observeString(key: String): Flow<String?> {
        return settingsDao.observe(key).map { it?.value }
    }

    // ========== Int Settings ==========

    suspend fun getInt(key: String, default: Int): Int {
        val entity = settingsDao.get(key) ?: return default
        return entity.value.toIntOrNull() ?: default
    }

    suspend fun setInt(key: String, value: Int) {
        val entity = SettingsEntity(
            key = key,
            value = value.toString(),
            valueType = "int",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    fun observeInt(key: String, default: Int): Flow<Int> {
        return settingsDao.observe(key).map {
            it?.value?.toIntOrNull() ?: default
        }
    }

    // ========== Long Settings ==========

    suspend fun getLong(key: String, default: Long): Long {
        val entity = settingsDao.get(key) ?: return default
        return entity.value.toLongOrNull() ?: default
    }

    suspend fun setLong(key: String, value: Long) {
        val entity = SettingsEntity(
            key = key,
            value = value.toString(),
            valueType = "long",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    // ========== Float Settings ==========

    suspend fun getFloat(key: String, default: Float): Float {
        val entity = settingsDao.get(key) ?: return default
        return entity.value.toFloatOrNull() ?: default
    }

    suspend fun setFloat(key: String, value: Float) {
        val entity = SettingsEntity(
            key = key,
            value = value.toString(),
            valueType = "float",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    // ========== Boolean Settings ==========

    suspend fun getBoolean(key: String, default: Boolean): Boolean {
        val entity = settingsDao.get(key) ?: return default
        return entity.value.toBooleanStrictOrNull() ?: default
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val entity = SettingsEntity(
            key = key,
            value = value.toString(),
            valueType = "boolean",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    fun observeBoolean(key: String, default: Boolean): Flow<Boolean> {
        return settingsDao.observe(key).map {
            it?.value?.toBooleanStrictOrNull() ?: default
        }
    }

    // ========== JSON Settings ==========

    /**
     * Get a JSON-serialized setting
     * Note: Due to Kotlin limitations, caller must pass the class explicitly
     */
    suspend fun <T : Any> getJson(key: String, clazz: Class<T>): T? {
        val entity = settingsDao.get(key) ?: return null
        return jsonCodec.decode(entity.value, clazz).getOrNull()
    }

    suspend fun <T : Any> setJson(key: String, value: T) {
        val json = jsonCodec.encode(value).getOrNull() ?: return
        val entity = SettingsEntity(
            key = key,
            value = json,
            valueType = "json",
            updatedAt = clock.nowMillis()
        )
        settingsDao.upsert(entity)
    }

    // ========== Batch Operations ==========

    suspend fun getAll(): Map<String, String> {
        return settingsDao.getAll().associate { it.key to it.value }
    }

    suspend fun getByPrefix(prefix: String): Map<String, String> {
        return settingsDao.getByPrefix(prefix).associate { it.key to it.value }
    }

    suspend fun delete(key: String) {
        settingsDao.delete(key)
    }

    suspend fun deleteByPrefix(prefix: String) {
        settingsDao.deleteByPrefix(prefix)
    }

    suspend fun clear() {
        settingsDao.deleteAll()
    }

    // ========== Convenience Methods for Common Settings ==========

    // Decay settings
    suspend fun isDecayEnabled(): Boolean =
        getBoolean("decay.enabled", SettingsDefaults.DECAY_ENABLED)

    suspend fun setDecayEnabled(enabled: Boolean) =
        setBoolean("decay.enabled", enabled)

    suspend fun getDecayIntervalHours(): Int =
        getInt("decay.interval_hours", SettingsDefaults.DECAY_INTERVAL_HOURS)

    suspend fun setDecayIntervalHours(hours: Int) =
        setInt("decay.interval_hours", hours)

    suspend fun getDecayBatchSize(): Int =
        getInt("decay.batch_size", SettingsDefaults.DECAY_BATCH_SIZE)

    // Review settings
    suspend fun getReviewDayOfWeek(): Int =
        getInt("review.day_of_week", SettingsDefaults.REVIEW_DAY_OF_WEEK)

    suspend fun setReviewDayOfWeek(day: Int) =
        setInt("review.day_of_week", day)

    suspend fun getLastReviewTimestamp(): Long =
        getLong("review.last_timestamp", 0L)

    suspend fun setLastReviewTimestamp(timestamp: Long) =
        setLong("review.last_timestamp", timestamp)

    // Stats
    suspend fun getTotalMemoriesCreated(): Long =
        getLong("stats.total_created", 0L)

    suspend fun incrementTotalMemoriesCreated() {
        val current = getTotalMemoriesCreated()
        setLong("stats.total_created", current + 1)
    }

    suspend fun getLastOptimizationTimestamp(): Long =
        getLong("stats.last_optimization", 0L)

    suspend fun setLastOptimizationTimestamp(timestamp: Long) =
        setLong("stats.last_optimization", timestamp)

    // App state
    suspend fun isFirstLaunchCompleted(): Boolean =
        getBoolean("app.first_launch_completed", false)

    suspend fun setFirstLaunchCompleted() =
        setBoolean("app.first_launch_completed", true)

    suspend fun isOnboardingCompleted(): Boolean =
        getBoolean("app.onboarding_completed", false)

    suspend fun setOnboardingCompleted() =
        setBoolean("app.onboarding_completed", true)

    // Theme
    suspend fun getThemeMode(): String =
        getString("ui.theme_mode", SettingsDefaults.THEME_MODE) ?: SettingsDefaults.THEME_MODE

    suspend fun setThemeMode(mode: String) =
        setString("ui.theme_mode", mode)

    fun observeThemeMode(): Flow<String> {
        return settingsDao.observe("ui.theme_mode").map {
            it?.value ?: SettingsDefaults.THEME_MODE
        }
    }
}
