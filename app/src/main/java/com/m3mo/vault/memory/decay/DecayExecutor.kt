package com.m3mo.vault.memory.decay

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IDecayStrategy
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.DecayAction
import com.m3mo.vault.domain.model.DecayBatchResult
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * DecayExecutor - Coordinates SmartDecay execution
 *
 * Responsibilities:
 * - Fetch eligible memories from repository
 * - Apply decay strategy
 * - Persist weight changes
 * - Handle archive/tombstone actions
 * - Track execution statistics
 *
 * Usage:
 * ```kotlin
 * val executor = DecayExecutor(strategy, repository, clock, logger)
 * val result = executor.runDecay(batchSize = 500)
 * println("Processed: ${result.totalProcessed}, Changed: ${result.totalChanged}")
 * ```
 */
class DecayExecutor(
    private val strategy: IDecayStrategy,
    private val repository: IMemoryRepository,
    private val clock: Clock,
    private val logger: Logger
) {
    /**
     * Run decay on eligible memories
     *
     * @param batchSize Maximum memories to process
     * @param onProgress Optional callback for progress updates
     * @return Batch result with statistics
     */
    suspend fun runDecay(
        batchSize: Int = 500,
        onProgress: ((Int, Int) -> Unit)? = null
    ): AppResult<DecayBatchResult, AppError> {
        val startTime = clock.nowMillis()
        logger.i(LogTags.DECAY, "Starting decay run, batch size: $batchSize")

        return try {
            // Step 1: Fetch eligible memories
            val fetchResult = repository.getDecayEligible(batchSize)
            val memories = when (fetchResult) {
                is AppResult.Success -> fetchResult.value
                is AppResult.Failure -> {
                    logger.e(LogTags.DECAY, "Failed to fetch eligible memories")
                    return AppResult.failure(fetchResult.error)
                }
            }

            if (memories.isEmpty()) {
                logger.i(LogTags.DECAY, "No eligible memories for decay")
                return AppResult.success(DecayBatchResult.empty())
            }

            logger.d(LogTags.DECAY, "Fetched ${memories.size} eligible memories")

            // Step 2: Apply decay strategy
            coroutineContext.ensureActive()  // Check for cancellation
            val batchResult = strategy.applyBatch(memories, startTime)

            // Step 3: Persist changes
            coroutineContext.ensureActive()
            val persistResult = persistDecayResults(memories, batchResult, onProgress)

            val totalTime = clock.nowMillis() - startTime
            logger.i(
                LogTags.DECAY,
                "Decay run complete: ${batchResult.totalProcessed} processed, " +
                "${batchResult.totalChanged} changed, ${totalTime}ms"
            )

            AppResult.success(
                batchResult.copy(processingTimeMs = totalTime)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DECAY, "Decay run failed", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Decay execution failed",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Run incremental decay (smaller batch, faster)
     * Suitable for background processing
     */
    suspend fun runIncrementalDecay(): AppResult<DecayBatchResult, AppError> {
        return runDecay(batchSize = 100)
    }

    /**
     * Run full decay (larger batch, complete)
     * Suitable for nightly optimization
     */
    suspend fun runFullDecay(): AppResult<DecayBatchResult, AppError> {
        return runDecay(batchSize = 1000)
    }

    /**
     * Boost a memory's weight due to user interaction
     *
     * @param memoryId Memory to boost
     * @param accessWeight Type of access (1=view, 2=search, 3=edit)
     * @return Updated memory or error
     */
    suspend fun boostMemory(
        memoryId: Long,
        accessWeight: Int
    ): AppResult<MemoryItem, AppError> {
        return try {
            val getResult = repository.getById(memoryId)
            val memory = when (getResult) {
                is AppResult.Success -> getResult.value
                is AppResult.Failure -> return AppResult.failure(getResult.error)
            }

            val newWeight = strategy.calculateBoost(memory, accessWeight)
            if (newWeight == memory.weight) {
                return AppResult.success(memory)
            }

            val updatedMemory = memory.withWeight(newWeight)
                .withAccessCount(memory.accessCount + 1)
                .withLastAccessedAt(clock.nowMillis())

            repository.update(updatedMemory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.DECAY, "Failed to boost memory: $memoryId", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Failed to boost memory",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Persist decay results to repository
     */
    private suspend fun persistDecayResults(
        originalMemories: List<MemoryItem>,
        batchResult: DecayBatchResult,
        onProgress: ((Int, Int) -> Unit)?
    ): AppResult<Int, AppError> {
        val memoryMap = originalMemories.associateBy { it.id }
        val toUpdate = mutableListOf<MemoryItem>()
        val toArchive = mutableListOf<Long>()
        val toTombstone = mutableListOf<Long>()

        // Categorize results
        for (result in batchResult.results) {
            if (!result.changed) continue

            val original = memoryMap[result.memoryId] ?: continue

            when (result.action) {
                DecayAction.UPDATE_WEIGHT -> {
                    val updated = original
                        .withWeight(result.newWeight)
                        .withVersion(original.version + 1)
                    toUpdate.add(updated)
                }
                DecayAction.ARCHIVE -> {
                    val archived = original
                        .withWeight(result.newWeight)
                        .withState(ProcessingState.ARCHIVED)
                        .withVersion(original.version + 1)
                    toUpdate.add(archived)
                    toArchive.add(result.memoryId)
                }
                DecayAction.TOMBSTONE -> {
                    toTombstone.add(result.memoryId)
                }
                else -> {}
            }
        }

        // Batch update weights
        if (toUpdate.isNotEmpty()) {
            coroutineContext.ensureActive()
            val updateResult = repository.updateBatch(toUpdate)
            when (updateResult) {
                is AppResult.Success -> {
                    logger.d(LogTags.DECAY, "Updated ${updateResult.value} memories")
                }
                is AppResult.Failure -> {
                    logger.w(LogTags.DECAY, "Partial update failure")
                }
            }
            onProgress?.invoke(toUpdate.size, batchResult.totalProcessed)
        }

        // Delete tombstoned
        if (toTombstone.isNotEmpty()) {
            coroutineContext.ensureActive()
            repository.deleteBatch(toTombstone)
            logger.d(LogTags.DECAY, "Tombstoned ${toTombstone.size} memories")
        }

        logger.d(
            LogTags.DECAY,
            "Persist complete: ${toUpdate.size} updated, ${toArchive.size} archived, ${toTombstone.size} tombstoned"
        )

        return AppResult.success(toUpdate.size)
    }
}
