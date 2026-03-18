package com.m3mo.vault.domain.model

/**
 * ProcessingState - Memory lifecycle states
 *
 * Tracks a memory's journey through the system:
 *
 * NEW → QUEUED → STORED → OPTIMIZED → ARCHIVED → TOMBSTONED
 *                  ↓
 *               DIRTY (needs reprocessing)
 *
 * This state machine enables:
 * - Incremental processing
 * - Crash recovery
 * - Efficient batch operations
 * - Garbage collection eligibility
 */
enum class ProcessingState(
    val displayName: String,
    val isTerminal: Boolean = false,
    val canBeOptimized: Boolean = true
) {
    /**
     * Just created, not yet processed
     * Entry point for all new memories
     */
    NEW(
        displayName = "New",
        canBeOptimized = false
    ),

    /**
     * In processing queue
     * Waiting for Stage 1/2/3 pipeline
     */
    QUEUED(
        displayName = "Queued",
        canBeOptimized = false
    ),

    /**
     * Successfully stored in L2 (database)
     * Ready for normal operations
     */
    STORED(
        displayName = "Stored",
        canBeOptimized = true
    ),

    /**
     * Needs reprocessing
     * Set when content is edited or feedback suggests error
     */
    DIRTY(
        displayName = "Dirty",
        canBeOptimized = false
    ),

    /**
     * Has been through optimization pipeline
     * Normalized, deduplicated, indexed
     */
    OPTIMIZED(
        displayName = "Optimized",
        canBeOptimized = true
    ),

    /**
     * Low activity, moved to cold storage
     * Still queryable but not in hot cache
     */
    ARCHIVED(
        displayName = "Archived",
        canBeOptimized = true
    ),

    /**
     * Marked for deletion
     * Will be physically removed by GC
     */
    TOMBSTONED(
        displayName = "Tombstoned",
        isTerminal = true,
        canBeOptimized = false
    );

    companion object {
        /**
         * Initial state for new memories
         */
        fun initial(): ProcessingState = NEW

        /**
         * Parse from string (case-insensitive)
         */
        fun fromString(value: String): ProcessingState? {
            return entries.find {
                it.name.equals(value, ignoreCase = true)
            }
        }

        /**
         * States that should be included in search results
         */
        fun searchable(): Set<ProcessingState> = setOf(
            STORED, OPTIMIZED, ARCHIVED
        )

        /**
         * States eligible for optimization pipeline
         */
        fun optimizable(): Set<ProcessingState> = setOf(
            STORED, OPTIMIZED, ARCHIVED
        )

        /**
         * States that should be in L1 cache
         */
        fun cacheable(): Set<ProcessingState> = setOf(
            NEW, QUEUED, STORED, DIRTY, OPTIMIZED
        )
    }
}

/**
 * State transitions
 */
fun ProcessingState.canTransitionTo(next: ProcessingState): Boolean {
    return when (this) {
        ProcessingState.NEW -> next in setOf(
            ProcessingState.QUEUED,
            ProcessingState.STORED,
            ProcessingState.TOMBSTONED
        )
        ProcessingState.QUEUED -> next in setOf(
            ProcessingState.STORED,
            ProcessingState.NEW,  // Retry on failure
            ProcessingState.TOMBSTONED
        )
        ProcessingState.STORED -> next in setOf(
            ProcessingState.DIRTY,
            ProcessingState.OPTIMIZED,
            ProcessingState.ARCHIVED,
            ProcessingState.TOMBSTONED
        )
        ProcessingState.DIRTY -> next in setOf(
            ProcessingState.QUEUED,
            ProcessingState.STORED,
            ProcessingState.TOMBSTONED
        )
        ProcessingState.OPTIMIZED -> next in setOf(
            ProcessingState.DIRTY,
            ProcessingState.ARCHIVED,
            ProcessingState.TOMBSTONED
        )
        ProcessingState.ARCHIVED -> next in setOf(
            ProcessingState.STORED,  // Reactivate
            ProcessingState.TOMBSTONED
        )
        ProcessingState.TOMBSTONED -> false  // Terminal state
    }
}

/**
 * Check if memory is visible to user
 */
fun ProcessingState.isVisible(): Boolean = this in setOf(
    ProcessingState.STORED,
    ProcessingState.OPTIMIZED,
    ProcessingState.ARCHIVED
)

/**
 * Check if memory needs processing
 */
fun ProcessingState.needsProcessing(): Boolean = this in setOf(
    ProcessingState.NEW,
    ProcessingState.QUEUED,
    ProcessingState.DIRTY
)
