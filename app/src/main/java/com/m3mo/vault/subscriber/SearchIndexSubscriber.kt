package com.m3mo.vault.subscriber

import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.Subscriber
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.event.MemoryCreatedEvent
import com.m3mo.vault.domain.event.MemoryDeletedEvent
import com.m3mo.vault.domain.event.MemoryUpdatedEvent
import com.m3mo.vault.domain.model.ProcessingState

/**
 * SearchIndexSubscriber - Keeps Search Index in Sync
 *
 * Listens to memory lifecycle events and logs index-related actions.
 * FTS4 indexing happens automatically via Room triggers on insert/update.
 * Semantic embedding generation is handled by OptimizationPipeline (Stage 2).
 *
 * This subscriber's role:
 * - Log index events for debugging
 * - Mark memories as needing re-embedding (future: set state to DIRTY)
 */
class SearchIndexSubscriber(
    eventBus: EventBus,
    logger: Logger
) : Subscriber(eventBus, logger) {

    override val name: String = "SearchIndexSubscriber"

    init {
        on<MemoryCreatedEvent> { event ->
            val memory = event.memory
            if (memory.embedding == null && memory.content.isNotBlank()) {
                logger.d(LogTags.SEARCH,
                    "Memory ${memory.id} created, embedding pending (pipeline will handle)"
                )
            }
        }

        on<MemoryUpdatedEvent> { event ->
            val memory = event.memory
            if (memory.state != ProcessingState.TOMBSTONED && memory.content.isNotBlank()) {
                logger.d(LogTags.SEARCH,
                    "Memory ${memory.id} updated, embedding refresh pending"
                )
            }
        }

        on<MemoryDeletedEvent> { event ->
            logger.d(LogTags.SEARCH, "Memory ${event.memoryId} removed from search index")
        }
    }

    override fun onHandlerError(event: Any, error: Exception) {
        logger.w(LogTags.SEARCH,
            "Search index event failed for ${event::class.simpleName}: ${error.message}"
        )
    }
}
