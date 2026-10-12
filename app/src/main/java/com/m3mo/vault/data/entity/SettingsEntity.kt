package com.m3mo.vault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SettingsEntity - Key-value store for app settings
 *
 * Uses string key with JSON-serialized values for flexibility.
 * Supports typed access through SettingsRepository.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    /**
     * Setting key (unique identifier)
     */
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    /**
     * JSON-serialized value
     */
    @ColumnInfo(name = "value")
    val value: String,

    /**
     * Value type hint for deserialization
     * e.g., "string", "int", "boolean", "json"
     */
    @ColumnInfo(name = "valueType")
    val valueType: String,

    /**
     * Last modification timestamp
     */
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long
)

/**
 * Predefined settings keys
 */
object SettingsKeys {
    const val DECAY_ENABLED = "decay.enabled"
    const val DECAY_INTERVAL_HOURS = "decay.interval_hours"
    const val DECAY_BATCH_SIZE = "decay.batch_size"
    const val DECAY_BASE_RATE = "decay.base_rate"
    const val DECAY_GRACE_PERIOD_DAYS = "decay.grace_period_days"

    const val REVIEW_DAY_OF_WEEK = "review.day_of_week"
    const val REVIEW_TIME_HOUR = "review.time_hour"
    const val REVIEW_NOTIFICATION_ENABLED = "review.notification_enabled"
    const val LAST_REVIEW_TIMESTAMP = "review.last_timestamp"

    const val VOICE_ENABLED = "voice.enabled"
    const val VOICE_AUTO_CLASSIFY = "voice.auto_classify"
    const val VOICE_LANGUAGE = "voice.language"

    const val SEARCH_MODE = "search.mode"
    const val SEARCH_MAX_RESULTS = "search.max_results"
    const val SEARCH_INCLUDE_ARCHIVED = "search.include_archived"

    const val THEME_MODE = "ui.theme_mode"
    const val FONT_SIZE = "ui.font_size"
    const val HAPTIC_FEEDBACK = "ui.haptic_feedback"

    const val OPTIMIZATION_TIME_HOUR = "optimization.time_hour"
    const val OPTIMIZATION_ONLY_ON_CHARGER = "optimization.only_on_charger"
    const val OPTIMIZATION_WIFI_ONLY = "optimization.wifi_only"

    const val TOTAL_MEMORIES_CREATED = "stats.total_created"
    const val TOTAL_MEMORIES_ARCHIVED = "stats.total_archived"
    const val TOTAL_DECAY_RUNS = "stats.decay_runs"
    const val LAST_OPTIMIZATION_TIMESTAMP = "stats.last_optimization"

    const val FIRST_LAUNCH_COMPLETED = "app.first_launch_completed"
    const val ONBOARDING_COMPLETED = "app.onboarding_completed"
    const val APP_VERSION_CODE = "app.version_code"
    const val DATABASE_VERSION = "app.database_version"
}

/**
 * Default setting values
 */
object SettingsDefaults {
    const val DECAY_ENABLED = true
    const val DECAY_INTERVAL_HOURS = 24
    const val DECAY_BATCH_SIZE = 100
    const val DECAY_BASE_RATE = 1.0f
    const val DECAY_GRACE_PERIOD_DAYS = 7

    const val REVIEW_DAY_OF_WEEK = 7
    const val REVIEW_TIME_HOUR = 10
    const val REVIEW_NOTIFICATION_ENABLED = true

    const val VOICE_ENABLED = true
    const val VOICE_AUTO_CLASSIFY = true
    const val VOICE_LANGUAGE = "en-US"

    const val SEARCH_MODE = "hybrid"
    const val SEARCH_MAX_RESULTS = 20
    const val SEARCH_INCLUDE_ARCHIVED = false

    const val THEME_MODE = "system"
    const val FONT_SIZE = "medium"
    const val HAPTIC_FEEDBACK = true

    const val OPTIMIZATION_TIME_HOUR = 3
    const val OPTIMIZATION_ONLY_ON_CHARGER = true
    const val OPTIMIZATION_WIFI_ONLY = false
}
