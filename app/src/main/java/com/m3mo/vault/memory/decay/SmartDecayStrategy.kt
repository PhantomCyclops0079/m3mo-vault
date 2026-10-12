package com.m3mo.vault.memory.decay

import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.DecayConfig
import com.m3mo.vault.domain.contract.IDecayStrategy
import com.m3mo.vault.domain.model.DecayAction
import com.m3mo.vault.domain.model.DecayBatchResult
import com.m3mo.vault.domain.model.DecayFactors
import com.m3mo.vault.domain.model.DecayResult
import com.m3mo.vault.domain.model.FactorValue
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import kotlin.math.max
import kotlin.math.min

/**
 * Calculates decay for unpinned records in STORED or OPTIMIZED state whose
 * weight is above the configured minimum.
 *
 * Factor contributions are added, truncated toward zero, and clamped at zero:
 * effectiveDecay = max(0, sumOfContributions.toInt())
 * newWeight = clamp(oldWeight - effectiveDecay, minWeight, maxWeight)
 *
 * The grace period cancels the base contribution. Recency and size can add
 * decay; frequency and confidence can reduce it. An unrated record uses
 * confidence 3. Pin bypasses the calculation entirely.
 *
 * For changed weights, tombstoning takes precedence over archiving.
 * This strategy returns a recommended action; callers persist the result.
 *
 * @see IDecayStrategy
 * @see DecayConfig
 */
