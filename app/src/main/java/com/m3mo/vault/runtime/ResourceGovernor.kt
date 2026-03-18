package com.m3mo.vault.runtime

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.AlgorithmTier
import com.m3mo.vault.domain.contract.GovernedResult
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.contract.OperationType
import com.m3mo.vault.domain.contract.PressureLevel
import com.m3mo.vault.domain.contract.ResourceBudget
import com.m3mo.vault.domain.contract.ResourceCost
import com.m3mo.vault.domain.contract.ResourcePressureListener
import com.m3mo.vault.domain.contract.ResourceState
import com.m3mo.vault.domain.contract.ThermalState
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * ResourceGovernor - Smart Resource Management for Mobile Devices
 *
 * Monitors system resources and controls algorithm selection to ensure
 * smooth operation on resource-constrained devices.
 *
 * ## Features
 *
 * - Real-time memory monitoring
 * - Battery-aware scheduling
 * - Thermal throttling detection
 * - Adaptive batch sizing
 * - Algorithm tier selection
 *
 * ## Usage
 *
 * ```kotlin
 * val governor = ResourceGovernor(deviceProfile, logger)
 *
 * // Check before heavy operation
 * if (governor.canProceed(ResourceCost(memoryMb = 50))) {
 *     // proceed
 * }
 *
 * // Or use governed execution
 * val result = governor.withGovernance(OperationType.DECAY_BATCH) { budget ->
 *     processDecay(budget.batchSize)
 * }
 * ```
 */
