package com.m3mo.vault.subscriber

import com.m3mo.vault.behavior.UsageTracker
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.Subscriber
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.event.DecayRunCompletedEvent
import com.m3mo.vault.domain.event.MemoryAccessedEvent
import com.m3mo.vault.domain.event.MemoryCreatedEvent
import com.m3mo.vault.domain.event.MemoryDeletedEvent
import com.m3mo.vault.domain.event.MemoryUpdatedEvent
import com.m3mo.vault.domain.event.SearchPerformedEvent
import com.m3mo.vault.domain.event.TaskCompletedEvent

/**
 * MetricsSubscriber - Bridges Domain Events to UsageTracker
 *
 * Listens to all relevant domain events and records metrics
 * via UsageTracker. Keeps metric tracking decoupled from
 * business logic.
 */
class MetricsSubscriber(
    eventBus: EventBus,
    logger: Logger,
    private val usageTracker: UsageTracker
) : Subscriber(eventBus, logger) {

    override val name: String = "MetricsSubscriber"

    init {
        on<MemoryCreatedEvent> { event ->
            usageTracker.trackMemoryCreated(event.memory.type.name)
        }

        on<MemoryUpdatedEvent> { event ->
            usageTracker.trackMemoryUpdated()
        }

        on<MemoryDeletedEvent> { event ->
            usageTracker.trackMemoryDeleted()
        }

        on<MemoryAccessedEvent> { event ->
            usageTracker.trackMemoryViewed()
        }

        on<SearchPerformedEvent> { event ->
            usageTracker.trackSearch(
                query = event.query,
                resultsCount = event.resultCount,
                searchTimeMs = event.searchTimeMs
            )
        }

        on<TaskCompletedEvent> { event ->
            if (event.isCompleted) {
                usageTracker.trackTaskCompleted()
            }
        }

        on<DecayRunCompletedEvent> { event ->
            val batchResult = event.result
            logger.d(LogTags.METRICS,
                "Decay run: ${batchResult.results.size} processed, " +
                "${batchResult.results.count { it.changed }} changed"
            )
        }
    }

    override fun onHandlerError(event: Any, error: Exception) {
        logger.w(LogTags.METRICS, "Metrics tracking failed for ${event::class.simpleName}: ${error.message}")
    }
}
