package com.m3mo.vault.memory.pipeline

import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.ProcessingState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PipelineContext - Shared context for optimization pipeline
 *
 * Maintains state and metrics across all pipeline stages.
 * Thread-safe for concurrent processing.
 */
class PipelineContext(
    /**
     * Memories to process
     */
    val inputMemories: List<MemoryItem>,

    /**
     * Pipeline configuration
     */
    val config: PipelineConfig = PipelineConfig.default(),

    /**
     * Resource budget for this run
     */
    val budget: ResourceBudget = ResourceBudget.default()
) {
    // Processing results
    private val _processedMemories = mutableListOf<MemoryItem>()
    val processedMemories: List<MemoryItem> get() = _processedMemories.toList()

    // Stage results
    private val stageResults = mutableMapOf<PipelineStage, StageResult>()

    // Metrics
    private val processedCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val startTimeMs = AtomicLong(0)
    private val stageTimings = mutableMapOf<PipelineStage, Long>()

    // State flags
    @Volatile var isCancelled = false
        private set

    @Volatile var currentStage: PipelineStage = PipelineStage.INITIALIZATION
        private set

    /**
     * Start pipeline execution
     */
    fun start() {
        startTimeMs.set(System.currentTimeMillis())
        currentStage = PipelineStage.STAGE_1_NORMALIZE
    }

    /**
     * Record stage completion
     */
    fun completeStage(stage: PipelineStage, result: StageResult) {
        stageResults[stage] = result
        stageTimings[stage] = result.durationMs

        currentStage = when (stage) {
            PipelineStage.INITIALIZATION -> PipelineStage.STAGE_1_NORMALIZE
            PipelineStage.STAGE_1_NORMALIZE -> PipelineStage.STAGE_2_VECTORIZE
            PipelineStage.STAGE_2_VECTORIZE -> PipelineStage.STAGE_3_DECAY
            PipelineStage.STAGE_3_DECAY -> PipelineStage.FINALIZATION
            PipelineStage.FINALIZATION -> PipelineStage.COMPLETED
            PipelineStage.COMPLETED -> PipelineStage.COMPLETED
        }
    }

    /**
     * Add processed memory
     */
    @Synchronized
    fun addProcessedMemory(memory: MemoryItem) {
        _processedMemories.add(memory)
        processedCount.incrementAndGet()
    }

    /**
     * Add multiple processed memories
     */
    @Synchronized
    fun addProcessedMemories(memories: List<MemoryItem>) {
        _processedMemories.addAll(memories)
        processedCount.addAndGet(memories.size)
    }

    /**
     * Record an error
     */
    fun recordError(error: PipelineError) {
        errorCount.incrementAndGet()
    }

    /**
     * Cancel pipeline execution
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Check if should continue processing
     */
    fun shouldContinue(): Boolean {
        if (isCancelled) return false
        if (budget.isExhausted()) return false
        return true
    }

    /**
     * Get total elapsed time
     */
    fun getElapsedMs(): Long {
        val start = startTimeMs.get()
        return if (start > 0) System.currentTimeMillis() - start else 0
    }

    /**
     * Get final pipeline result
     */
    fun getResult(): PipelineResult {
        return PipelineResult(
            inputCount = inputMemories.size,
            processedCount = processedCount.get(),
            errorCount = errorCount.get(),
            stageResults = stageResults.toMap(),
            totalDurationMs = getElapsedMs(),
            processedMemories = processedMemories,
            wasCancelled = isCancelled
        )
    }

    /**
     * Get memories filtered by state
     */
    fun getMemoriesByState(state: ProcessingState): List<MemoryItem> {
        return inputMemories.filter { it.state == state }
    }
}

/**
 * Pipeline stages
 */
enum class PipelineStage {
    INITIALIZATION,
    STAGE_1_NORMALIZE,    // Normalization + Classification
    STAGE_2_VECTORIZE,    // Vectorization + Clustering (adaptive)
    STAGE_3_DECAY,        // SmartDecay + Storage Strategy
    FINALIZATION,
    COMPLETED
}

/**
 * Result of a single stage
 */
