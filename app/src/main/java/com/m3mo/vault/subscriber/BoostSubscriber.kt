package com.m3mo.vault.subscriber

import com.m3mo.vault.behavior.MemoryBooster
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.Subscriber
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.event.AccessType
import com.m3mo.vault.domain.event.MemoryAccessedEvent
import com.m3mo.vault.domain.event.MemoryPinnedEvent

/**
 * BoostSubscriber - Automatic Memory Weight Boosting
 *
 * Listens to user interaction events and triggers the MemoryBooster
 * to increase memory weights accordingly. This is the "positive
 * feedback" side of SmartDecay — frequently used memories get stronger.
 *
 * ## Boost Rules (mapped from AccessType)
 *
 * | AccessType | Action | Boost |
 * |------------|--------|-------|
 * | VIEW | onMemoryViewed | +1 |
 * | SEARCH_CLICK | onSearchResultClicked | +2 |
 * | EDIT | onMemoryEdited | +3 |
 * | REVIEW | onReviewKeep | +2 |
 * | SEARCH_RESULT | (no boost, just appeared) | — |
 * | MemoryPinnedEvent | onMemoryPinned | → MAX |
 */
class BoostSubscriber(
    eventBus: EventBus,
    logger: Logger,
    private val memoryBooster: MemoryBooster
) : Subscriber(eventBus, logger) {

    override val name: String = "BoostSubscriber"

    init {
        on<MemoryAccessedEvent> { event ->
            when (event.accessType) {
                AccessType.VIEW -> {
                    memoryBooster.onMemoryViewed(event.memoryId)
                    logger.d(LogTags.DECAY, "Boost: memory ${event.memoryId} viewed")
                }
                AccessType.SEARCH_CLICK -> {
                    memoryBooster.onSearchResultClicked(event.memoryId)
                    logger.d(LogTags.DECAY, "Boost: memory ${event.memoryId} clicked from search")
                }
                AccessType.EDIT -> {
                    memoryBooster.onMemoryEdited(event.memoryId)
                    logger.d(LogTags.DECAY, "Boost: memory ${event.memoryId} edited")
                }
                AccessType.REVIEW -> {
                    memoryBooster.onReviewKeep(event.memoryId)
                    logger.d(LogTags.DECAY, "Boost: memory ${event.memoryId} reviewed")
                }
                AccessType.SEARCH_RESULT -> {
                    // No boost for simply appearing in search results
                }
            }
        }

        on<MemoryPinnedEvent> { event ->
            if (event.isPinned) {
                memoryBooster.onMemoryPinned(event.memoryId)
                logger.d(LogTags.DECAY, "Boost: memory ${event.memoryId} pinned")
            }
        }
    }

    override fun onHandlerError(event: Any, error: Exception) {
        logger.w(LogTags.DECAY,
            "Boost failed for ${event::class.simpleName}: ${error.message}"
        )
    }
}
