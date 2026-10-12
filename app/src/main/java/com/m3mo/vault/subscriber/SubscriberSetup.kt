package com.m3mo.vault.subscriber

import com.m3mo.vault.behavior.MemoryBooster
import com.m3mo.vault.behavior.UsageTracker
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.SubscriberRegistry
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.data.cache.MemoryCache

/**
 * SubscriberSetup - Creates and registers all event subscribers
 *
 * Uses the existing SubscriberRegistry from core/event/Subscriber.kt.
 * Call this during Application.onCreate() after all dependencies are ready.
 *
 * ## Usage
 *
 * ```kotlin
 * val registry = setupSubscribers(
 *     eventBus = eventBus,
 *     logger = logger,
 *     cache = memoryCache,
 *     usageTracker = usageTracker,
 *     memoryBooster = memoryBooster
 * )
 * registry.startAll()
 *
 * // On shutdown:
 * registry.stopAll()
 * ```
 */
fun setupSubscribers(
    eventBus: EventBus,
    logger: Logger,
    cache: MemoryCache,
    usageTracker: UsageTracker,
    memoryBooster: MemoryBooster
): SubscriberRegistry {
    val registry = SubscriberRegistry(logger)

    registry.register(CacheInvalidationSubscriber(eventBus, logger, cache))
    registry.register(SearchIndexSubscriber(eventBus, logger))
    registry.register(BoostSubscriber(eventBus, logger, memoryBooster))
    registry.register(MetricsSubscriber(eventBus, logger, usageTracker))

    return registry
}
