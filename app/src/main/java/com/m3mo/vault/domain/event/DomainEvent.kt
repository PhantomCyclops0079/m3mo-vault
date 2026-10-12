package com.m3mo.vault.domain.event

import com.m3mo.vault.domain.model.DecayBatchResult
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.domain.model.SearchResults

/**
 * DomainEvent - Base marker interface for all domain events
 *
 * Domain events represent something significant that happened in the system.
 * They enable loose coupling between components via the EventBus.
 *
 * Naming convention: Past tense (e.g., MemoryCreated, not CreateMemory)
 */
interface DomainEvent {
    /**
     * When the event occurred
     */
    val timestamp: Long get() = System.currentTimeMillis()
}


/**
 * A new memory was created
 */
data class MemoryCreatedEvent(
    val memory: MemoryItem,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Memory content or metadata was updated
 */
data class MemoryUpdatedEvent(
    val memory: MemoryItem,
    val previousState: ProcessingState?,
    val previousWeight: Int?,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Memory was deleted (tombstoned)
 */
data class MemoryDeletedEvent(
    val memoryId: Long,
    val reason: DeletionReason,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Reason for memory deletion
 */
enum class DeletionReason {
    USER_REQUEST,
    DECAY,
    MERGE,
    CLEANUP
}

/**
 * Memory state changed (state machine transition)
 */
data class MemoryStateChangedEvent(
    val memoryId: Long,
    val previousState: ProcessingState,
    val newState: ProcessingState,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Memory was accessed (search result clicked, viewed, etc.)
 */
data class MemoryAccessedEvent(
    val memoryId: Long,
    val accessType: AccessType,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

enum class AccessType {
    VIEW,
    SEARCH_RESULT,
    SEARCH_CLICK,
    REVIEW,
    EDIT
}

/**
 * Memory was pinned or unpinned
 */
data class MemoryPinnedEvent(
    val memoryId: Long,
    val isPinned: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Task completion state changed
 */
data class TaskCompletedEvent(
    val memoryId: Long,
    val isCompleted: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent


/**
 * Search was performed
 */
data class SearchPerformedEvent(
    val query: String,
    val resultCount: Int,
    val searchTimeMs: Long,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Search results were delivered
 */
data class SearchResultsEvent(
    val results: SearchResults,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent


/**
 * Memory processing started
 */
data class ProcessingStartedEvent(
    val memoryId: Long,
    val stage: ProcessingStage,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Memory processing completed
 */
data class ProcessingCompletedEvent(
    val memoryId: Long,
    val stage: ProcessingStage,
    val durationMs: Long,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Memory processing failed
 */
data class ProcessingFailedEvent(
    val memoryId: Long,
    val stage: ProcessingStage,
    val error: String,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

enum class ProcessingStage {
    NORMALIZATION,
    SEMANTIC,
    OPTIMIZATION
}


/**
 * Decay optimization run started
 */
data class DecayRunStartedEvent(
    val batchSize: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Decay optimization run completed
 */
data class DecayRunCompletedEvent(
    val result: DecayBatchResult,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Individual memory weight changed due to decay
 */
data class WeightChangedEvent(
    val memoryId: Long,
    val previousWeight: Int,
    val newWeight: Int,
    val reason: WeightChangeReason,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

enum class WeightChangeReason {
    DECAY,
    ACCESS_BOOST,
    PIN_BOOST,
    CONFIDENCE,
    MANUAL
}


/**
 * Voice input started
 */
data class VoiceInputStartedEvent(
    val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Voice input completed
 */
data class VoiceInputCompletedEvent(
    val sessionId: String,
    val transcription: String,
    val durationSeconds: Int,
    val confidence: Float,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Voice segments extracted from input
 */
data class SegmentsExtractedEvent(
    val sessionId: String,
    val segments: List<ExtractedSegment>,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Extracted segment from voice input
 */
data class ExtractedSegment(
    val content: String,
    val type: MemoryType,
    val confidence: Float
)


/**
 * Weekly review generation started
 */
data class WeeklyReviewStartedEvent(
    val weekStart: Long,
    val weekEnd: Long,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Weekly review is ready
 */
data class WeeklyReviewReadyEvent(
    val weekStart: Long,
    val weekEnd: Long,
    val memoriesIncluded: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent


/**
 * Database migration started
 */
data class MigrationStartedEvent(
    val fromVersion: Int,
    val toVersion: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Database migration completed
 */
data class MigrationCompletedEvent(
    val fromVersion: Int,
    val toVersion: Int,
    val durationMs: Long,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Low memory condition detected
 */
data class LowMemoryEvent(
    val availableMemoryMb: Int,
    val threshold: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent

/**
 * Storage space low
 */
data class LowStorageEvent(
    val availableMb: Int,
    val threshold: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : DomainEvent