class ResourceGovernor(
    private val deviceProfile: DeviceProfile,
    private val logger: Logger
) : IResourceGovernor {

    // Current state (cached, updated periodically)
    private val currentState = AtomicReference(ResourceState.unknown())

    // Active budgets
    private val activeBudgets = CopyOnWriteArrayList<ResourceBudget>()

    // Pressure listeners
    private val pressureListeners = CopyOnWriteArrayList<ResourcePressureListener>()

    // Last known pressure level (for change detection)
    private var lastPressureLevel = PressureLevel.NORMAL

    init {
        refreshState()
    }

    override fun getCurrentState(): ResourceState {
        refreshState()
        return currentState.get()
    }

    override fun canProceed(cost: ResourceCost): Boolean {
        val state = getCurrentState()

        // Critical pressure - deny all non-essential
        if (state.pressureLevel == PressureLevel.CRITICAL) {
            logger.w(LogTags.METRICS, "Operation denied: critical resource pressure")
            return false
        }

        // Check memory
        if (cost.memoryMb > 0 && cost.memoryMb > state.availableMemoryMb * 0.8) {
            logger.w(LogTags.METRICS, "Operation denied: insufficient memory")
            return false
        }

        // Check thermal state for CPU intensive operations
        if (cost.cpuIntensive && state.thermalState >= ThermalState.SERIOUS) {
            logger.w(LogTags.METRICS, "Operation denied: thermal throttling")
            return false
        }

        // Check battery for long operations
        if (cost.durationEstimateMs > 30_000 && state.batteryPercent < 15 && !state.isCharging) {
            logger.w(LogTags.METRICS, "Operation denied: low battery")
            return false
        }

        return true
    }

    override fun requestBudget(operation: OperationType): ResourceBudget? {
        val state = getCurrentState()

        // Determine appropriate budget based on state
        val budget = calculateBudget(operation, state)

        if (budget == null) {
            logger.w(LogTags.METRICS, "Budget denied for $operation")
            return null
        }

        activeBudgets.add(budget)
        logger.d(LogTags.METRICS, "Budget allocated: $operation, batch=${budget.batchSize}, tier=${budget.algorithmTier}")

        return budget
    }

    override fun releaseBudget(budget: ResourceBudget) {
        activeBudgets.remove(budget)
        logger.d(LogTags.METRICS, "Budget released: ${budget.operation}")
    }

    override suspend fun <T> withGovernance(
        operation: OperationType,
        block: suspend (ResourceBudget) -> T
    ): GovernedResult<T> {
        val budget = requestBudget(operation)
            ?: return GovernedResult.Denied(
                reason = "Insufficient resources for $operation",
                retryAfterMs = 30_000
            )

        return try {
            val result = block(budget)
            GovernedResult.Success(result, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(LogTags.METRICS, "Governed operation failed: $operation", e)
            GovernedResult.Denied(
                reason = "Operation failed: ${e.message}",
                retryAfterMs = null
            )
        } finally {
            releaseBudget(budget)
        }
    }

    override fun recommendedBatchSize(operation: OperationType): Int {
        val state = getCurrentState()
        val baseBatchSize = getBaseBatchSize(operation)

        // Scale based on pressure level
        val multiplier = when (state.pressureLevel) {
            PressureLevel.LOW -> 1.5f
            PressureLevel.NORMAL -> 1.0f
            PressureLevel.ELEVATED -> 0.6f
            PressureLevel.HIGH -> 0.3f
            PressureLevel.CRITICAL -> 0.1f
        }

        return (baseBatchSize * multiplier).toInt().coerceAtLeast(10)
    }

    override fun recommendedTier(): AlgorithmTier {
        val state = getCurrentState()
        val profile = deviceProfile

        // Critical state - minimal tier
        if (state.pressureLevel >= PressureLevel.HIGH) {
            return AlgorithmTier.MINIMAL
        }

        // Low-end device - minimal tier
        if (profile.tier == DeviceTier.LOW_END) {
            return AlgorithmTier.MINIMAL
        }

        // Mid-range device
        if (profile.tier == DeviceTier.MID_RANGE) {
            return when (state.pressureLevel) {
                PressureLevel.LOW, PressureLevel.NORMAL -> AlgorithmTier.STANDARD
                else -> AlgorithmTier.MINIMAL
            }
        }

        // High-end device
        return when (state.pressureLevel) {
            PressureLevel.LOW -> AlgorithmTier.FULL
            PressureLevel.NORMAL -> AlgorithmTier.STANDARD
            else -> AlgorithmTier.MINIMAL
        }
    }

    override fun addPressureListener(listener: ResourcePressureListener) {
        pressureListeners.add(listener)
    }

    override fun removePressureListener(listener: ResourcePressureListener) {
        pressureListeners.remove(listener)
    }

    override fun triggerGcIfNeeded(): Boolean {
        val state = getCurrentState()

        if (state.memoryUsagePercent > 85) {
            logger.d(LogTags.METRICS, "Triggering GC due to high memory usage: ${state.memoryUsagePercent}%")
            System.gc()
            return true
        }

        return false
    }

    // ========== Internal Methods ==========

    /**
     * Refresh resource state from system
     */
    private fun refreshState() {
        val runtime = Runtime.getRuntime()

        val totalMemory = runtime.maxMemory() / (1024 * 1024)
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val availableMemory = totalMemory - usedMemory
        val memoryUsagePercent = ((usedMemory.toFloat() / totalMemory) * 100).toInt()

        val pressureLevel = calculatePressureLevel(memoryUsagePercent.toInt())

        val newState = ResourceState(
            availableMemoryMb = availableMemory.toInt(),
            totalMemoryMb = totalMemory.toInt(),
            memoryUsagePercent = memoryUsagePercent,
            availableStorageMb = deviceProfile.availableStorageMb,
            batteryPercent = deviceProfile.batteryPercent,
            isCharging = deviceProfile.isCharging,
            thermalState = deviceProfile.thermalState,
            pressureLevel = pressureLevel
        )

        currentState.set(newState)

        // Notify listeners if pressure changed
        if (pressureLevel != lastPressureLevel) {
            lastPressureLevel = pressureLevel
            notifyPressureChanged(pressureLevel)
        }

        // Low memory notification
        if (availableMemory < 50) {
            notifyLowMemory(availableMemory.toInt())
        }
    }

    /**
     * Calculate pressure level from memory usage
     */
    private fun calculatePressureLevel(memoryUsagePercent: Int): PressureLevel {
        return when {
            memoryUsagePercent < 50 -> PressureLevel.LOW
            memoryUsagePercent < 70 -> PressureLevel.NORMAL
            memoryUsagePercent < 80 -> PressureLevel.ELEVATED
            memoryUsagePercent < 90 -> PressureLevel.HIGH
            else -> PressureLevel.CRITICAL
        }
    }

    /**
     * Calculate budget for operation
     */
    private fun calculateBudget(operation: OperationType, state: ResourceState): ResourceBudget? {
        // Check if we can proceed at all
        if (state.pressureLevel == PressureLevel.CRITICAL) {
            return null
        }

        val batchSize = recommendedBatchSize(operation)
        val tier = recommendedTier()

        val maxDuration = when (operation) {
            OperationType.DECAY_BATCH -> 30_000L
            OperationType.SEMANTIC_SEARCH -> 5_000L
            OperationType.EMBEDDING_GEN -> 10_000L
            OperationType.VOICE_TRANSCRIBE -> 60_000L
            OperationType.DATABASE_QUERY -> 10_000L
            OperationType.INDEX_BUILD -> 60_000L
            OperationType.WEEKLY_REVIEW -> 120_000L
            OperationType.EXPORT -> 300_000L
            OperationType.IMPORT -> 300_000L
        }

        val maxMemory = when (tier) {
            AlgorithmTier.MINIMAL -> 30
            AlgorithmTier.STANDARD -> 50
            AlgorithmTier.FULL -> 100
        }

        return ResourceBudget(
            id = UUID.randomUUID().toString(),
            operation = operation,
            maxMemoryMb = maxMemory,
            maxDurationMs = maxDuration,
            batchSize = batchSize,
            algorithmTier = tier
        )
    }

    /**
     * Get base batch size for operation type
     */
    private fun getBaseBatchSize(operation: OperationType): Int {
        return when (operation) {
            OperationType.DECAY_BATCH -> 200
            OperationType.SEMANTIC_SEARCH -> 100
            OperationType.EMBEDDING_GEN -> 50
            OperationType.VOICE_TRANSCRIBE -> 1
            OperationType.DATABASE_QUERY -> 500
            OperationType.INDEX_BUILD -> 100
            OperationType.WEEKLY_REVIEW -> 50
            OperationType.EXPORT -> 500
            OperationType.IMPORT -> 100
        }
    }

    private fun notifyPressureChanged(level: PressureLevel) {
        pressureListeners.forEach { it.onPressureChanged(level) }
    }

    private fun notifyLowMemory(availableMb: Int) {
        pressureListeners.forEach { it.onLowMemory(availableMb) }
    }
}