data class StageResult(
    val stage: PipelineStage,
    val inputCount: Int,
    val outputCount: Int,
    val durationMs: Long,
    val success: Boolean,
    val errors: List<PipelineError> = emptyList(),
    val metrics: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(stage: PipelineStage, inputCount: Int, outputCount: Int, durationMs: Long): StageResult {
            return StageResult(
                stage = stage,
                inputCount = inputCount,
                outputCount = outputCount,
                durationMs = durationMs,
                success = true
            )
        }

        fun failure(stage: PipelineStage, inputCount: Int, durationMs: Long, errors: List<PipelineError>): StageResult {
            return StageResult(
                stage = stage,
                inputCount = inputCount,
                outputCount = 0,
                durationMs = durationMs,
                success = false,
                errors = errors
            )
        }
    }
}

/**
 * Pipeline error
 */
data class PipelineError(
    val stage: PipelineStage,
    val memoryId: Long?,
    val message: String,
    val cause: Throwable? = null
)

/**
 * Final pipeline result
 */
data class PipelineResult(
    val inputCount: Int,
    val processedCount: Int,
    val errorCount: Int,
    val stageResults: Map<PipelineStage, StageResult>,
    val totalDurationMs: Long,
    val processedMemories: List<MemoryItem>,
    val wasCancelled: Boolean
) {
    val success: Boolean get() = errorCount == 0 && !wasCancelled

    /**
     * Get stage timing breakdown
     */
    fun getTimingBreakdown(): Map<PipelineStage, Long> {
        return stageResults.mapValues { it.value.durationMs }
    }

    /**
     * Format as summary string
     */
    fun toSummary(): String {
        return buildString {
            appendLine("Pipeline Result:")
            appendLine("  Input: $inputCount, Processed: $processedCount, Errors: $errorCount")
            appendLine("  Duration: ${totalDurationMs}ms")
            appendLine("  Cancelled: $wasCancelled")
            appendLine("Stage Breakdown:")
            for ((stage, result) in stageResults) {
                appendLine("  $stage: ${result.durationMs}ms (${result.inputCount}→${result.outputCount})")
            }
        }
    }
}

/**
 * Pipeline configuration
 */
data class PipelineConfig(
    /**
     * Enable Stage 2 (vectorization)
     * May be disabled on low-end devices
     */
    val enableStage2: Boolean = true,

    /**
     * Batch size for processing
     */
    val batchSize: Int = 100,

    /**
     * Enable parallel processing where possible
     */
    val enableParallel: Boolean = true,

    /**
     * Maximum parallel workers
     */
    val maxParallelism: Int = 4,

    /**
     * Continue on individual item errors
     */
    val continueOnError: Boolean = true,

    /**
     * Log verbose debug info
     */
    val verboseLogging: Boolean = false
) {
    companion object {
        fun default(): PipelineConfig = PipelineConfig()

        fun minimal(): PipelineConfig = PipelineConfig(
            enableStage2 = false,
            enableParallel = false,
            batchSize = 50
        )

        fun full(): PipelineConfig = PipelineConfig(
            enableStage2 = true,
            enableParallel = true,
            batchSize = 200,
            maxParallelism = 4
        )
    }
}

/**
 * Resource budget for pipeline execution
 */
data class ResourceBudget(
    /**
     * Maximum execution time in milliseconds
     */
    val maxTimeMs: Long = 30_000,

    /**
     * Maximum memory usage in bytes
     */
    val maxMemoryBytes: Long = 50 * 1024 * 1024,  // 50MB

    /**
     * Maximum items to process
     */
    val maxItems: Int = 1000
) {
    private var startTimeMs: Long = System.currentTimeMillis()
    private var itemsProcessed: Int = 0

    fun start() {
        startTimeMs = System.currentTimeMillis()
        itemsProcessed = 0
    }

    fun recordItem() {
        itemsProcessed++
    }

    fun isExhausted(): Boolean {
        if (System.currentTimeMillis() - startTimeMs > maxTimeMs) return true
        if (itemsProcessed >= maxItems) return true
        // Memory check would require Android-specific APIs
        return false
    }

    fun remainingTimeMs(): Long {
        val elapsed = System.currentTimeMillis() - startTimeMs
        return (maxTimeMs - elapsed).coerceAtLeast(0)
    }

    fun remainingItems(): Int {
        return (maxItems - itemsProcessed).coerceAtLeast(0)
    }

    companion object {
        fun default(): ResourceBudget = ResourceBudget()

        fun quick(): ResourceBudget = ResourceBudget(
            maxTimeMs = 10_000,
            maxItems = 100
        )

        fun full(): ResourceBudget = ResourceBudget(
            maxTimeMs = 60_000,
            maxItems = 2000
        )
    }
}
