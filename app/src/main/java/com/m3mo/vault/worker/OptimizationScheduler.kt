package com.m3mo.vault.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * OptimizationScheduler - Unified Worker Scheduling Manager
 *
 * Central place to schedule, cancel, and monitor all background workers.
 * Integrates with BatteryAwareScheduler for constraint-aware scheduling.
 *
 * ## Workers Managed
 *
 * | Worker | Frequency | Constraints |
 * |--------|-----------|-------------|
 * | SmartDecay | Daily | Battery >20%, not power-save |
 * | Review | Weekly (Sun) | None (lightweight) |
 * | Cleanup | Weekly | Idle + Charging preferred |
 *
 * ## Usage
 *
 * ```kotlin
 * val scheduler = OptimizationScheduler(context, logger)
 *
 * // Schedule all workers
 * scheduler.scheduleAll()
 *
 * // Schedule with custom hour
 * scheduler.scheduleSmartDecay(hour = 3) // 3 AM
 *
 * // Manual trigger
 * scheduler.runSmartDecayNow()
 *
 * // Cancel all
 * scheduler.cancelAll()
 *
 * // Monitor status
 * scheduler.observeSmartDecayStatus().collect { status -> ... }
 * ```
 */
class OptimizationScheduler(
    private val context: Context,
    private val logger: Logger
) {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    companion object {
        const val DEFAULT_DECAY_HOUR = 3
        const val DEFAULT_REVIEW_DAY = Calendar.SUNDAY
        const val DEFAULT_REVIEW_HOUR = 18
    }


    /**
     * Schedule all periodic workers with default settings
     */
    fun scheduleAll(
        decayHour: Int = DEFAULT_DECAY_HOUR,
        requireUnmetered: Boolean = false
    ) {
        scheduleSmartDecay(decayHour, requireUnmetered)
        scheduleWeeklyReview()
        scheduleWeeklyCleanup()
        logger.i(LogTags.WORKER, "All workers scheduled")
    }

    /**
     * Cancel all periodic workers
     */
    fun cancelAll() {
        workManager.cancelUniqueWork(SmartDecayWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(ReviewWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(CleanupWorker.UNIQUE_WORK_NAME)
        logger.i(LogTags.WORKER, "All workers cancelled")
    }


    /**
     * Schedule daily SmartDecay optimization
     *
     * @param hour Hour of day to run (0-23), default 3 AM
     * @param requireUnmetered Whether to require WiFi
     */
    fun scheduleSmartDecay(
        hour: Int = DEFAULT_DECAY_HOUR,
        requireUnmetered: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply {
                if (requireUnmetered) {
                    setRequiredNetworkType(NetworkType.UNMETERED)
                }
            }
            .build()

        val initialDelay = calculateInitialDelay(hour)

        val workRequest = PeriodicWorkRequestBuilder<SmartDecayWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(SmartDecayWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SmartDecayWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        logger.i(LogTags.WORKER, "SmartDecay scheduled daily at ${hour}:00 (delay=${initialDelay}ms)")
    }

    /**
     * Run SmartDecay immediately (manual trigger)
     */
    fun runSmartDecayNow(maxBatchSize: Int = 500) {
        val inputData = Data.Builder()
            .putBoolean(SmartDecayWorker.KEY_FORCE_RUN, true)
            .putInt(SmartDecayWorker.KEY_MAX_BATCH_SIZE, maxBatchSize)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SmartDecayWorker>()
            .setInputData(inputData)
            .addTag(SmartDecayWorker.TAG)
            .addTag("manual")
            .build()

        workManager.enqueueUniqueWork(
            "${SmartDecayWorker.UNIQUE_WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        logger.i(LogTags.WORKER, "Manual SmartDecay triggered")
    }

    /**
     * Cancel SmartDecay scheduling
     */
    fun cancelSmartDecay() {
        workManager.cancelUniqueWork(SmartDecayWorker.UNIQUE_WORK_NAME)
        logger.i(LogTags.WORKER, "SmartDecay cancelled")
    }


    /**
     * Schedule weekly review generation (Sunday evening)
     */
    fun scheduleWeeklyReview(
        dayOfWeek: Int = DEFAULT_REVIEW_DAY,
        hour: Int = DEFAULT_REVIEW_HOUR
    ) {
        val constraints = Constraints.Builder().build()

        val initialDelay = calculateWeeklyDelay(dayOfWeek, hour)

        val workRequest = PeriodicWorkRequestBuilder<ReviewWorker>(
            repeatInterval = 7,
            repeatIntervalTimeUnit = TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(ReviewWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ReviewWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        logger.i(LogTags.WORKER, "Weekly review scheduled for day=$dayOfWeek at ${hour}:00")
    }

    /**
     * Generate review now (manual trigger)
     */
    fun runReviewNow(weekStartMs: Long = 0L) {
        val inputData = Data.Builder()
            .putLong(ReviewWorker.KEY_WEEK_START_MS, weekStartMs)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReviewWorker>()
            .setInputData(inputData)
            .addTag(ReviewWorker.TAG)
            .addTag("manual")
            .build()

        workManager.enqueueUniqueWork(
            "${ReviewWorker.UNIQUE_WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        logger.i(LogTags.WORKER, "Manual review triggered")
    }


    /**
     * Schedule weekly cleanup (runs after review, low priority)
     */
    fun scheduleWeeklyCleanup() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<CleanupWorker>(
            repeatInterval = 7,
            repeatIntervalTimeUnit = TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .addTag(CleanupWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            CleanupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        logger.i(LogTags.WORKER, "Weekly cleanup scheduled")
    }

    /**
     * Run cleanup now (manual trigger)
     */
    fun runCleanupNow() {
        val workRequest = OneTimeWorkRequestBuilder<CleanupWorker>()
            .addTag(CleanupWorker.TAG)
            .addTag("manual")
            .build()

        workManager.enqueueUniqueWork(
            "${CleanupWorker.UNIQUE_WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        logger.i(LogTags.WORKER, "Manual cleanup triggered")
    }


    /**
     * Observe SmartDecay worker status
     */
    fun observeSmartDecayStatus(): Flow<WorkerStatus> {
        return observeWorkerStatus(SmartDecayWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Observe Review worker status
     */
    fun observeReviewStatus(): Flow<WorkerStatus> {
        return observeWorkerStatus(ReviewWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Observe Cleanup worker status
     */
    fun observeCleanupStatus(): Flow<WorkerStatus> {
        return observeWorkerStatus(CleanupWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Get all worker statuses as a snapshot
     */
    suspend fun getAllStatuses(): Map<String, WorkerStatus> {
        return mapOf(
            "smartDecay" to getWorkerStatus(SmartDecayWorker.UNIQUE_WORK_NAME),
            "review" to getWorkerStatus(ReviewWorker.UNIQUE_WORK_NAME),
            "cleanup" to getWorkerStatus(CleanupWorker.UNIQUE_WORK_NAME)
        )
    }


    /**
     * Calculate delay to next occurrence of target hour today or tomorrow
     */
    private fun calculateInitialDelay(targetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    /**
     * Calculate delay to next occurrence of target day+hour
     */
    private fun calculateWeeklyDelay(targetDayOfWeek: Int, targetHour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, targetDayOfWeek)
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.WEEK_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    private fun observeWorkerStatus(uniqueWorkName: String): Flow<WorkerStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .map { workInfos ->
                val info = workInfos.firstOrNull()
                if (info == null) {
                    WorkerStatus.NOT_SCHEDULED
                } else {
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> WorkerStatus.SCHEDULED
                        WorkInfo.State.RUNNING -> WorkerStatus.RUNNING
                        WorkInfo.State.SUCCEEDED -> WorkerStatus.COMPLETED
                        WorkInfo.State.FAILED -> WorkerStatus.FAILED
                        WorkInfo.State.BLOCKED -> WorkerStatus.BLOCKED
                        WorkInfo.State.CANCELLED -> WorkerStatus.CANCELLED
                    }
                }
            }
    }

    private suspend fun getWorkerStatus(uniqueWorkName: String): WorkerStatus {
        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
        val info = workInfos.firstOrNull() ?: return WorkerStatus.NOT_SCHEDULED
        return when (info.state) {
            WorkInfo.State.ENQUEUED -> WorkerStatus.SCHEDULED
            WorkInfo.State.RUNNING -> WorkerStatus.RUNNING
            WorkInfo.State.SUCCEEDED -> WorkerStatus.COMPLETED
            WorkInfo.State.FAILED -> WorkerStatus.FAILED
            WorkInfo.State.BLOCKED -> WorkerStatus.BLOCKED
            WorkInfo.State.CANCELLED -> WorkerStatus.CANCELLED
        }
    }
}

/**
 * Simple status enum for worker monitoring
 */
enum class WorkerStatus {
    NOT_SCHEDULED,
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    BLOCKED,
    CANCELLED
}
