package com.m3mo.vault.domain.model

/**
 * DecayResult - Result of SmartDecay algorithm execution
 *
 * Contains the calculated new weight and the factors that
 * contributed to the decision. This transparency is important
 * for debugging and patent documentation.
 */
data class DecayResult(
    /**
     * Memory ID that was processed
     */
    val memoryId: Long,

    /**
     * Original weight before decay
     */
    val originalWeight: Int,

    /**
     * New weight after decay
     */
    val newWeight: Int,

    /**
     * Whether weight actually changed
     */
    val changed: Boolean,

    /**
     * Individual factor contributions
     */
    val factors: DecayFactors,

    /**
     * Recommended action based on new weight
     */
    val action: DecayAction,

    /**
     * Processing timestamp
     */
    val processedAt: Long
) {
    companion object {
        /**
         * Create result for skipped memory (pinned, etc.)
         */
        fun skipped(memoryId: Long, weight: Int, reason: String): DecayResult {
            return DecayResult(
                memoryId = memoryId,
                originalWeight = weight,
                newWeight = weight,
                changed = false,
                factors = DecayFactors.empty(reason),
                action = DecayAction.NONE,
                processedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Weight delta (negative means decay, positive means boost)
     */
    val weightDelta: Int get() = newWeight - originalWeight
}

/**
 * DecayFactors - The 7 factors of SmartDecay algorithm
 *
 * This is the core patent-pending innovation:
 * 1. Base decay rate
 * 2. Grace period protection
 * 3. Recency penalty
 * 4. Frequency bonus
 * 5. Confidence bonus
 * 6. Size penalty
 * 7. Pin protection
 *
 * Each factor has a value and contribution to final weight change.
 */
data class DecayFactors(
    /**
     * Base decay per optimization run
     */
    val baseDecay: FactorValue,

    /**
     * Protection during initial grace period (typically 7 days)
     */
    val gracePeriod: FactorValue,

    /**
     * Penalty based on days since last access
     */
    val recencyPenalty: FactorValue,

    /**
     * Bonus based on access frequency
     */
    val frequencyBonus: FactorValue,

    /**
     * Bonus based on user confidence rating
     */
    val confidenceBonus: FactorValue,

    /**
     * Penalty for very large content
     */
    val sizePenalty: FactorValue,

    /**
     * Absolute protection for pinned items
     */
    val pinProtection: FactorValue,

    /**
     * Skip reason if not processed
     */
    val skipReason: String?
) {
    companion object {
        fun empty(reason: String? = null): DecayFactors {
            return DecayFactors(
                baseDecay = FactorValue.zero("baseDecay"),
                gracePeriod = FactorValue.zero("gracePeriod"),
                recencyPenalty = FactorValue.zero("recencyPenalty"),
                frequencyBonus = FactorValue.zero("frequencyBonus"),
                confidenceBonus = FactorValue.zero("confidenceBonus"),
                sizePenalty = FactorValue.zero("sizePenalty"),
                pinProtection = FactorValue.zero("pinProtection"),
                skipReason = reason
            )
        }
    }

    /**
     * Total contribution from all factors
     */
    val totalContribution: Double get() = listOf(
        baseDecay,
        gracePeriod,
        recencyPenalty,
        frequencyBonus,
        confidenceBonus,
        sizePenalty,
        pinProtection
    ).sumOf { it.contribution }

    /**
     * Get all factors as a list
     */
    fun asList(): List<FactorValue> = listOf(
        baseDecay,
        gracePeriod,
        recencyPenalty,
        frequencyBonus,
        confidenceBonus,
        sizePenalty,
        pinProtection
    )

    /**
     * Format for logging/debugging
     */
    fun toDebugString(): String {
        return buildString {
            appendLine("SmartDecay Factors:")
            asList().forEach { factor ->
                appendLine("  ${factor.name}: ${factor.rawValue} -> contribution: ${factor.contribution}")
            }
            appendLine("  Total: $totalContribution")
            skipReason?.let { appendLine("  Skip reason: $it") }
        }
    }
}

/**
 * Individual factor value and contribution
 */
data class FactorValue(
    /**
     * Factor name
     */
    val name: String,

    /**
     * Raw calculated value
     */
    val rawValue: Double,

    /**
     * Contribution to final weight change
     */
    val contribution: Double,

    /**
     * Whether this factor was applied
     */
    val applied: Boolean
) {
    companion object {
        fun zero(name: String): FactorValue {
            return FactorValue(
                name = name,
                rawValue = 0.0,
                contribution = 0.0,
                applied = false
            )
        }

        fun of(name: String, rawValue: Double, contribution: Double): FactorValue {
            return FactorValue(
                name = name,
                rawValue = rawValue,
                contribution = contribution,
                applied = true
            )
        }
    }
}

/**
 * Recommended action after decay calculation
 */
enum class DecayAction {
    /**
     * No action needed - weight unchanged or within normal range
     */
    NONE,

    /**
     * Update weight in database
     */
    UPDATE_WEIGHT,

    /**
     * Move to archive (weight dropped to minimum)
     */
    ARCHIVE,

    /**
     * Mark for deletion (weight below threshold + other conditions)
     */
    TOMBSTONE,

    /**
     * Boost weight (user interaction detected)
     */
    BOOST
}

/**
 * Batch decay operation result
 */
data class DecayBatchResult(
    /**
     * Individual results
     */
    val results: List<DecayResult>,

    /**
     * Total memories processed
     */
    val totalProcessed: Int,

    /**
     * Memories with weight changes
     */
    val totalChanged: Int,

    /**
     * Memories archived
     */
    val totalArchived: Int,

    /**
     * Memories tombstoned
     */
    val totalTombstoned: Int,

    /**
     * Processing time in milliseconds
     */
    val processingTimeMs: Long,

    /**
     * Batch processing timestamp
     */
    val processedAt: Long
) {
    companion object {
        fun empty(): DecayBatchResult {
            return DecayBatchResult(
                results = emptyList(),
                totalProcessed = 0,
                totalChanged = 0,
                totalArchived = 0,
                totalTombstoned = 0,
                processingTimeMs = 0,
                processedAt = System.currentTimeMillis()
            )
        }

        fun fromResults(results: List<DecayResult>, processingTimeMs: Long): DecayBatchResult {
            return DecayBatchResult(
                results = results,
                totalProcessed = results.size,
                totalChanged = results.count { it.changed },
                totalArchived = results.count { it.action == DecayAction.ARCHIVE },
                totalTombstoned = results.count { it.action == DecayAction.TOMBSTONE },
                processingTimeMs = processingTimeMs,
                processedAt = System.currentTimeMillis()
            )
        }
    }
}
