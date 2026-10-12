package com.m3mo.vault.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.m3mo.vault.behavior.ReviewGenerator
import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.event.ReviewGeneratedEvent

/**
 * ReviewWorker - Background Worker for Weekly Review Generation
 *
 * Generates a weekly review every Sunday evening (18:00+).
 * The review includes:
 * - Completed/pending tasks
 * - New facts learned
 * - Episode highlights
 * - Decaying memories (user may want to reinforce)
 * - Usage statistics
 *
 * ## Scheduling
 * - Periodic: Every Sunday at 18:00 (via OptimizationScheduler)
 * - Manual: Can be triggered from UI
 *
 * ## Output Data
 * - KEY_MEMORIES_IN_REVIEW: total memories included
 * - KEY_TASKS_COMPLETED: tasks completed this week
 * - KEY_DECAYING_COUNT: memories losing weight
 */
class ReviewWorker(
    context: Context,
    params: WorkerParameters,
    private val reviewGenerator: ReviewGenerator,
    private val eventBus: EventBus,
    private val clock: Clock,
    private val logger: Logger
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ReviewWorker"
        const val UNIQUE_WORK_NAME = "m3mo_weekly_review"

        const val KEY_WEEK_START_MS = "week_start_ms"

        const val KEY_MEMORIES_IN_REVIEW = "memories_in_review"
        const val KEY_TASKS_COMPLETED = "tasks_completed"
        const val KEY_DECAYING_COUNT = "decaying_count"
        const val KEY_DURATION_MS = "duration_ms"
    }

    override suspend fun doWork(): Result {
        val startTime = clock.nowMillis()

        logger.i(LogTags.WORKER, "Review worker starting (attempt=$runAttemptCount)")

        try {
            val weekStartMs = inputData.getLong(KEY_WEEK_START_MS, 0L)
            val reviewResult = if (weekStartMs > 0) {
                reviewGenerator.generateReviewForWeek(weekStartMs)
            } else {
                reviewGenerator.generateWeeklyReview()
            }

            return when (reviewResult) {
                is AppResult.Success -> {
                    val review = reviewResult.value
                    val duration = clock.nowMillis() - startTime

                    eventBus.publish(ReviewGeneratedEvent(
                        weekStart = review.weekStart,
                        weekEnd = review.weekEnd,
                        totalMemories = review.stats.memoriesCreated,
                        tasksCompleted = review.stats.tasksCompleted,
                        decayingCount = review.decayedMemories.size,
                        generatedAt = review.generatedAt
                    ))

                    logger.i(LogTags.WORKER,
                        "Review generated in ${duration}ms: " +
                        "${review.stats.memoriesCreated} memories, " +
                        "${review.stats.tasksCompleted} tasks completed"
                    )

                    Result.success(
                        Data.Builder()
                            .putInt(KEY_MEMORIES_IN_REVIEW, review.stats.memoriesCreated)
                            .putInt(KEY_TASKS_COMPLETED, review.stats.tasksCompleted)
                            .putInt(KEY_DECAYING_COUNT, review.decayedMemories.size)
                            .putLong(KEY_DURATION_MS, duration)
                            .build()
                    )
                }
                is AppResult.Failure -> {
                    logger.e(LogTags.WORKER, "Review generation failed: ${reviewResult.error}")
                    if (runAttemptCount < 2) Result.retry() else Result.failure()
                }
            }

        } catch (e: Exception) {
            logger.e(LogTags.WORKER, "Review worker failed", e)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
