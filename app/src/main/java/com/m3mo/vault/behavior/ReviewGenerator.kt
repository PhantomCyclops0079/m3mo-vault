package com.m3mo.vault.behavior

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.contract.ResourceCost
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.WeeklyReview
import com.m3mo.vault.domain.model.WeeklyStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ReviewGenerator - Weekly Review Generation
 */
class ReviewGenerator(
    private val repository: IMemoryRepository,
    private val governor: IResourceGovernor,
    private val clock: Clock,
    private val logger: Logger
) {
    companion object {
        private const val MAX_ITEMS_PER_SECTION = 10
        private const val DAYS_IN_WEEK = 7
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
        private const val DECAY_THRESHOLD = 6 // Weight below this is "decayed"
    }

    suspend fun generateWeeklyReview(): AppResult<WeeklyReview, AppError> {
        val now = clock.nowMillis()
        val weekStart = getWeekStart(now)
        val weekEnd = weekStart + (DAYS_IN_WEEK * MS_PER_DAY) - 1
        return generateReviewForPeriod(weekStart, weekEnd)
    }

    suspend fun generateReviewForWeek(weekStartMs: Long): AppResult<WeeklyReview, AppError> {
        val weekEnd = weekStartMs + (DAYS_IN_WEEK * MS_PER_DAY) - 1
        return generateReviewForPeriod(weekStartMs, weekEnd)
    }

    suspend fun generateReviewForPeriod(
        startMs: Long,
        endMs: Long
    ): AppResult<WeeklyReview, AppError> =
        // Run repository queries off the main thread.
        withContext(Dispatchers.IO) {
            val startTime = clock.nowMillis()

            try {
                // Check the configured 30 MB resource budget.
                if (!governor.canProceed(ResourceCost(memoryMb = 30))) {
                    logger.w(LogTags.REVIEW, "Review generation skipped: Insufficient resources")
                    return@withContext AppResult.failure(
                        AppError.Internal.UnexpectedError(
                            message = "Consolidation skipped",
                            technicalMessage = "Insufficient resources: Memory budget exceeded"
                    ))
                }

                logger.d(LogTags.REVIEW, "Generating review for period ${formatDate(startMs)} to ${formatDate(endMs)}")

                // Fetch records in the requested time range.
                val memoriesResult = repository.getByTimeRange(startMs, endMs)
                val memories = when (memoriesResult) {
                    is AppResult.Success -> memoriesResult.value
                    is AppResult.Failure -> return@withContext AppResult.failure(memoriesResult.error)
                }

                if (memories.isEmpty()) {
                    return@withContext AppResult.success(WeeklyReview.empty(startMs, endMs))
                }

                // Group records by type.
                val completedTasks = memories
                    .filter { it.type == MemoryType.TASK && it.isCompleted }
                    .sortedByDescending { it.updatedAt }
                    .take(MAX_ITEMS_PER_SECTION)

                val pendingTasks = memories
                    .filter { it.type == MemoryType.TASK && !it.isCompleted }
                    .sortedByDescending { it.weight }
                    .take(MAX_ITEMS_PER_SECTION)

                val episodes = memories
                    .filter { it.type == MemoryType.EPISODE }
                    .sortedByDescending { it.createdAt }
                    .take(MAX_ITEMS_PER_SECTION)

                val newFacts = memories
                    .filter { it.type == MemoryType.FACT }
                    .sortedByDescending { it.createdAt }
                    .take(MAX_ITEMS_PER_SECTION)

                val decayedMemories = memories
                    .filter { it.weight < DECAY_THRESHOLD && !it.isPinned }
                    .sortedBy { it.weight }
                    .take(MAX_ITEMS_PER_SECTION)

                val frequentlyAccessed = memories
                    .sortedByDescending { it.accessCount }
                    .take(MAX_ITEMS_PER_SECTION)

                val stats = generateStats(memories)

                val review = WeeklyReview(
                    weekStart = startMs,
                    weekEnd = endMs,
                    completedTasks = completedTasks,
                    pendingTasks = pendingTasks,
                    episodes = episodes,
                    newFacts = newFacts,
                    decayedMemories = decayedMemories,
                    frequentlyAccessed = frequentlyAccessed,
                    stats = stats,
                    generatedAt = clock.nowMillis()
                )

                val duration = clock.nowMillis() - startTime
                logger.i(LogTags.REVIEW, "Review generated in ${duration}ms: ${memories.size} memories")

                AppResult.success(review)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(LogTags.REVIEW, "Failed to generate review", e)
                AppResult.failure(
                    AppError.Internal.UnexpectedError(
                        message = "Failed to generate review",
                        technicalMessage = e.message ?: "Unknown error"
                    )
                )
            }
        }

    fun isReviewDue(): Boolean {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return dayOfWeek == Calendar.SUNDAY && hour >= 18
    }

    suspend fun getMemoriesNeedingReinforcement(limit: Int = 10): AppResult<List<MemoryItem>, AppError> {
        val memoriesResult = repository.getSearchable()

        return when (memoriesResult) {
            is AppResult.Success -> {
                val now = clock.nowMillis()
                val twoWeeksAgo = now - (14 * MS_PER_DAY)

                val needsReinforcement = memoriesResult.value
                    .filter { memory ->
                        memory.weight >= 8 &&
                                memory.lastAccessedAt < twoWeeksAgo &&
                                !memory.isPinned
                    }
                    .sortedByDescending { it.weight }
                    .take(limit)

                AppResult.success(needsReinforcement)
            }
            is AppResult.Failure -> AppResult.failure(memoriesResult.error)
        }
    }

    private fun generateStats(memories: List<MemoryItem>): WeeklyStats {
        val tasks = memories.filter { it.type == MemoryType.TASK }
        val completedCount = tasks.count { it.isCompleted }
        val totalTasks = tasks.size

        val completionRate = if (totalTasks > 0) {
            completedCount.toFloat() / totalTasks
        } else 0f

        return WeeklyStats(
            memoriesCreated = memories.size,
            memoriesAccessed = memories.count { it.accessCount > 0 },
            searchesPerformed = 0,
            avgSearchScore = 0f,
            tasksCompleted = completedCount,
            tasksCreated = totalTasks,
            completionRate = completionRate,
            memoriesArchived = 0,
            voiceInputSeconds = 0
        )
    }

    private fun getWeekStart(timestampMs: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestampMs
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun formatDate(timestampMs: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dateFormat.format(Date(timestampMs))
    }
}