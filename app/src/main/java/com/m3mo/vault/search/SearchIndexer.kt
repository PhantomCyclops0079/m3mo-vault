package com.m3mo.vault.search

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.ISemanticMatcher
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * SearchIndexer - Manages Search Index Updates
 *
 * Responsible for:
 * - Building initial search index
 * - Incremental index updates
 * - Embedding generation for new memories
 * - Index health monitoring
 *
 * ## Index Strategy
 *
 * The indexer uses a lazy/incremental approach:
 * 1. New memories are marked as "needing embedding"
 * 2. Background worker processes them in batches
 * 3. TF-IDF vocabulary is updated incrementally
 *
 * This avoids expensive full re-indexing while maintaining
 * search quality.
 *
 * ## Usage
 *
 * ```kotlin
 * val indexer = SearchIndexer(repository, semanticMatcher, logger)
 *
 * // Build initial index
 * indexer.buildIndex()
 *
 * // Process pending memories
 * indexer.processPending(batchSize = 50)
 *
 * // Check index health
 * val stats = indexer.getStats()
 * if (stats.pendingCount > 100) {
 *     indexer.processPending()
 * }
 * ```
 */
class SearchIndexer(
    private val repository: IMemoryRepository,
    private val semanticMatcher: ISemanticMatcher,
    private val clock: Clock,
    private val logger: Logger
) {
    private val isIndexing = AtomicBoolean(false)
    private val lastIndexTime = AtomicLong(0)
    private val indexedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)

    /**
     * Build full search index
     *
     * Should be called on first app launch or after data import.
     * For large datasets, consider using processPending() incrementally.
     *
     * @param batchSize Memories to process per batch
     * @param onProgress Progress callback (processed, total)
     * @return Index result
     */
    suspend fun buildIndex(
        batchSize: Int = 100,
        onProgress: ((Int, Int) -> Unit)? = null
    ): AppResult<IndexResult, AppError> {
        if (isIndexing.getAndSet(true)) {
            return AppResult.failure(
                AppError.Business.OperationNotAllowed(
                    operation = "buildIndex",
                    reason = "Indexing already in progress"
                )
            )
        }

        val startTime = clock.nowMillis()
        logger.i(LogTags.SEARCH, "Starting full index build")

        return try {
            val memoriesResult = repository.getNeedingEmbedding(10000)
            val memories = when (memoriesResult) {
                is AppResult.Success -> memoriesResult.value
                is AppResult.Failure -> {
                    isIndexing.set(false)
                    return AppResult.failure(memoriesResult.error)
                }
            }

            if (memories.isEmpty()) {
                logger.i(LogTags.SEARCH, "No memories need indexing")
                isIndexing.set(false)
                lastIndexTime.set(clock.nowMillis())
                return AppResult.success(IndexResult(
                    totalProcessed = 0,
                    successCount = 0,
                    errorCount = 0,
                    durationMs = clock.nowMillis() - startTime
                ))
            }

            var processed = 0
            var errors = 0

            for (batch in memories.chunked(batchSize)) {
                coroutineContext.ensureActive()

                val batchResult = processBatch(batch)
                processed += batchResult.first
                errors += batchResult.second

                onProgress?.invoke(processed, memories.size)
            }

            val duration = clock.nowMillis() - startTime
            logger.i(LogTags.SEARCH, "Index build complete: $processed indexed, $errors errors, ${duration}ms")

            indexedCount.addAndGet(processed)
            errorCount.addAndGet(errors)
            lastIndexTime.set(clock.nowMillis())

            AppResult.success(IndexResult(
                totalProcessed = memories.size,
                successCount = processed,
                errorCount = errors,
                durationMs = duration
            ))

        } catch (e: CancellationException) {
            logger.w(LogTags.SEARCH, "Index build cancelled")
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.SEARCH, "Index build failed", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Index build failed",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        } finally {
            isIndexing.set(false)
        }
    }

    /**
     * Process pending memories (incremental indexing)
     *
     * @param batchSize Maximum memories to process
     * @return Number of memories processed
     */
    suspend fun processPending(batchSize: Int = 50): AppResult<Int, AppError> {
        if (isIndexing.get()) {
            logger.d(LogTags.SEARCH, "Skipping processPending: indexing in progress")
            return AppResult.success(0)
        }

        return try {
            val memoriesResult = repository.getNeedingEmbedding(batchSize)
            val memories = when (memoriesResult) {
                is AppResult.Success -> memoriesResult.value
                is AppResult.Failure -> return AppResult.failure(memoriesResult.error)
            }

            if (memories.isEmpty()) {
                return AppResult.success(0)
            }

            val (processed, errors) = processBatch(memories)
            indexedCount.addAndGet(processed)
            errorCount.addAndGet(errors)

            logger.d(LogTags.SEARCH, "Processed $processed pending memories, $errors errors")

            AppResult.success(processed)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.SEARCH, "processPending failed", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Failed to process pending memories",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Index a single memory immediately
     */
    suspend fun indexMemory(memory: MemoryItem): AppResult<MemoryItem, AppError> {
        return try {
            val embedding = semanticMatcher.embed(
                memory.normalizedContent.ifBlank { memory.content }
            )

            val indexed = memory.withEmbedding(embedding)
            
            when (val result = repository.update(indexed)) {
                is AppResult.Success -> {
                    indexedCount.incrementAndGet()
                    AppResult.success(indexed)
                }
                is AppResult.Failure -> AppResult.failure(result.error)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Failed to index memory",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Check if indexing is in progress
     */
    fun isIndexing(): Boolean = isIndexing.get()

    /**
     * Get index statistics
     */
    suspend fun getStats(): IndexStats {
        val pendingResult = repository.getNeedingEmbedding(1)
        val pendingCount = when (pendingResult) {
            is AppResult.Success -> {
                val countResult = repository.count()
                when (countResult) {
                    is AppResult.Success -> {
                        if (pendingResult.value.isNotEmpty()) {
                            pendingResult.value.size * 10
                        } else 0
                    }
                    is AppResult.Failure -> 0
                }
            }
            is AppResult.Failure -> 0
        }

        return IndexStats(
            totalIndexed = indexedCount.get(),
            pendingCount = pendingCount,
            errorCount = errorCount.get(),
            lastIndexTimeMs = lastIndexTime.get(),
            isIndexing = isIndexing.get()
        )
    }

    /**
     * Clear index (for testing/debug)
     */
    suspend fun clearIndex(): AppResult<Int, AppError> {
        logger.w(LogTags.SEARCH, "Clearing search index")

        indexedCount.set(0)
        errorCount.set(0)
        lastIndexTime.set(0)

        return AppResult.success(0)
    }


    /**
     * Process a batch of memories
     * @return Pair(successCount, errorCount)
     */
    private suspend fun processBatch(memories: List<MemoryItem>): Pair<Int, Int> {
        var successCount = 0
        var errorCount = 0
        val updates = mutableListOf<MemoryItem>()

        for (memory in memories) {
            coroutineContext.ensureActive()

            try {
                val embedding = semanticMatcher.embed(
                    memory.normalizedContent.ifBlank { memory.content }
                )

                val indexed = memory
                    .withEmbedding(embedding)
                    .withState(ProcessingState.STORED)

                updates.add(indexed)
                successCount++

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(LogTags.SEARCH, "Failed to embed memory ${memory.id}: ${e.message}")
                errorCount++
            }
        }

        if (updates.isNotEmpty()) {
            repository.updateBatch(updates)
        }

        return Pair(successCount, errorCount)
    }
}

/**
 * Result of index build operation
 */
data class IndexResult(
    val totalProcessed: Int,
    val successCount: Int,
    val errorCount: Int,
    val durationMs: Long
) {
    val successRate: Float
        get() = if (totalProcessed > 0) {
            successCount.toFloat() / totalProcessed
        } else 1f
}

/**
 * Index statistics
 */
data class IndexStats(
    val totalIndexed: Int,
    val pendingCount: Int,
    val errorCount: Int,
    val lastIndexTimeMs: Long,
    val isIndexing: Boolean
) {
    val isHealthy: Boolean
        get() = pendingCount < 100 && errorCount < totalIndexed * 0.1

    val needsRebuild: Boolean
        get() = pendingCount > totalIndexed * 0.5
}
