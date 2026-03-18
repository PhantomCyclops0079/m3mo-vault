package com.m3mo.vault.subscriber

import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.Subscriber
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.data.cache.MemoryCache
import com.m3mo.vault.domain.event.DecayRunCompletedEvent
import com.m3mo.vault.domain.event.MemoryCreatedEvent
import com.m3mo.vault.domain.event.MemoryDeletedEvent
import com.m3mo.vault.domain.event.MemoryUpdatedEvent
import com.m3mo.vault.domain.event.WeightChangedEvent

/**
 * CacheInvalidationSubscriber - Keeps In-Memory Cache Consistent
 *
 * Strategy:
 * - Single memory create/update → put (proactive cache)
 * - Single memory delete/weight change → remove(id)
 * - Bulk decay run → clear entire cache
 */
class CacheInvalidationSubscriber(
    eventBus: EventBus,
    logger: Logger,
    private val cache: MemoryCache
) : Subscriber(eventBus, logger) {

    override val name: String = "CacheInvalidationSubscriber"

    init {
        on<MemoryCreatedEvent> { event ->
            cache.put(event.memory)
            logger.d(LogTags.DATA, "Cache: added memory ${event.memory.id}")
        }

        on<MemoryUpdatedEvent> { event ->
            cache.put(event.memory)
            logger.d(LogTags.DATA, "Cache: updated memory ${event.memory.id}")
        }

        on<MemoryDeletedEvent> { event ->
            cache.remove(event.memoryId)
            logger.d(LogTags.DATA, "Cache: removed memory ${event.memoryId}")
        }

        on<WeightChangedEvent> { event ->
            cache.remove(event.memoryId)
        }

        on<DecayRunCompletedEvent> { event ->
            val changedCount = event.result.results.count { it.changed }
            if (changedCount > 0) {
                cache.clear()
                logger.d(LogTags.DATA, "Cache: cleared after decay run ($changedCount changes)")
            }
        }
    }

    override fun onHandlerError(event: Any, error: Exception) {
        logger.w(LogTags.DATA, "Cache invalidation failed: ${error.message}")
        cache.clear()
    }
}