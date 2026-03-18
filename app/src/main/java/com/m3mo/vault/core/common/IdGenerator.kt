package com.m3mo.vault.core.common

import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * IdGenerator - Unique Identifier Generation
 *
 * Supports multiple ID strategies:
 * 1. UUID - Universal unique, but long (36 chars)
 * 2. Snowflake-like - Time-ordered, compact, sortable
 * 3. Sequential - Simple, predictable (for testing)
 *
 * For M3mo, we use Snowflake-like IDs because:
 * - Time-ordered (newer IDs > older IDs)
 * - Compact (64-bit long vs 128-bit UUID)
 * - Collision-resistant without coordination
 * - Works offline (no server needed)
 */
interface IdGenerator {
    /**
     * Generate a new unique ID as Long
     */
    fun nextId(): Long

    /**
     * Generate a new unique ID as String
     */
    fun nextIdString(): String
}

/**
 * Exception thrown when clock moves backwards significantly
 */
class ClockBackwardException(
    message: String,
    val backwardMs: Long
) : RuntimeException(message)

/**
 * Snowflake-like ID Generator with Safe Clock Backward Handling
 *
 * Structure (64 bits):
 * - 1 bit: sign (always 0)
 * - 41 bits: timestamp (ms since epoch) - ~69 years
 * - 10 bits: machine/process ID - 1024 unique sources
 * - 12 bits: sequence - 4096 IDs per ms
 *
 * Clock Backward Handling:
 * 1. Tolerable range (0-2s): Wait for clock to catch up
 * 2. Moderate range (2-5s): Use sequence compensation
 * 3. Severe (>5s): Throw exception to prevent ID collision
 *
 * Persistence:
 * - Last timestamp persisted to file to detect clock backward after restart
 * - Async write to avoid blocking ID generation
 *
 * Benefits:
 * - Roughly time-ordered
 * - No coordination needed
 * - Works offline
 * - Fits in Long (efficient storage)
 * - Production-safe clock handling
 */
