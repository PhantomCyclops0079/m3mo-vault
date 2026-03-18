package com.m3mo.vault.behavior

import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * UsageTracker - Tracks User Activity and Patterns
 *
 * Collects anonymous usage statistics to:
 * - Generate meaningful weekly reviews
 * - Optimize SmartDecay parameters
 * - Identify usage patterns
 *
 * All data is stored locally and never transmitted.
 *
 * ## Tracked Metrics
 *
 * | Category | Metrics |
 * |----------|---------|
 * | Memory | Creates, updates, deletes, views |
 * | Search | Queries, results clicked, avg time |
 * | Tasks | Created, completed, overdue |
 * | Voice | Input duration, transcriptions |
 *
 * ## Usage
 *
 * ```kotlin
 * val tracker = UsageTracker(clock, logger)
 *
 * // Track events
 * tracker.trackMemoryCreated(MemoryType.FACT)
 * tracker.trackSearch(query, resultsCount, selectedIndex)
 *
 * // Get statistics
 * val stats = tracker.getWeeklyStats()
 * val dailyActivity = tracker.getDailyActivity()
 * ```
 */
class UsageTracker(
    private val clock: Clock,
    private val logger: Logger
) {
    // Memory metrics
    private val memoriesCreated = AtomicInteger(0)
    private val memoriesUpdated = AtomicInteger(0)
    private val memoriesDeleted = AtomicInteger(0)
    private val memoriesViewed = AtomicInteger(0)

    // Search metrics
    private val searchesPerformed = AtomicInteger(0)
    private val searchResultsClicked = AtomicInteger(0)
    private val totalSearchTimeMs = AtomicLong(0)

    // Task metrics
    private val tasksCreated = AtomicInteger(0)
    private val tasksCompleted = AtomicInteger(0)

    // Voice metrics
    private val voiceInputCount = AtomicInteger(0)
    private val voiceInputDurationMs = AtomicLong(0)

    // Daily activity (hour -> count)
    private val hourlyActivity = ConcurrentHashMap<Int, AtomicInteger>()

    // Type distribution
    private val typeDistribution = ConcurrentHashMap<String, AtomicInteger>()

    // Session tracking
    private var sessionStartMs: Long = clock.nowMillis()
    private val sessionCount = AtomicInteger(1)

    // ========== Tracking Methods ==========

    /**
     * Track memory created
     */
    fun trackMemoryCreated(type: String) {
        memoriesCreated.incrementAndGet()
        typeDistribution.getOrPut(type) { AtomicInteger(0) }.incrementAndGet()
        recordHourlyActivity()
        logger.d(LogTags.METRICS, "Tracked: memory created ($type)")
    }

    /**
     * Track memory updated
     */
    fun trackMemoryUpdated() {
        memoriesUpdated.incrementAndGet()
        recordHourlyActivity()
    }

    /**
     * Track memory deleted
     */
    fun trackMemoryDeleted() {
        memoriesDeleted.incrementAndGet()
    }

    /**
     * Track memory viewed
     */
    fun trackMemoryViewed() {
        memoriesViewed.incrementAndGet()
        recordHourlyActivity()
    }

    /**
     * Track search performed
     */
    fun trackSearch(
        query: String,
        resultsCount: Int,
        searchTimeMs: Long
    ) {
        searchesPerformed.incrementAndGet()
        totalSearchTimeMs.addAndGet(searchTimeMs)
        recordHourlyActivity()
        logger.d(LogTags.METRICS, "Tracked: search '$query' -> $resultsCount results in ${searchTimeMs}ms")
    }

    /**
     * Track search result clicked
     */
    fun trackSearchResultClicked(resultIndex: Int) {
        searchResultsClicked.incrementAndGet()
    }

    /**
     * Track task created
     */
    fun trackTaskCreated() {
        tasksCreated.incrementAndGet()
        memoriesCreated.incrementAndGet()
        typeDistribution.getOrPut("TASK") { AtomicInteger(0) }.incrementAndGet()
        recordHourlyActivity()
    }

    /**
     * Track task completed
     */
    fun trackTaskCompleted() {
        tasksCompleted.incrementAndGet()
    }

    /**
     * Track voice input
     */
    fun trackVoiceInput(durationMs: Long) {
        voiceInputCount.incrementAndGet()
        voiceInputDurationMs.addAndGet(durationMs)
        recordHourlyActivity()
    }

    /**
     * Track new session started
     */
    fun trackSessionStarted() {
        sessionStartMs = clock.nowMillis()
        sessionCount.incrementAndGet()
    }

    // ========== Statistics Methods ==========

    /**
     * Get current statistics snapshot
     */
    fun getStats(): UsageStats {
        val avgSearchTime = if (searchesPerformed.get() > 0) {
            totalSearchTimeMs.get() / searchesPerformed.get()
        } else 0L

        val searchClickRate = if (searchesPerformed.get() > 0) {
            searchResultsClicked.get().toFloat() / searchesPerformed.get()
        } else 0f

        val taskCompletionRate = if (tasksCreated.get() > 0) {
            tasksCompleted.get().toFloat() / tasksCreated.get()
        } else 0f

        return UsageStats(
            memoriesCreated = memoriesCreated.get(),
            memoriesUpdated = memoriesUpdated.get(),
            memoriesDeleted = memoriesDeleted.get(),
            memoriesViewed = memoriesViewed.get(),
            searchesPerformed = searchesPerformed.get(),
            avgSearchTimeMs = avgSearchTime,
            searchClickRate = searchClickRate,
            tasksCreated = tasksCreated.get(),
            tasksCompleted = tasksCompleted.get(),
            taskCompletionRate = taskCompletionRate,
            voiceInputCount = voiceInputCount.get(),
            voiceInputDurationMs = voiceInputDurationMs.get(),
            sessionCount = sessionCount.get(),
            typeDistribution = typeDistribution.mapValues { it.value.get() }
        )
    }

    /**
     * Get hourly activity distribution
     */
    fun getHourlyActivity(): Map<Int, Int> {
        return hourlyActivity.mapValues { it.value.get() }
    }

    /**
     * Get most active hours
     */
    fun getMostActiveHours(topN: Int = 3): List<Int> {
        return hourlyActivity
            .toList()
            .sortedByDescending { it.second.get() }
            .take(topN)
            .map { it.first }
    }

    /**
     * Get type distribution as percentages
     */
    fun getTypeDistributionPercent(): Map<String, Float> {
        val total = typeDistribution.values.sumOf { it.get() }
        if (total == 0) return emptyMap()

        return typeDistribution.mapValues { 
            (it.value.get().toFloat() / total) * 100 
        }
    }

    /**
     * Reset all statistics
     */
    fun reset() {
        memoriesCreated.set(0)
        memoriesUpdated.set(0)
        memoriesDeleted.set(0)
        memoriesViewed.set(0)
        searchesPerformed.set(0)
        searchResultsClicked.set(0)
        totalSearchTimeMs.set(0)
        tasksCreated.set(0)
        tasksCompleted.set(0)
        voiceInputCount.set(0)
        voiceInputDurationMs.set(0)
        hourlyActivity.clear()
        typeDistribution.clear()
        sessionCount.set(1)
        sessionStartMs = clock.nowMillis()

        logger.d(LogTags.METRICS, "Usage statistics reset")
    }

    // ========== Internal Methods ==========

    /**
     * Record activity for current hour
     */
    private fun recordHourlyActivity() {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = clock.nowMillis()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        hourlyActivity.getOrPut(hour) { AtomicInteger(0) }.incrementAndGet()
    }
}

/**
 * Usage statistics snapshot
 */
data class UsageStats(
    // Memory metrics
    val memoriesCreated: Int,
    val memoriesUpdated: Int,
    val memoriesDeleted: Int,
    val memoriesViewed: Int,

    // Search metrics
    val searchesPerformed: Int,
    val avgSearchTimeMs: Long,
    val searchClickRate: Float,

    // Task metrics
    val tasksCreated: Int,
    val tasksCompleted: Int,
    val taskCompletionRate: Float,

    // Voice metrics
    val voiceInputCount: Int,
    val voiceInputDurationMs: Long,

    // Session
    val sessionCount: Int,

    // Distribution
    val typeDistribution: Map<String, Int>
) {
    /**
     * Total interactions
     */
    val totalInteractions: Int
        get() = memoriesCreated + memoriesUpdated + memoriesViewed + searchesPerformed

    /**
     * Voice input duration in seconds
     */
    val voiceInputSeconds: Int
        get() = (voiceInputDurationMs / 1000).toInt()
}
