package com.m3mo.vault.domain.contract

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.domain.model.WeeklyReview
import com.m3mo.vault.domain.model.WeeklyStats
import kotlinx.coroutines.flow.Flow

/**
 * IWeeklyReviewEngine - Weekly Review Generation Interface
 *
 * Generates the weekly review ritual content:
 * - Completed tasks celebration
 * - Pending tasks reminder
 * - Significant episodes summary
 * - Decaying memories for review
 * - Usage statistics
 */
interface IWeeklyReviewEngine {

    /**
     * Generate weekly review for a specific week
     *
     * @param weekStart Start of week (Monday 00:00)
     * @param weekEnd End of week (Sunday 23:59)
     * @return Generated review
     */
    suspend fun generateReview(
        weekStart: Long,
        weekEnd: Long
    ): AppResult<WeeklyReview, AppError>

    /**
     * Generate review for current week
     */
    suspend fun generateCurrentWeekReview(): AppResult<WeeklyReview, AppError>

    /**
     * Generate review for previous week
     */
    suspend fun generatePreviousWeekReview(): AppResult<WeeklyReview, AppError>

    /**
     * Get cached review if available
     *
     * @param weekStart Week identifier
     * @return Cached review or null
     */
    suspend fun getCachedReview(weekStart: Long): WeeklyReview?

    /**
     * Invalidate cached review
     */
    suspend fun invalidateCache(weekStart: Long)

    /**
     * Calculate statistics for a time period
     *
     * @param startTime Period start
     * @param endTime Period end
     * @return Calculated statistics
     */
    suspend fun calculateStats(
        startTime: Long,
        endTime: Long
    ): AppResult<WeeklyStats, AppError>

    /**
     * Get week boundaries for a given timestamp
     *
     * @param timestamp Any time within the week
     * @return Pair of (weekStart, weekEnd)
     */
    fun getWeekBoundaries(timestamp: Long): Pair<Long, Long>

    /**
     * Check if a review is due (e.g., Sunday evening)
     */
    fun isReviewDue(): Boolean

    /**
     * Observe review readiness
     */
    fun observeReviewReady(): Flow<WeeklyReview>

    /**
     * Schedule review notification
     */
    suspend fun scheduleReviewNotification()

    /**
     * Cancel scheduled notification
     */
    suspend fun cancelReviewNotification()
}

/**
 * Weekly review configuration
 */
data class WeeklyReviewConfig(
    /**
     * Day of week to show review (1=Monday, 7=Sunday)
     */
    val reviewDay: Int = 7,

    /**
     * Hour to show review notification (24h format)
     */
    val reviewHour: Int = 19,

    /**
     * Maximum tasks to show
     */
    val maxTasks: Int = 20,

    /**
     * Maximum episodes to show
     */
    val maxEpisodes: Int = 10,

    /**
     * Maximum decayed memories to show
     */
    val maxDecayed: Int = 5,

    /**
     * Include statistics
     */
    val includeStats: Boolean = true,

    /**
     * Cache duration in milliseconds
     */
    val cacheDurationMs: Long = 3600000
) {
    companion object {
        fun default(): WeeklyReviewConfig = WeeklyReviewConfig()
    }
}
