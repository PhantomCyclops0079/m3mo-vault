package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IDecayStrategy
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.contract.OperationType
import com.m3mo.vault.domain.event.DecayRunCompletedEvent
import com.m3mo.vault.domain.event.DecayRunStartedEvent
import com.m3mo.vault.domain.event.WeightChangedEvent
import com.m3mo.vault.domain.event.WeightChangeReason
import com.m3mo.vault.domain.model.DecayAction
import com.m3mo.vault.domain.model.DecayBatchResult
import com.m3mo.vault.domain.model.ProcessingState

/**
 * RunDecayUseCase - Executes SmartDecay optimization
 *
 * Coordinates record selection, decay calculation, and persistence.
 * It processes memories in batches, applying the 7-factor decay
 * algorithm to each eligible memory.
 */
class RunDecayUseCase(
    private val repository: IMemoryRepository,
    private val decayStrategy: IDecayStrategy,
    private val resourceGovernor: IResourceGovernor,
    private val eventBus: EventBus,
    private val clock: Clock
) {
    /**
     * Run decay optimization with automatic batch sizing
     */
    suspend fun execute(): AppResult<DecayBatchResult, AppError> {
        // Get recommended batch size based on resources
        val batchSize = resourceGovernor.recommendedBatchSize(OperationType.DECAY_BATCH)

        return executeBatch(batchSize)
    }

    /**
     * Run decay with explicit batch size
     */
    suspend fun executeBatch(batchSize: Int): AppResult<DecayBatchResult, AppError> {
        // Request resource budget
        val budget = resourceGovernor.requestBudget(OperationType.DECAY_BATCH)
            ?: return AppResult.failure(
                AppError.Resource.ThermalThrottling(
                    technicalMessage = "Resource budget denied for decay operation"
                )
            )

        try {
            val startTime = clock.nowMillis()

            // Publish start event
            eventBus.publish(DecayRunStartedEvent(batchSize = batchSize))

            // Get eligible memories
            val memoriesResult = repository.getDecayEligible(limit = batchSize)

            return memoriesResult.suspendFlatMap { memories ->
                if (memories.isEmpty()) {
                    return@suspendFlatMap AppResult.success(DecayBatchResult.empty())
                }

                // Apply decay algorithm
                val decayResults = decayStrategy.applyBatch(memories, clock.nowMillis())

                // Process results
                val updatedMemories = mutableListOf<com.m3mo.vault.domain.model.MemoryItem>()
                val archivedIds = mutableListOf<Long>()
                val tombstonedIds = mutableListOf<Long>()

                for (result in decayResults.results) {
                    if (!result.changed) continue

                    val memory = memories.find { it.id == result.memoryId } ?: continue

                    when (result.action) {
                        DecayAction.UPDATE_WEIGHT -> {
                            val updated = memory.withWeight(result.newWeight)
                            updatedMemories.add(updated)

                            eventBus.publish(
                                WeightChangedEvent(
                                    memoryId = result.memoryId,
                                    previousWeight = result.originalWeight,
                                    newWeight = result.newWeight,
                                    reason = WeightChangeReason.DECAY
                                )
                            )
                        }

                        DecayAction.ARCHIVE -> {
                            val archived = memory
                                .withWeight(result.newWeight)
                                .withState(ProcessingState.ARCHIVED)
                            updatedMemories.add(archived)
                            archivedIds.add(result.memoryId)
                        }

                        DecayAction.TOMBSTONE -> {
                            tombstonedIds.add(result.memoryId)
                        }

                        else -> {}
                    }
                }

                // Batch update
                if (updatedMemories.isNotEmpty()) {
                    repository.updateBatch(updatedMemories)
                }

                // Batch tombstone
                if (tombstonedIds.isNotEmpty()) {
                    repository.deleteBatch(tombstonedIds)
                }

                val processingTime = clock.nowMillis() - startTime

                val finalResult = DecayBatchResult(
                    results = decayResults.results,
                    totalProcessed = memories.size,
                    totalChanged = updatedMemories.size,
                    totalArchived = archivedIds.size,
                    totalTombstoned = tombstonedIds.size,
                    processingTimeMs = processingTime,
                    processedAt = clock.nowMillis()
                )

                // Publish completion event
                eventBus.publish(DecayRunCompletedEvent(result = finalResult))

                AppResult.success(finalResult)
            }
        } finally {
            resourceGovernor.releaseBudget(budget)
        }
    }

    /**
     * Boost memory weight due to user interaction
     */
    suspend fun boostMemory(
        memoryId: Long,
        accessWeight: Int = 1
    ): AppResult<Unit, AppError> {
        return repository.getById(memoryId).suspendFlatMap { memory ->
            val newWeight = decayStrategy.calculateBoost(memory, accessWeight)

            if (newWeight == memory.weight) {
                return@suspendFlatMap AppResult.success(Unit)
            }

            val updated = memory.withWeight(newWeight)
            repository.update(updated).map { }
        }
    }
}