class SnowflakeIdGenerator(
    private val machineId: Long = generateMachineId(),
    private val clock: Clock = SystemClock.instance,
    private val maxClockBackwardMs: Long = 2000,
    private val persistenceDir: File? = null,
    private val logger: Logger = NoOpLogger
) : IdGenerator {

    private val sequence = AtomicLong(0)

    @Volatile
    private var lastTimestamp = -1L

    private val persistedTimestampFile: File? = persistenceDir?.let {
        File(it, "snowflake_last_timestamp.txt")
    }

    companion object {
        // Custom epoch: Jan 1, 2024 00:00:00 UTC
        // Gives us ~69 years before overflow
        private const val CUSTOM_EPOCH = 1704067200000L

        // Bit allocation
        private const val MACHINE_ID_BITS = 10
        private const val SEQUENCE_BITS = 12

        // Max values
        private const val MAX_MACHINE_ID = (1L shl MACHINE_ID_BITS) - 1
        private const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1

        // Bit shifts
        private const val MACHINE_ID_SHIFT = SEQUENCE_BITS
        private const val TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS

        // Clock backward thresholds
        private const val MODERATE_BACKWARD_MS = 5000L

        /**
         * Generate a pseudo-random machine ID based on device characteristics
         */
        private fun generateMachineId(): Long {
            val random = SecureRandom()
            return (random.nextLong() and MAX_MACHINE_ID)
        }

        /**
         * Singleton instance
         */
        val instance: IdGenerator by lazy {
            SnowflakeIdGenerator()
        }

        /**
         * Create instance with persistence
         */
        fun withPersistence(persistenceDir: File, logger: Logger = NoOpLogger): SnowflakeIdGenerator {
            return SnowflakeIdGenerator(
                persistenceDir = persistenceDir,
                logger = logger
            )
        }
    }

    init {
        require(machineId in 0..MAX_MACHINE_ID) {
            "Machine ID must be between 0 and $MAX_MACHINE_ID"
        }

        // Load persisted timestamp to detect restart-related clock backward
        loadLastTimestamp()

        logger.i(
            "SnowflakeId",
            "Initialized with machineId=$machineId, maxBackward=${maxClockBackwardMs}ms, " +
                    "lastTimestamp=$lastTimestamp"
        )
    }

    @Synchronized
    override fun nextId(): Long {
        var currentTs = currentTimestamp()

        // Clock moved backwards detection
        if (currentTs < lastTimestamp) {
            val backwardMs = lastTimestamp - currentTs

            logger.w(
                "SnowflakeId",
                "Clock moved backwards by ${backwardMs}ms (last=$lastTimestamp, current=$currentTs)"
            )

            when {
                // Tolerable: Wait for clock to catch up
                backwardMs <= maxClockBackwardMs -> {
                    logger.i("SnowflakeId", "Waiting ${backwardMs}ms for clock to catch up")
                    Thread.sleep(backwardMs)
                    currentTs = currentTimestamp()

                    // Verify clock caught up
                    if (currentTs < lastTimestamp) {
                        throw ClockBackwardException(
                            "Clock still backwards after waiting",
                            backwardMs
                        )
                    }
                }

                // Moderate: Use sequence compensation
                backwardMs <= MODERATE_BACKWARD_MS -> {
                    logger.w(
                        "SnowflakeId",
                        "Using sequence compensation for ${backwardMs}ms backward"
                    )
                    return generateIdWithSequenceCompensation()
                }

                // Severe: Throw exception
                else -> {
                    logger.e(
                        "SnowflakeId",
                        "Clock moved backwards by ${backwardMs}ms, exceeds threshold"
                    )
                    throw ClockBackwardException(
                        "Clock moved backwards by ${backwardMs}ms (max allowed: ${maxClockBackwardMs}ms). " +
                                "This may indicate system time tampering or NTP sync issues.",
                        backwardMs
                    )
                }
            }
        }

        // Normal flow: same millisecond or newer
        if (currentTs == lastTimestamp) {
            // Same millisecond - increment sequence
            val seq = sequence.incrementAndGet() and MAX_SEQUENCE
            if (seq == 0L) {
                // Sequence overflow - wait for next millisecond
                currentTs = waitNextMillis(lastTimestamp)
            }
        } else {
            // New millisecond - reset sequence
            sequence.set(0)
        }

        lastTimestamp = currentTs

        // Async persist timestamp (non-blocking)
        persistTimestamp(currentTs)

        return composeId(currentTs)
    }

    override fun nextIdString(): String = nextId().toString()

    /**
     * Sequence compensation strategy for moderate clock backward
     * Uses old timestamp but increments sequence number
     */
    private fun generateIdWithSequenceCompensation(): Long {
        val seq = sequence.incrementAndGet() and MAX_SEQUENCE

        if (seq == 0L) {
            // Sequence exhausted - must wait for clock
            logger.w("SnowflakeId", "Sequence exhausted during compensation, waiting for clock")
            val currentTs = waitNextMillis(lastTimestamp)
            lastTimestamp = currentTs
            persistTimestamp(currentTs)
            return composeId(currentTs)
        }

        // Use old timestamp with new sequence
        // Note: ID will appear older than reality, but still unique
        logger.d("SnowflakeId", "Generated compensated ID with seq=$seq")
        return composeId(lastTimestamp)
    }

    private fun composeId(timestamp: Long): Long {
        return ((timestamp - CUSTOM_EPOCH) shl TIMESTAMP_SHIFT) or
                (machineId shl MACHINE_ID_SHIFT) or
                sequence.get()
    }

    private fun currentTimestamp(): Long = clock.nowMillis()

    private fun waitNextMillis(lastTs: Long): Long {
        var ts = currentTimestamp()
        var iterations = 0
        while (ts <= lastTs) {
            ts = currentTimestamp()
            iterations++

            // Safety: Prevent infinite loop
            if (iterations > 10000) {
                logger.e("SnowflakeId", "Stuck waiting for next millisecond, iterations=$iterations")
                throw IllegalStateException("Failed to wait for next millisecond after $iterations attempts")
            }
        }
        if (iterations > 1) {
            logger.v("SnowflakeId", "Waited $iterations iterations for next millisecond")
        }
        return ts
    }

    /**
     * Asynchronously persist timestamp to file
     * Non-blocking to avoid impacting ID generation performance
     */
    private fun persistTimestamp(ts: Long) {
        val file = persistedTimestampFile ?: return

        GlobalScope.launch(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(ts.toString())
            } catch (e: Exception) {
                logger.w("SnowflakeId", "Failed to persist timestamp: ${e.message}")
            }
        }
    }

    /**
     * Load last persisted timestamp
     * Helps detect clock backward after app restart
     */
    private fun loadLastTimestamp() {
        val file = persistedTimestampFile ?: return

        try {
            if (file.exists()) {
                val persisted = file.readText().toLongOrNull()
                if (persisted != null && persisted > lastTimestamp) {
                    lastTimestamp = persisted
                    logger.i("SnowflakeId", "Loaded persisted timestamp: $persisted")

                    // Check if current time is before persisted time
                    val currentTs = currentTimestamp()
                    if (currentTs < persisted) {
                        val backwardMs = persisted - currentTs
                        logger.w(
                            "SnowflakeId",
                            "Detected ${backwardMs}ms clock backward since last run"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("SnowflakeId", "Failed to load persisted timestamp: ${e.message}")
        }
    }

    /**
     * Extract timestamp from a Snowflake ID
     */
    fun extractTimestamp(id: Long): Long {
        return (id shr TIMESTAMP_SHIFT) + CUSTOM_EPOCH
    }

    /**
     * Extract machine ID from a Snowflake ID
     */
    fun extractMachineId(id: Long): Long {
        return (id shr MACHINE_ID_SHIFT) and MAX_MACHINE_ID
    }

    /**
     * Extract sequence from a Snowflake ID
     */
    fun extractSequence(id: Long): Long {
        return id and MAX_SEQUENCE
    }

    /**
     * Get current statistics
     */
    fun getStats(): SnowflakeStats {
        return SnowflakeStats(
            machineId = machineId,
            lastTimestamp = lastTimestamp,
            currentSequence = sequence.get(),
            hasPersistence = persistedTimestampFile != null
        )
    }
}

/**
 * Snowflake generator statistics
 */
data class SnowflakeStats(
    val machineId: Long,
    val lastTimestamp: Long,
    val currentSequence: Long,
    val hasPersistence: Boolean
)

/**
 * UUID-based generator (for compatibility/debugging)
 */
class UuidIdGenerator : IdGenerator {

    override fun nextId(): Long {
        // Use most significant bits of UUID (good enough for uniqueness)
        return UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    }

    override fun nextIdString(): String = UUID.randomUUID().toString()

    companion object {
        val instance: IdGenerator by lazy { UuidIdGenerator() }
    }
}

/**
 * Sequential generator for testing
 * Predictable IDs make test assertions easier
 */
class SequentialIdGenerator(
    startFrom: Long = 1L
) : IdGenerator {

    private val counter = AtomicLong(startFrom)

    override fun nextId(): Long = counter.getAndIncrement()

    override fun nextIdString(): String = nextId().toString()

    /**
     * Reset counter (useful between tests)
     */
    fun reset(startFrom: Long = 1L) {
        counter.set(startFrom)
    }

    /**
     * Get current value without incrementing
     */
    fun peek(): Long = counter.get()
}

/**
 * Prefixed ID generator - adds a prefix for readability
 * e.g., "mem_123456789", "task_987654321"
 */
class PrefixedIdGenerator(
    private val prefix: String,
    private val delegate: IdGenerator = SnowflakeIdGenerator.instance
) : IdGenerator {

    override fun nextId(): Long = delegate.nextId()

    override fun nextIdString(): String = "${prefix}_${delegate.nextId()}"
}

// ========== Convenience Functions ==========

/**
 * Generate a memory fact ID
 */
fun IdGenerator.nextFactId(): String = "fact_${nextId()}"

/**
 * Generate a memory episode ID
 */
fun IdGenerator.nextEpisodeId(): String = "ep_${nextId()}"

/**
 * Generate a task ID
 */
fun IdGenerator.nextTaskId(): String = "task_${nextId()}"

/**
 * Generate a trace ID (for debugging/logging)
 */
fun IdGenerator.nextTraceId(): String = "trace_${nextId()}"

/**
 * Generate a WAL record ID
 */
fun IdGenerator.nextWalId(): String = "wal_${nextId()}"