class SmartDecayStrategy(
    private val config: DecayConfig = DecayConfig.default(),
    private val clock: Clock,
    private val logger: Logger
) : IDecayStrategy {

    companion object {
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    override fun apply(memory: MemoryItem, currentTimeMs: Long): DecayResult {
        // Factor 7: Pin Protection (absolute)
        if (memory.isPinned) {
            logger.d(LogTags.DECAY, "Memory ${memory.id} is pinned, skipping decay")
            return DecayResult.skipped(memory.id, memory.weight, "Pinned")
        }

        // Check eligibility
        if (!isEligible(memory)) {
            return DecayResult.skipped(memory.id, memory.weight, "Not eligible")
        }

        val factors = calculateFactors(memory, currentTimeMs)
        val effectiveDecay = calculateEffectiveDecay(factors)
        val newWeight = calculateNewWeight(memory.weight, effectiveDecay)
        val action = determineAction(memory.weight, newWeight)

        val result = DecayResult(
            memoryId = memory.id,
            originalWeight = memory.weight,
            newWeight = newWeight,
            changed = newWeight != memory.weight,
            factors = factors,
            action = action,
            processedAt = currentTimeMs
        )

        logDecayResult(memory, result)
        return result
    }

    override fun applyBatch(memories: List<MemoryItem>, currentTimeMs: Long): DecayBatchResult {
        if (memories.isEmpty()) {
            return DecayBatchResult.empty()
        }

        val startTime = System.currentTimeMillis()
        val results = memories.map { apply(it, currentTimeMs) }
        val processingTime = System.currentTimeMillis() - startTime

        logger.i(LogTags.DECAY, "Batch decay: ${results.size} memories in ${processingTime}ms")

        return DecayBatchResult.fromResults(results, processingTime)
    }

    override fun calculateBoost(memory: MemoryItem, accessWeight: Int): Int {
        // Boost formula: increase by 1-3 based on access type
        val boost = when (accessWeight) {
            1 -> 1  // View
            2 -> 2  // Search click
            3 -> 3  // Edit
            else -> 1
        }

        val newWeight = min(memory.weight + boost, config.maxWeight)
        logger.d(LogTags.DECAY, "Boost memory ${memory.id}: ${memory.weight} → $newWeight (access: $accessWeight)")
        return newWeight
    }

    override fun isEligible(memory: MemoryItem): Boolean {
        // Pinned memories are never eligible
        if (memory.isPinned) return false

        // Already at minimum weight
        if (memory.weight <= config.minWeight) return false

        // Only process memories in certain states
        val eligibleStates = setOf(
            ProcessingState.STORED,
            ProcessingState.OPTIMIZED
        )
        if (memory.state !in eligibleStates) return false

        return true
    }

    override fun getConfig(): DecayConfig = config

    // ========== Core Algorithm ==========

    /**
     * Calculate all 7 factors for a memory
     */
    private fun calculateFactors(memory: MemoryItem, currentTimeMs: Long): DecayFactors {
        val ageInDays = calculateAgeInDays(memory.createdAt, currentTimeMs)
        val daysSinceAccess = calculateDaysSinceAccess(memory.lastAccessedAt, currentTimeMs)

        // Factor 1: Base Decay
        val baseDecay = FactorValue.of(
            name = "baseDecay",
            rawValue = config.decayPerRun.toDouble(),
            contribution = config.decayPerRun.toDouble()
        )

        // Factor 2: Grace Period Protection
        val gracePeriod = calculateGracePeriodFactor(ageInDays)

        // Factor 3: Recency Penalty
        val recencyPenalty = calculateRecencyPenaltyFactor(daysSinceAccess)

        // Factor 4: Frequency Bonus
        val frequencyBonus = calculateFrequencyBonusFactor(memory.accessCount)

        // Factor 5: Confidence Bonus
        val confidenceBonus = calculateConfidenceBonusFactor(memory.confidence)

        // Factor 6: Size Penalty
        val sizePenalty = calculateSizePenaltyFactor(memory.content.length)

        // Factor 7: Pin Protection (already checked above, but include for completeness)
        val pinProtection = FactorValue.of(
            name = "pinProtection",
            rawValue = 0.0,
            contribution = 0.0
        )

        return DecayFactors(
            baseDecay = baseDecay,
            gracePeriod = gracePeriod,
            recencyPenalty = recencyPenalty,
            frequencyBonus = frequencyBonus,
            confidenceBonus = confidenceBonus,
            sizePenalty = sizePenalty,
            pinProtection = pinProtection,
            skipReason = null
        )
    }

    /**
     * Factor 2: Grace Period
     * Cancel the base contribution while a record is in its grace period.
     */
    private fun calculateGracePeriodFactor(ageInDays: Long): FactorValue {
        val inGracePeriod = ageInDays < config.gracePeriodDays
        val multiplier = if (inGracePeriod) 0.0 else 1.0

        return FactorValue.of(
            name = "gracePeriod",
            rawValue = ageInDays.toDouble(),
            contribution = if (inGracePeriod) -config.decayPerRun.toDouble() else 0.0
        )
    }

    /**
     * Factor 3: Recency Penalty
     * Memories not accessed for a long time decay faster
     *
     * With the default configuration:
     * - Fewer than 14 days: no contribution
     * - 14-27 days: add 1 to calculated decay
     * - 28 or more days: add 2 to calculated decay
     */
    private fun calculateRecencyPenaltyFactor(daysSinceAccess: Long): FactorValue {
        val penalty = when {
            daysSinceAccess < config.recencyThresholdDays -> 0.0
            daysSinceAccess < config.recencyThresholdDays * 2 -> config.maxRecencyPenalty * 0.5
            else -> config.maxRecencyPenalty.toDouble()
        }

        return FactorValue.of(
            name = "recencyPenalty",
            rawValue = daysSinceAccess.toDouble(),
            contribution = penalty
        )
    }

    /**
     * Factor 4: Frequency Bonus
     * Frequently accessed memories are protected
     *
     * Formula:
     * - 0-4 accesses: no bonus
     * - 5-9 accesses: 1 weight protection
     * - 10+ accesses: 2 weight protection
     */
    private fun calculateFrequencyBonusFactor(accessCount: Int): FactorValue {
        val bonus = when {
            accessCount < config.frequencyThreshold -> 0.0
            accessCount < config.frequencyThreshold * 2 -> config.maxFrequencyBonus * 0.5
            else -> config.maxFrequencyBonus.toDouble()
        }

        return FactorValue.of(
            name = "frequencyBonus",
            rawValue = accessCount.toDouble(),
            contribution = -bonus  // Negative because it reduces decay
        )
    }

    /**
     * Factor 5: Confidence Bonus
     * User-rated importance protects memory
     *
     * Formula:
     * - Confidence 1-2: no bonus
     * - Confidence 3: 0.5 protection
     * - Confidence 4: 1 protection
     * - Confidence 5: 2 protection
     */
    private fun calculateConfidenceBonusFactor(confidence: Int?): FactorValue {
        val effectiveConfidence = confidence ?: 3  // Default to neutral
        val bonus = when {
            effectiveConfidence <= 2 -> 0.0
            effectiveConfidence == 3 -> 0.5
            effectiveConfidence == 4 -> 1.0
            else -> 2.0
        }

        return FactorValue.of(
            name = "confidenceBonus",
            rawValue = effectiveConfidence.toDouble(),
            contribution = -bonus  // Negative because it reduces decay
        )
    }

    /**
     * Factor 6: Size Penalty
     * Large content decays faster to manage storage
     *
     * Formula:
     * - 0-1000 chars: no penalty
     * - 1001-5000 chars: 0.5 penalty
     * - 5001+ chars: 1 penalty
     */
    private fun calculateSizePenaltyFactor(contentLength: Int): FactorValue {
        val penalty = when {
            contentLength <= config.sizeThreshold -> 0.0
            contentLength <= config.sizeThreshold * 5 -> config.maxSizePenalty * 0.5
            else -> config.maxSizePenalty.toDouble()
        }

        return FactorValue.of(
            name = "sizePenalty",
            rawValue = contentLength.toDouble(),
            contribution = penalty
        )
    }

    /**
     * Calculate effective decay from all factors
     *
     * Formula:
     * effectiveDecay = baseDecay + gracePeriodContribution + recencyPenalty
     *                  + frequencyBonus (negative) + confidenceBonus (negative)
     *                  + sizePenalty
     */
    private fun calculateEffectiveDecay(factors: DecayFactors): Int {
        val totalContribution = factors.totalContribution
        
        // Truncate toward zero, then clamp at zero.
        return max(0, totalContribution.toInt())
    }

    /**
     * Calculate new weight after decay
     */
    private fun calculateNewWeight(originalWeight: Int, effectiveDecay: Int): Int {
        val newWeight = originalWeight - effectiveDecay
        return newWeight.coerceIn(config.minWeight, config.maxWeight)
    }

    /**
     * Determine recommended action based on weight change
     */
    private fun determineAction(originalWeight: Int, newWeight: Int): DecayAction {
        return when {
            newWeight == originalWeight -> DecayAction.NONE
            newWeight <= config.tombstoneThreshold -> DecayAction.TOMBSTONE
            newWeight <= config.archiveThreshold -> DecayAction.ARCHIVE
            else -> DecayAction.UPDATE_WEIGHT
        }
    }

    // ========== Utility Functions ==========

    private fun calculateAgeInDays(createdAt: Long, currentTimeMs: Long): Long {
        return (currentTimeMs - createdAt) / MILLIS_PER_DAY
    }

    private fun calculateDaysSinceAccess(lastAccessedAt: Long, currentTimeMs: Long): Long {
        return (currentTimeMs - lastAccessedAt) / MILLIS_PER_DAY
    }

    private fun logDecayResult(memory: MemoryItem, result: DecayResult) {
        if (result.changed) {
            logger.d(
                LogTags.DECAY,
                "Decay: ${memory.id} [${memory.weight}→${result.newWeight}] " +
                "action=${result.action} factors=${result.factors.totalContribution}"
            )
        }
    }
}

/**
 * Factory for creating SmartDecayStrategy instances
 */
class SmartDecayStrategyFactory(
    private val clock: Clock,
    private val logger: Logger
) : com.m3mo.vault.domain.contract.IDecayStrategyFactory {

    override fun create(): IDecayStrategy {
        return SmartDecayStrategy(
            config = DecayConfig.default(),
            clock = clock,
            logger = logger
        )
    }

    override fun create(config: DecayConfig): IDecayStrategy {
        return SmartDecayStrategy(
            config = config,
            clock = clock,
            logger = logger
        )
    }
}
