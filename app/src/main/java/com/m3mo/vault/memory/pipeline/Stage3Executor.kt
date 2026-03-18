package com.m3mo.vault.memory.pipeline

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.IDecayStrategy
import com.m3mo.vault.domain.model.DecayAction
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import kotlinx.coroutines.CancellationException

/**
 * Stage3Executor - SmartDecay and Storage Strategy
 *
 * Stage 3 of the optimization pipeline:
 * - Apply SmartDecay 7-factor algorithm
 * - Determine storage tier (hot/warm/cold)
 * - Identify archive/tombstone candidates
 *
 * This is the core memory lifecycle management stage.
 */
class Stage3Executor(
    private val decayStrategy: IDecayStrategy,
    private val logger: Logger
) {
    /**
     * Execute Stage 3 on pipeline context
     */
    suspend fun execute(context: PipelineContext): StageResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<PipelineError>()

        // 🔥 FIX BUG 2: Stale Data in Decay
        // We must use the LATEST version of the memories.
        // Some memories might have been updated in Stage 1 (normalized/classified)
        // or Stage 2 (vectorized).

        // 1. Start with all input memories
        val latestMemoriesMap = context.inputMemories.associateBy { it.id }.toMutableMap()

        // 2. Overwrite with any processed (updated) memories from previous stages
        context.processedMemories.forEach { memory ->
            latestMemoriesMap[memory.id] = memory
        }

        // 3. Filter for eligibility using the LATEST state
        val inputMemories = latestMemoriesMap.values.filter {
            decayStrategy.isEligible(it)
        }

        if (inputMemories.isEmpty()) {
            logger.d(LogTags.PIPELINE, "Stage 3: No memories eligible for decay")
            return StageResult.success(
                stage = PipelineStage.STAGE_3_DECAY,
                inputCount = 0,
                outputCount = 0,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        logger.d(LogTags.PIPELINE, "Stage 3: Processing ${inputMemories.size} memories for decay")

        // Apply decay in batch
        val currentTime = System.currentTimeMillis()
        val batchResult = decayStrategy.applyBatch(inputMemories, currentTime)

        // Process results
        val updatedMemories = mutableListOf<MemoryItem>()
        val archiveCandidates = mutableListOf<Long>()
        val tombstoneCandidates = mutableListOf<Long>()

        // We use the latest map to look up originals for modification
        val memoryMap = inputMemories.associateBy { it.id }

        for (result in batchResult.results) {
            if (!context.shouldContinue()) {
                logger.d(LogTags.PIPELINE, "Stage 3: Cancelled or budget exhausted")
                break
            }

            val original = memoryMap[result.memoryId] ?: continue

            when (result.action) {
                DecayAction.UPDATE_WEIGHT -> {
                    val updated = original
                        .withWeight(result.newWeight)
                        .withState(ProcessingState.OPTIMIZED)
                    updatedMemories.add(updated)
                }
                DecayAction.ARCHIVE -> {
                    val archived = original
                        .withWeight(result.newWeight)
                        .withState(ProcessingState.ARCHIVED)
                    updatedMemories.add(archived)
                    archiveCandidates.add(result.memoryId)
                }
                DecayAction.TOMBSTONE -> {
                    val tombstoned = original
                        .withState(ProcessingState.TOMBSTONED)
                    updatedMemories.add(tombstoned)
                    tombstoneCandidates.add(result.memoryId)
                }
                DecayAction.NONE, DecayAction.BOOST -> {
                    // No change needed
                }
            }

            context.budget.recordItem()
        }

        // Add results to context (these are the final versions)
        context.addProcessedMemories(updatedMemories)

        val duration = System.currentTimeMillis() - startTime
        logger.d(
            LogTags.PIPELINE,
            "Stage 3: Processed ${batchResult.totalProcessed}, " +
                    "Changed: ${batchResult.totalChanged}, " +
                    "Archived: ${archiveCandidates.size}, " +
                    "Tombstoned: ${tombstoneCandidates.size} in ${duration}ms"
        )

        return StageResult(
            stage = PipelineStage.STAGE_3_DECAY,
            inputCount = inputMemories.size,
            outputCount = updatedMemories.size,
            durationMs = duration,
            success = true,
            errors = errors,
            metrics = mapOf(
                "totalChanged" to batchResult.totalChanged,
                "archived" to archiveCandidates.size,
                "tombstoned" to tombstoneCandidates.size,
                "avgDecayFactors" to calculateAvgDecay(batchResult)
            )
        )
    }

    /**
     * Calculate average decay for metrics
     */
    private fun calculateAvgDecay(
        result: com.m3mo.vault.domain.model.DecayBatchResult
    ): Double {
        if (result.results.isEmpty()) return 0.0

        val totalDecay = result.results.sumOf {
            (it.originalWeight - it.newWeight).toDouble()
        }
        return totalDecay / result.results.size
    }
}