package com.m3mo.vault.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.event.CleanupCompletedEvent

/**
 * CleanupWorker - Database Maintenance Worker
 *
 * Periodically cleans up tombstoned memories and performs
 * database maintenance (vacuum) to reclaim storage.
 *
 * ## Cleanup Rules
 * - Tombstoned memories older than RETENTION_DAYS are hard-deleted
 * - Vacuum is run after significant deletions (>10 records)
 * - Runs weekly, after SmartDecay (low priority)
 *
 * ## Scheduling
 * - Weekly (via OptimizationScheduler)
 * - Constraints: Device idle + charging preferred
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: IMemoryRepository,
    private val eventBus: EventBus,
    private val clock: Clock,
    private val logger: Logger
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "CleanupWorker"
        const val UNIQUE_WORK_NAME = "m3mo_cleanup"

        // How long to keep tombstoned memories before hard-delete
        const val RETENTION_DAYS = 30
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
        private const val VACUUM_THRESHOLD = 10

        // Output keys
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_VACUUMED = "vacuumed"
        const val KEY_DURATION_MS = "duration_ms"
    }

    override suspend fun doWork(): Result {
        val startTime = clock.nowMillis()

        logger.i(LogTags.WORKER, "Cleanup worker starting (attempt=$runAttemptCount)")

        try {
            val cutoffTime = clock.nowMillis() - (RETENTION_DAYS * MS_PER_DAY)

            // Hard-delete tombstoned memories older than retention period
            val cleanupResult = repository.cleanupTombstoned(cutoffTime)

            val deletedCount = when (cleanupResult) {
                is AppResult.Success -> cleanupResult.value
                is AppResult.Failure -> {
                    logger.e(LogTags.WORKER, "Cleanup failed: ${cleanupResult.error}")
                    return if (runAttemptCount < 2) Result.retry() else Result.failure()
                }
            }

            // Vacuum if significant deletions
            var vacuumed = false
            if (deletedCount >= VACUUM_THRESHOLD) {
                when (repository.vacuum()) {
                    is AppResult.Success -> {
                        vacuumed = true
                        logger.i(LogTags.WORKER, "Database vacuumed after $deletedCount deletions")
                    }
                    is AppResult.Failure -> {
                        logger.w(LogTags.WORKER, "Vacuum failed (non-critical)")
                    }
                }
            }

            val duration = clock.nowMillis() - startTime

            // Publish event
            eventBus.publish(CleanupCompletedEvent(
                deletedCount = deletedCount,
                vacuumed = vacuumed,
                durationMs = duration
            ))

            logger.i(LogTags.WORKER,
                "Cleanup complete in ${duration}ms: $deletedCount deleted, vacuum=$vacuumed"
            )

            return Result.success(
                Data.Builder()
                    .putInt(KEY_DELETED_COUNT, deletedCount)
                    .putBoolean(KEY_VACUUMED, vacuumed)
                    .putLong(KEY_DURATION_MS, duration)
                    .build()
            )

        } catch (e: Exception) {
            logger.e(LogTags.WORKER, "Cleanup worker failed", e)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
