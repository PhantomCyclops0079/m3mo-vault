package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.event.DeletionReason
import com.m3mo.vault.domain.event.MemoryDeletedEvent

/**
 * DeleteMemoryUseCase - Deletes memories (soft delete)
 *
 * Memories are tombstoned, not immediately deleted.
 * Physical deletion happens during GC cycles.
 */
class DeleteMemoryUseCase(
    private val repository: IMemoryRepository,
    private val eventBus: EventBus
) {
    /**
     * Delete a single memory
     */
    suspend fun execute(
        id: Long,
        reason: DeletionReason = DeletionReason.USER_REQUEST
    ): AppResult<Unit, AppError> {
        return repository.delete(id).suspendOnSuccess {
            eventBus.publish(
                MemoryDeletedEvent(
                    memoryId = id,
                    reason = reason
                )
            )
        }
    }

    /**
     * Delete multiple memories
     */
    suspend fun executeBatch(
        ids: List<Long>,
        reason: DeletionReason = DeletionReason.USER_REQUEST
    ): AppResult<Int, AppError> {
        if (ids.isEmpty()) {
            return AppResult.success(0)
        }

        return repository.deleteBatch(ids).suspendOnSuccess { count ->
            ids.forEach { id ->
                eventBus.publish(
                    MemoryDeletedEvent(
                        memoryId = id,
                        reason = reason
                    )
                )
            }
        }
    }

    /**
     * Hard delete (permanent) - use with caution
     */
    suspend fun hardDelete(id: Long): AppResult<Unit, AppError> {
        return repository.hardDelete(id).suspendOnSuccess {
            eventBus.publish(
                MemoryDeletedEvent(
                    memoryId = id,
                    reason = DeletionReason.CLEANUP
                )
            )
        }
    }
}
