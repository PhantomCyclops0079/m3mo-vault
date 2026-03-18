package com.m3mo.vault.domain.contract

/**
 * IResourceGovernor - Resource Management Interface
 *
 * Controls resource usage to prevent OOM and ensure smooth
 * operation on low-end devices. The governor monitors:
 * - Memory usage
 * - CPU/thermal state
 * - Storage space
 * - Battery level
 *
 * And adjusts algorithm selection and batch sizes accordingly.
 */
interface IResourceGovernor {

    /**
     * Get current resource state
     */
    fun getCurrentState(): ResourceState

    /**
     * Check if operation is allowed given current resources
     *
     * @param cost Estimated resource cost
     * @return Whether operation should proceed
     */
    fun canProceed(cost: ResourceCost): Boolean

    /**
     * Request resource budget for operation
     *
     * @param operation Type of operation
     * @return Allocated budget or null if denied
     */
    fun requestBudget(operation: OperationType): ResourceBudget?

    /**
     * Release allocated budget
     */
    fun releaseBudget(budget: ResourceBudget)

    /**
     * Execute operation with resource governance
     *
     * @param operation Operation type
     * @param block Code to execute
     * @return Result or ResourceDenied error
     */
    suspend fun <T> withGovernance(
        operation: OperationType,
        block: suspend (ResourceBudget) -> T
    ): GovernedResult<T>

    /**
     * Get recommended batch size for operation type
     */
    fun recommendedBatchSize(operation: OperationType): Int

    /**
     * Get recommended algorithm tier
     */
    fun recommendedTier(): AlgorithmTier

    /**
     * Register resource pressure listener
     */
    fun addPressureListener(listener: ResourcePressureListener)

    /**
     * Remove resource pressure listener
     */
    fun removePressureListener(listener: ResourcePressureListener)

    /**
     * Force garbage collection if memory pressure is high
     */
    fun triggerGcIfNeeded(): Boolean
}

/**
 * Current resource state snapshot
 */
data class ResourceState(
    /**
     * Available heap memory in MB
     */
    val availableMemoryMb: Int,

    /**
     * Total heap memory in MB
     */
    val totalMemoryMb: Int,

    /**
     * Memory usage percentage (0-100)
     */
    val memoryUsagePercent: Int,

    /**
     * Available storage in MB
     */
    val availableStorageMb: Int,

    /**
     * Battery level (0-100)
     */
    val batteryPercent: Int,

    /**
     * Is device charging
     */
    val isCharging: Boolean,

    /**
     * Thermal state
     */
    val thermalState: ThermalState,

    /**
     * Overall pressure level
     */
    val pressureLevel: PressureLevel,

    /**
     * Timestamp
     */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun unknown(): ResourceState = ResourceState(
            availableMemoryMb = 100,
            totalMemoryMb = 200,
            memoryUsagePercent = 50,
            availableStorageMb = 500,
            batteryPercent = 50,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            pressureLevel = PressureLevel.NORMAL
        )
    }
}

/**
 * Thermal throttling state
 */
enum class ThermalState {
    NOMINAL,    // Normal operation
    FAIR,       // Slight throttling
    SERIOUS,    // Significant throttling
    CRITICAL    // Emergency - stop heavy operations
}

/**
 * Overall resource pressure level
 */
enum class PressureLevel {
    LOW,        // Plenty of resources
    NORMAL,     // Normal operation
    ELEVATED,   // Should reduce batch sizes
    HIGH,       // Should defer non-essential operations
    CRITICAL    // Emergency mode
}

/**
 * Estimated resource cost for an operation
 */
data class ResourceCost(
    val memoryMb: Int = 0,
    val cpuIntensive: Boolean = false,
    val ioIntensive: Boolean = false,
    val durationEstimateMs: Long = 0
)

/**
 * Allocated resource budget
 */
data class ResourceBudget(
    val id: String,
    val operation: OperationType,
    val maxMemoryMb: Int,
    val maxDurationMs: Long,
    val batchSize: Int,
    val algorithmTier: AlgorithmTier,
    val allocatedAt: Long = System.currentTimeMillis()
)

/**
 * Types of operations that need resource governance
 */
enum class OperationType {
    DECAY_BATCH,        // SmartDecay processing
    SEMANTIC_SEARCH,    // Embedding-based search
    EMBEDDING_GEN,      // Generate embeddings
    VOICE_TRANSCRIBE,   // Voice to text
    DATABASE_QUERY,     // Large database queries
    INDEX_BUILD,        // Build search index
    WEEKLY_REVIEW,      // Generate weekly review
    EXPORT,             // Export data
    IMPORT              // Import data
}

/**
 * Algorithm complexity tiers
 */
enum class AlgorithmTier {
    /**
     * Lowest resource usage
     * - TF-IDF for search
     * - Small batch sizes
     */
    MINIMAL,

    /**
     * Balanced resource usage
     * - Word2Vec for search
     * - Medium batch sizes
     */
    STANDARD,

    /**
     * Full quality
     * - Transformer for search
     * - Large batch sizes
     */
    FULL
}

/**
 * Result of governed operation
 */
sealed class GovernedResult<T> {
    data class Success<T>(val value: T, val budget: ResourceBudget) : GovernedResult<T>()
    data class Denied<T>(val reason: String, val retryAfterMs: Long?) : GovernedResult<T>()
    data class Throttled<T>(val value: T, val wasLimited: Boolean) : GovernedResult<T>()
}

/**
 * Listener for resource pressure changes
 */
interface ResourcePressureListener {
    fun onPressureChanged(level: PressureLevel)
    fun onLowMemory(availableMb: Int)
    fun onThermalThrottle(state: ThermalState)
}
