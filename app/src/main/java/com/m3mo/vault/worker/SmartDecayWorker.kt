package com.m3mo.vault.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.contract.OperationType
import com.m3mo.vault.domain.usecase.RunDecayUseCase

/**
 * SmartDecayWorker - Background Worker for SmartDecay Optimization
 *
 * Delegates to RunDecayUseCase which handles the full decay pipeline:
 * get eligible memories → apply 7-factor SmartDecay → update DB → publish events.
 *
 * This worker only handles WorkManager lifecycle, resource checks, and retry logic.
 * All business logic lives in RunDecayUseCase (single source of truth).
 *
 * ## Scheduling
 * - Default: Once daily during idle/charging
 * - Manual: Can be triggered from Settings (force_run=true)
 *
 * ## Output Data
 * - KEY_MEMORIES_PROCESSED: total eligible memories
 * - KEY_MEMORIES_CHANGED: memories with weight changes
 * - KEY_DURATION_MS: execution time
 *
 * Dependencies are injected via M3moWorkerFactory (Batch 8 DI).
 */
class SmartDecayWorker(
    context: Context,
    params: WorkerParameters,
    private val runDecayUseCase: RunDecayUseCase,
    private val resourceGovernor: IResourceGovernor,
    private val clock: Clock,
    private val logger: Logger
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "SmartDecayWorker"
        const val UNIQUE_WORK_NAME = "m3mo_smart_decay"

        const val KEY_FORCE_RUN = "force_run"
        const val KEY_MAX_BATCH_SIZE = "max_batch_size"

        const val KEY_MEMORIES_PROCESSED = "memories_processed"
        const val KEY_MEMORIES_CHANGED = "memories_changed"
        const val KEY_DURATION_MS = "duration_ms"

        private const val DEFAULT_BATCH_SIZE = 500
    }

    override suspend fun doWork(): Result {
        val startTime = clock.nowMillis()
        val forceRun = inputData.getBoolean(KEY_FORCE_RUN, false)

        logger.i(LogTags.WORKER, "SmartDecay worker starting (attempt=${runAttemptCount}, force=$forceRun)")

        if (!forceRun) {
            val budget = resourceGovernor.requestBudget(OperationType.DECAY_BATCH)
            if (budget == null) {
                logger.w(LogTags.WORKER, "Resource budget denied, retrying later")
                return Result.retry()
            }
        }

        try {
            val maxBatch = inputData.getInt(KEY_MAX_BATCH_SIZE, DEFAULT_BATCH_SIZE)
            val batchSize = if (forceRun) {
                maxBatch
            } else {
                resourceGovernor.recommendedBatchSize(OperationType.DECAY_BATCH)
                    .coerceAtMost(maxBatch)
            }

            val decayResult = runDecayUseCase.executeBatch(batchSize)

            val duration = clock.nowMillis() - startTime

            return when (decayResult) {
                is AppResult.Success -> {
                    val batchResult = decayResult.value
                    val processed = batchResult.results.size
                    val changed = batchResult.results.count { it.changed }

                    logger.i(LogTags.WORKER,
                        "SmartDecay complete in ${duration}ms: $processed processed, $changed changed"
                    )

                    Result.success(
                        Data.Builder()
                            .putInt(KEY_MEMORIES_PROCESSED, processed)
                            .putInt(KEY_MEMORIES_CHANGED, changed)
                            .putLong(KEY_DURATION_MS, duration)
                            .build()
                    )
                }
                is AppResult.Failure -> {
                    logger.e(LogTags.WORKER, "SmartDecay failed: ${decayResult.error}")
                    if (runAttemptCount < 3) Result.retry() else Result.failure(
                        Data.Builder()
                            .putString("error", decayResult.error.toString())
                            .putLong(KEY_DURATION_MS, duration)
                            .build()
                    )
                }
            }

        } catch (e: Exception) {
            val duration = clock.nowMillis() - startTime
            logger.e(LogTags.WORKER, "SmartDecay worker failed after ${duration}ms", e)

            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString("error", e.message ?: "Unknown error")
                        .putLong(KEY_DURATION_MS, duration)
                        .build()
                )
            }
        }
    }
}
