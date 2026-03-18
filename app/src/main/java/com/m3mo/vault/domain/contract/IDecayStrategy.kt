package com.m3mo.vault.domain.contract

import com.m3mo.vault.domain.model.DecayBatchResult
import com.m3mo.vault.domain.model.DecayResult
import com.m3mo.vault.domain.model.MemoryItem

/**
 * IDecayStrategy - SmartDecay Algorithm Interface
 *
 * US Provisional Patent #63/942,399: "SmartDecay" Technology
 *
 * This interface defines the contract for the intelligent memory decay
 * algorithm that achieves 16x weight differentiation on resource-constrained
 * mobile devices.
 *
 * The algorithm uses 7 factors to determine memory importance:
 * 1. Base decay rate
 * 2. Grace period protection (new memories protected)
 * 3. Recency penalty (older = faster decay)
 * 4. Frequency bonus (frequently accessed = slower decay)
 * 5. Confidence bonus (user-rated importance)
 * 6. Size penalty (large content = faster decay for resource management)
 * 7. Pin protection (user-pinned = no decay)
 *
 * Implementation Requirements:
 * - Must be deterministic given same inputs
 * - Must complete within time budget (see ResourceGovernor)
 * - Must preserve pinned memory weights
 * - Must respect min/max weight bounds (1-16)
 */
interface IDecayStrategy {

    /**
     * Apply decay algorithm to a single memory
     *
     * @param memory The memory to process
     * @param currentTimeMs Current timestamp for calculations
     * @return DecayResult with new weight and factor breakdown
     */
    fun apply(memory: MemoryItem, currentTimeMs: Long = System.currentTimeMillis()): DecayResult

    /**
     * Apply decay to a batch of memories
     *
     * @param memories List of memories to process
     * @param currentTimeMs Current timestamp
     * @return Batch result with statistics
     */
    fun applyBatch(
        memories: List<MemoryItem>,
        currentTimeMs: Long = System.currentTimeMillis()
    ): DecayBatchResult

    /**
     * Calculate boost amount for memory access
     *
     * When a user interacts with a memory, its weight should increase.
     * The boost amount depends on the type of access.
     *
     * @param memory The memory being accessed
     * @param accessWeight Weight of the access (1=view, 2=search click, 3=edit)
     * @return New weight after boost
     */
    fun calculateBoost(memory: MemoryItem, accessWeight: Int): Int

    /**
     * Check if memory is eligible for decay processing
     *
     * Memories may be skipped if:
     * - Pinned
     * - In grace period
     * - Already at minimum weight
     * - Not in appropriate state
     */
    fun isEligible(memory: MemoryItem): Boolean

    /**
     * Get current configuration
     */
    fun getConfig(): DecayConfig
}

/**
 * Decay algorithm configuration
 *
 * These parameters tune the algorithm behavior.
 * Default values are optimized for typical usage patterns.
 */
data class DecayConfig(
    /**
     * Base decay amount per optimization run
     * Default: 1 (reduces weight by 1 each cycle)
     */
    val decayPerRun: Int = 1,

    /**
     * Grace period in days (new memories protected)
     * Default: 7 days
     */
    val gracePeriodDays: Int = 7,

    /**
     * Days without access before recency penalty kicks in
     * Default: 14 days
     */
    val recencyThresholdDays: Int = 14,

    /**
     * Maximum recency penalty
     * Default: 2 (additional weight loss)
     */
    val maxRecencyPenalty: Int = 2,

    /**
     * Access count threshold for frequency bonus
     * Default: 5 accesses
     */
    val frequencyThreshold: Int = 5,

    /**
     * Maximum frequency bonus
     * Default: 2 (weight preserved)
     */
    val maxFrequencyBonus: Int = 2,

    /**
     * Content size threshold for penalty (characters)
     * Default: 1000 characters
     */
    val sizeThreshold: Int = 1000,

    /**
     * Maximum size penalty
     * Default: 1
     */
    val maxSizePenalty: Int = 1,

    /**
     * Minimum weight (cannot go below)
     */
    val minWeight: Int = MemoryItem.MIN_WEIGHT,

    /**
     * Maximum weight (cannot exceed)
     */
    val maxWeight: Int = MemoryItem.MAX_WEIGHT,

    /**
     * Weight threshold for archiving
     * Memories at or below this are candidates for archive
     */
    val archiveThreshold: Int = 2,

    /**
     * Weight threshold for tombstoning
     * Memories at this level for N cycles get tombstoned
     */
    val tombstoneThreshold: Int = 1
) {
    companion object {
        /**
         * Default configuration
         */
        fun default(): DecayConfig = DecayConfig()

        /**
         * Aggressive decay (for testing or low storage)
         */
        fun aggressive(): DecayConfig = DecayConfig(
            decayPerRun = 2,
            gracePeriodDays = 3,
            recencyThresholdDays = 7
        )

        /**
         * Conservative decay (preserve more memories)
         */
        fun conservative(): DecayConfig = DecayConfig(
            decayPerRun = 1,
            gracePeriodDays = 14,
            recencyThresholdDays = 30,
            maxRecencyPenalty = 1
        )
    }
}

/**
 * Factory for creating decay strategies
 */
interface IDecayStrategyFactory {
    /**
     * Create strategy with default config
     */
    fun create(): IDecayStrategy

    /**
     * Create strategy with custom config
     */
    fun create(config: DecayConfig): IDecayStrategy
}
