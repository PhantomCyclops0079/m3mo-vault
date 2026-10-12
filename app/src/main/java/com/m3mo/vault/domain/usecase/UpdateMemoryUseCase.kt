package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.event.*
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.domain.model.canTransitionTo
import com.m3mo.vault.domain.model.isActionable

/**
 * UpdateMemoryUseCase - Updates existing memories
 *
 * Handles all memory modifications:
 * - Content editing
 * - Pin/unpin
 * - Task completion
 * - Confidence rating
 * - Weight adjustments
 */
class UpdateMemoryUseCase(
    private val repository: IMemoryRepository,
    private val eventBus: EventBus,
    private val clock: Clock
) {
    /**
     * Update memory content
     */
    suspend fun updateContent(
        id: Long,
        newContent: String
    ): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (newContent.isBlank()) {
                return@suspendFlatMap AppResult.failure(
                    AppError.Validation.MissingRequired(
                        field = "content",
                        technicalMessage = "Content cannot be empty"
                    )
                )
            }

            val updated = memory.copy(
                content = newContent.trim(),
                normalizedContent = newContent.trim().lowercase(),
                updatedAt = clock.nowMillis()
            ).markDirty()

            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryUpdatedEvent(
                        memory = it,
                        previousState = memory.state,
                        previousWeight = memory.weight
                    )
                )
                eventBus.publish(
                    MemoryAccessedEvent(
                        memoryId = id,
                        accessType = AccessType.EDIT
                    )
                )
            }
        }
    }

    /**
     * Toggle pin status
     */
    suspend fun togglePin(id: Long): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            val updated = memory.withPin(!memory.isPinned)

            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryPinnedEvent(
                        memoryId = id,
                        isPinned = it.isPinned
                    )
                )
                if (it.isPinned) {
                    eventBus.publish(
                        WeightChangedEvent(
                            memoryId = id,
                            previousWeight = memory.weight,
                            newWeight = MemoryItem.MAX_WEIGHT,
                            reason = WeightChangeReason.PIN_BOOST
                        )
                    )
                }
            }
        }
    }

    /**
     * Set pin status explicitly
     */
    suspend fun setPin(id: Long, pinned: Boolean): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (memory.isPinned == pinned) {
                return@suspendFlatMap AppResult.success(memory)
            }

            val updated = memory.withPin(pinned)
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryPinnedEvent(memoryId = id, isPinned = pinned)
                )
            }
        }
    }

    /**
     * Toggle task completion
     */
    suspend fun toggleCompletion(id: Long): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (!memory.type.isActionable()) {
                return@suspendFlatMap AppResult.failure(
                    AppError.Business.StateConflict(
                        entity = "Memory",
                        currentState = memory.type.name,
                        requiredState = "TASK",
                        technicalMessage = "Only tasks can be completed"
                    )
                )
            }

            val updated = memory.withCompletion(!memory.isCompleted)

            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    TaskCompletedEvent(
                        memoryId = id,
                        isCompleted = it.isCompleted
                    )
                )
            }
        }
    }

    /**
     * Set task completion explicitly
     */
    suspend fun setCompletion(id: Long, completed: Boolean): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (!memory.type.isActionable()) {
                return@suspendFlatMap AppResult.failure(
                    AppError.Business.StateConflict(
                        entity = "Memory",
                        currentState = memory.type.name,
                        requiredState = "TASK",
                        technicalMessage = "Only tasks can be completed"
                    )
                )
            }

            if (memory.isCompleted == completed) {
                return@suspendFlatMap AppResult.success(memory)
            }

            val updated = memory.withCompletion(completed)
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    TaskCompletedEvent(memoryId = id, isCompleted = completed)
                )
            }
        }
    }

    /**
     * Set user confidence rating (1-5)
     */
    suspend fun setConfidence(id: Long, rating: Int): AppResult<MemoryItem, AppError> {
        if (rating !in 1..5) {
            return AppResult.failure(
                AppError.Validation.OutOfRange(
                    field = "confidence",
                    min = 1,
                    max = 5,
                    value = rating,
                    technicalMessage = "Confidence must be 1-5"
                )
            )
        }

        return repository.getById(id).suspendFlatMap { memory ->
            val updated = memory.withConfidence(rating)
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    WeightChangedEvent(
                        memoryId = id,
                        previousWeight = memory.weight,
                        newWeight = it.weight,
                        reason = WeightChangeReason.CONFIDENCE
                    )
                )
            }
        }
    }

    /**
     * Update tags
     */
    suspend fun setTags(id: Long, tags: List<String>): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            val updated = memory.withTags(tags)
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryUpdatedEvent(
                        memory = it,
                        previousState = null,
                        previousWeight = null
                    )
                )
            }
        }
    }

    /**
     * Add a tag
     */
    suspend fun addTag(id: Long, tag: String): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (tag in memory.tags) {
                return@suspendFlatMap AppResult.success(memory)
            }
            val updated = memory.withTags(memory.tags + tag)
            repository.update(updated)
        }
    }

    /**
     * Remove a tag
     */
    suspend fun removeTag(id: Long, tag: String): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (tag !in memory.tags) {
                return@suspendFlatMap AppResult.success(memory)
            }
            val updated = memory.withTags(memory.tags - tag)
            repository.update(updated)
        }
    }

    /**
     * Change processing state
     */
    suspend fun changeState(
        id: Long,
        newState: ProcessingState
    ): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            if (!memory.state.canTransitionTo(newState)) {
                return@suspendFlatMap AppResult.failure(
                    AppError.Business.StateConflict(
                        entity = "Memory",
                        currentState = memory.state.name,
                        requiredState = newState.name,
                        technicalMessage = "Invalid state transition"
                    )
                )
            }

            val updated = memory.withState(newState)
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryStateChangedEvent(
                        memoryId = id,
                        previousState = memory.state,
                        newState = newState
                    )
                )
            }
        }
    }

    /**
     * Record memory access (view)
     */
    suspend fun recordAccess(id: Long): AppResult<MemoryItem, AppError> {
        return repository.getById(id).suspendFlatMap { memory ->
            val updated = memory.withAccess(clock.nowMillis())
            repository.update(updated).suspendOnSuccess {
                eventBus.publish(
                    MemoryAccessedEvent(
                        memoryId = id,
                        accessType = AccessType.VIEW
                    )
                )
            }
        }
    }
}
