package com.m3mo.vault.domain.usecase

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.common.IdGenerator
import com.m3mo.vault.core.error.AppError
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.event.MemoryCreatedEvent
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemorySource
import com.m3mo.vault.domain.model.MemoryType

/**
 * CreateMemoryUseCase - Creates a new memory
 *
 * Responsibilities:
 * - Validate input
 * - Generate ID
 * - Create domain object
 * - Persist to repository
 * - Publish creation event
 */
class CreateMemoryUseCase(
    private val repository: IMemoryRepository,
    private val eventBus: EventBus,
    private val idGenerator: IdGenerator,
    private val clock: Clock
) {
    /**
     * Create a new memory from raw input
     *
     * @param content Memory content text
     * @param type Memory type classification
     * @param source Input source
     * @param tags Optional tags
     * @return Created memory or validation error
     */
    suspend fun execute(
        content: String,
        type: MemoryType = MemoryType.FACT,
        source: MemorySource = MemorySource.TEXT,
        tags: List<String> = emptyList()
    ): AppResult<MemoryItem, AppError> {
        // Validate input
        val validationError = validateInput(content)
        if (validationError != null) {
            return AppResult.failure(validationError)
        }

        // Create domain object
        val memory = MemoryItem.create(
            id = idGenerator.nextId(),
            content = content.trim(),
            type = type,
            source = source,
            timestamp = clock.nowMillis()
        ).withTags(tags)

        // Persist
        return repository.insert(memory)
            .suspendOnSuccess { created ->
                // Publish event
                eventBus.publish(MemoryCreatedEvent(created))
            }
    }

    /**
     * Create memory from voice segment
     */
    suspend fun fromVoice(
        content: String,
        type: MemoryType,
        confidence: Float
    ): AppResult<MemoryItem, AppError> {
        val memory = MemoryItem.create(
            id = idGenerator.nextId(),
            content = content.trim(),
            type = type,
            source = MemorySource.VOICE,
            timestamp = clock.nowMillis()
        ).withConfidence((confidence * 5).toInt().coerceIn(1, 5))

        return repository.insert(memory)
            .suspendOnSuccess { created ->
                eventBus.publish(MemoryCreatedEvent(created))
            }
    }

    /**
     * Batch create multiple memories
     */
    suspend fun executeBatch(
        items: List<CreateMemoryRequest>
    ): AppResult<List<MemoryItem>, AppError> {
        if (items.isEmpty()) {
            return AppResult.success(emptyList())
        }

        val memories = items.map { request ->
            MemoryItem.create(
                id = idGenerator.nextId(),
                content = request.content.trim(),
                type = request.type,
                source = request.source,
                timestamp = clock.nowMillis()
            ).withTags(request.tags)
        }

        return repository.insertBatch(memories)
            .suspendOnSuccess { created ->
                created.forEach { memory ->
                    eventBus.publish(MemoryCreatedEvent(memory))
                }
            }
    }

    private fun validateInput(content: String): AppError? {
        val trimmed = content.trim()

        if (trimmed.isEmpty()) {
            return AppError.Validation.MissingRequired(
                field = "content",
                technicalMessage = "Memory content cannot be empty"
            )
        }

        if (trimmed.length < 2) {
            return AppError.Validation.InvalidInput(
                field = "content",
                reason = "Content must be at least 2 characters",
                technicalMessage = "Content too short: ${trimmed.length} chars"
            )
        }

        if (trimmed.length > 10000) {
            return AppError.Validation.OutOfRange(
                field = "content",
                min = 2,
                max = 10000,
                value = trimmed.length,
                technicalMessage = "Content exceeds maximum length"
            )
        }

        return null
    }
}

/**
 * Request for batch creation
 */
data class CreateMemoryRequest(
    val content: String,
    val type: MemoryType = MemoryType.FACT,
    val source: MemorySource = MemorySource.TEXT,
    val tags: List<String> = emptyList()
)
