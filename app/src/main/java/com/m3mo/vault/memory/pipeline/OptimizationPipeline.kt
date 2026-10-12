package com.m3mo.vault.memory.pipeline

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * OptimizationPipeline - Three-stage Memory Optimization Pipeline
 *
 * Coordinates the complete optimization flow:
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ Stage 1: Normalization + Classification                 │
 * │ - Text normalization (Unicode, whitespace, case)        │
 * │ - Auto-classification (FACT/EPISODE/TASK)               │
 * │ - Keyword extraction                                    │
 * └─────────────────────────────────────────────────────────┘
 * ↓
 * ┌─────────────────────────────────────────────────────────┐
 * │ Stage 2: Vectorization + Clustering (ADAPTIVE)          │
 * │ - TF-IDF vectorization                                  │
 * │ - Embedding generation                                  │
 * │ - Similar memory clustering                             │
 * │ - May be SKIPPED on resource-constrained devices        │
 * └─────────────────────────────────────────────────────────┘
 * ↓
 * ┌─────────────────────────────────────────────────────────┐
 * │ Stage 3: SmartDecay + Storage Strategy                  │
 * │ - 7-factor intelligent decay                            │
 * │ - Archive/tombstone decisions                           │
 * │ - Tiered storage recommendations                        │
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val pipeline = OptimizationPipeline(stage1, stage2, stage3, repository, logger)
 *
 * // Run optimization
 * val result = pipeline.run(memories, config, budget)
 *
 * // Or run incrementally
 * val result = pipeline.runIncremental()
 * ```
 *
 * ## Patent Reference
 * US Provisional Patent #63/942,399 - SmartDecay Technology
 */
class OptimizationPipeline(
    private val stage1: Stage1Executor,
    private val stage2: Stage2Executor,
    private val stage3: Stage3Executor,
    private val repository: IMemoryRepository,
    private val logger: Logger
) {
    /**
     * Run the complete optimization pipeline
     *
     * @param memories Memories to optimize
     * @param config Pipeline configuration
     * @param budget Resource budget
     * @return Pipeline result
     */
    suspend fun run(
        memories: List<MemoryItem>,
        config: PipelineConfig = PipelineConfig.default(),
        budget: ResourceBudget = ResourceBudget.default()
    ): AppResult<PipelineResult, AppError> {
        logger.i(LogTags.PIPELINE, "Starting optimization pipeline with ${memories.size} memories")

        val context = PipelineContext(
            inputMemories = memories,
            config = config,
            budget = budget
        )

        return try {
            context.start()
            budget.start()

            coroutineContext.ensureActive()
            val stage1Result = stage1.execute(context)
            context.completeStage(PipelineStage.STAGE_1_NORMALIZE, stage1Result)

            if (!context.shouldContinue()) {
                return finishEarly(context, "Stage 1")
            }

            coroutineContext.ensureActive()
            val stage2Result = stage2.execute(context)
            context.completeStage(PipelineStage.STAGE_2_VECTORIZE, stage2Result)

            if (!context.shouldContinue()) {
                return finishEarly(context, "Stage 2")
            }

            coroutineContext.ensureActive()
            val stage3Result = stage3.execute(context)
            context.completeStage(PipelineStage.STAGE_3_DECAY, stage3Result)

            coroutineContext.ensureActive()
            persistResults(context)

            context.completeStage(PipelineStage.FINALIZATION, StageResult.success(
                stage = PipelineStage.FINALIZATION,
                inputCount = context.processedMemories.size,
                outputCount = context.processedMemories.size,
                durationMs = 0
            ))

            val result = context.getResult()
            logger.i(LogTags.PIPELINE, "Pipeline complete: ${result.toSummary()}")

            AppResult.success(result)

        } catch (e: CancellationException) {
            context.cancel()
            logger.w(LogTags.PIPELINE, "Pipeline cancelled")
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.PIPELINE, "Pipeline failed", e)
            AppResult.failure(
                AppError.Internal.UnexpectedError(
                    message = "Optimization pipeline failed",
                    technicalMessage = e.message ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Run incremental optimization
     * Suitable for background/periodic execution
     */
    suspend fun runIncremental(): AppResult<PipelineResult, AppError> {

        val newMemoriesResult = repository.getByStates(setOf(ProcessingState.NEW, ProcessingState.QUEUED), 50)
        val newMemories = (newMemoriesResult as? AppResult.Success)?.value ?: emptyList()

        val decayEligibleResult = repository.getDecayEligible(50)
        val decayEligible = (decayEligibleResult as? AppResult.Success)?.value ?: emptyList()

        val allMemories = (newMemories + decayEligible).distinctBy { it.id }

        if (allMemories.isEmpty()) {
            logger.d(LogTags.PIPELINE, "No memories to optimize")
            return AppResult.success(PipelineResult(
                inputCount = 0,
                processedCount = 0,
                errorCount = 0,
                stageResults = emptyMap(),
                totalDurationMs = 0,
                processedMemories = emptyList(),
                wasCancelled = false
            ))
        }

        return run(
            memories = allMemories,
            config = PipelineConfig.minimal(),
            budget = ResourceBudget.quick()
        )
    }

    /**
     * Run full nightly optimization
     * Suitable for when device is charging
     */
    suspend fun runFull(): AppResult<PipelineResult, AppError> {

        val newMemoriesResult = repository.getByStates(setOf(ProcessingState.NEW, ProcessingState.QUEUED), 200)
        val newMemories = (newMemoriesResult as? AppResult.Success)?.value ?: emptyList()

        val fetchResult = repository.getDecayEligible(1000)
        val decayEligible = when (fetchResult) {
            is AppResult.Success -> fetchResult.value
            is AppResult.Failure -> return AppResult.failure(fetchResult.error)
        }

        val allMemories = (newMemories + decayEligible).distinctBy { it.id }

        return run(
            memories = allMemories,
            config = PipelineConfig.full(),
            budget = ResourceBudget.full()
        )
    }

    /**
     * Persist pipeline results to repository
     */
    private suspend fun persistResults(context: PipelineContext) {
        val toUpdate = context.processedMemories

        if (toUpdate.isEmpty()) {
            logger.d(LogTags.PIPELINE, "No results to persist")
            return
        }

        logger.d(LogTags.PIPELINE, "Persisting ${toUpdate.size} optimized memories")

        when (val result = repository.updateBatch(toUpdate)) {
            is AppResult.Success -> {
                logger.d(LogTags.PIPELINE, "Persisted ${result.value} memories")
            }
            is AppResult.Failure -> {
                logger.e(LogTags.PIPELINE, "Failed to persist: ${result.error}")
            }
        }
    }

    /**
     * Handle early termination
     */
    private fun finishEarly(context: PipelineContext, stage: String): AppResult<PipelineResult, AppError> {
        logger.w(LogTags.PIPELINE, "Pipeline terminated early at $stage")

        val result = context.getResult()
        return AppResult.success(result)
    }
}

/**
 * Factory for creating OptimizationPipeline instances
 */
class OptimizationPipelineFactory(
    private val stage1: Stage1Executor,
    private val stage2: Stage2Executor,
    private val stage3: Stage3Executor,
    private val repository: IMemoryRepository,
    private val logger: Logger
) {
    fun create(): OptimizationPipeline {
        return OptimizationPipeline(
            stage1 = stage1,
            stage2 = stage2,
            stage3 = stage3,
            repository = repository,
            logger = logger
        )
    }
}