package com.m3mo.vault.core.event

import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.NoOpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * InProcessEventBus - SharedFlow-based Event Bus Implementation
 *
 * Features:
 * - Type-safe event subscription
 * - Configurable backpressure handling (explicit BufferOverflow strategy)
 * - Subscriber counting
 * - Event logging/tracing
 * - Thread-safe
 * - Critical event protection
 *
 * Configuration options:
 * - bufferCapacity: How many events to buffer when subscribers are slow
 * - replayCount: How many recent events new subscribers receive
 * - onBufferOverflow: What to do when buffer is full (DROP_OLDEST recommended)
 *
 * Performance Notes:
 * - For high-frequency events (UI scroll, metrics), use bufferCapacity=256+
 * - For critical events (data consistency), use SUSPEND strategy
 * - Default DROP_OLDEST prevents memory leaks from slow subscribers
 *
 * Note: Events are NOT persisted - this is for in-memory communication only.
 * For event sourcing, use WAL or a dedicated event store.
 */
class InProcessEventBus(
    private val bufferCapacity: Int = 64,
    private val replayCount: Int = 0,
    private val onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST,
    private val logger: Logger = NoOpLogger
) : EventBus {

    // Internal shared flow for all events
    private val eventFlow = MutableSharedFlow<EventEnvelope<Any>>(
        replay = replayCount,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = onBufferOverflow
    )

    // Track subscriber counts per event type
    private val subscriberCounts = ConcurrentHashMap<KClass<*>, AtomicInteger>()

    // Internal scope for any background processing
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Closed flag
    private val isClosed = AtomicBoolean(false)

    // Metrics
    private val totalPublished = AtomicInteger(0)
    private val totalDropped = AtomicInteger(0)
    private val criticalEventsForced = AtomicInteger(0)

    override suspend fun publish(event: Any) {
        publishWithTrace(event, null)
    }

    override suspend fun publishWithTrace(event: Any, traceId: String?) {
        if (isClosed.get()) {
            logger.w(LogTags.EVENT, "Attempted to publish to closed EventBus: ${event::class.simpleName}")
            return
        }

        val envelope = EventEnvelope.wrap(event, traceId)

        logger.d(LogTags.EVENT, "Publishing: ${envelope.toLogString()}")

        // Try non-blocking emit first
        val emitted = eventFlow.tryEmit(envelope as EventEnvelope<Any>)

        if (emitted) {
            totalPublished.incrementAndGet()
        } else {
            // Buffer full - handle based on event criticality
            totalDropped.incrementAndGet()

            if (isCriticalEvent(event)) {
                // Critical events: Force delivery via suspending emit
                logger.w(
                    LogTags.EVENT,
                    "Critical event buffer full, using suspend emit: ${envelope.eventType}"
                )
                try {
                    eventFlow.emit(envelope) // Will suspend until space available
                    totalPublished.incrementAndGet()
                    totalDropped.decrementAndGet() // Correct the count
                    criticalEventsForced.incrementAndGet()
                } catch (e: Exception) {
                    logger.e(LogTags.EVENT, "Failed to emit critical event: ${envelope.eventType}", e)
                }
            } else {
                // Non-critical events: Already handled by DROP_OLDEST
                logger.w(
                    LogTags.EVENT,
                    "Non-critical event dropped (buffer full): ${envelope.eventType}"
                )
            }
        }
    }

    /**
     * Determine if an event is critical and should never be dropped
     * Override this in subclasses or configure via constructor parameter
     */
    protected open fun isCriticalEvent(event: Any): Boolean {
        // Default: Check for marker interface or specific types
        return when (event) {
            is CriticalEvent -> true
            // Add your critical event types here:
            // is MemoryCreatedEvent,
            // is MemoryDeletedEvent,
            // is OptimizationCompletedEvent -> true
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventType: KClass<T>): Flow<EventEnvelope<T>> {
        // Increment subscriber count
        subscriberCounts.computeIfAbsent(eventType) { AtomicInteger(0) }.incrementAndGet()

        logger.d(LogTags.EVENT, "New subscriber for ${eventType.simpleName}")

        return eventFlow
            .filter { envelope ->
                eventType.isInstance(envelope.event)
            }
            .map { envelope ->
                envelope as EventEnvelope<T>
            }
            .onEach { envelope ->
                logger.v(LogTags.EVENT, "Delivering to ${eventType.simpleName}: ${envelope.eventId}")
            }
    }

    override fun subscribeAll(): Flow<EventEnvelope<Any>> {
        return eventFlow
    }

    override fun hasSubscribers(eventType: KClass<*>): Boolean {
        return (subscriberCounts[eventType]?.get() ?: 0) > 0
    }

    override fun subscriberCount(eventType: KClass<*>): Int {
        return subscriberCounts[eventType]?.get() ?: 0
    }

    override fun clearSubscribers() {
        subscriberCounts.clear()
        logger.i(LogTags.EVENT, "Cleared all subscriber counts")
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            logger.i(
                LogTags.EVENT,
                "Closing EventBus. Published: $totalPublished, Dropped: $totalDropped, " +
                        "Critical forced: $criticalEventsForced"
            )
            scope.cancel()
            subscriberCounts.clear()
        }
    }

    /**
     * Get statistics about the event bus
     */
    fun getStats(): EventBusStats {
        return EventBusStats(
            totalPublished = totalPublished.get(),
            totalDropped = totalDropped.get(),
            criticalEventsForced = criticalEventsForced.get(),
            subscriberCounts = subscriberCounts.mapValues { it.value.get() },
            bufferCapacity = bufferCapacity,
            replayCount = replayCount,
            bufferOverflow = onBufferOverflow,
            isClosed = isClosed.get()
        )
    }

    companion object {
        /**
         * Default instance for simple use cases
         * Prefer dependency injection for better testability
         */
        val instance: EventBus by lazy {
            InProcessEventBus()
        }

        /**
         * Create an instance optimized for high-frequency events
         * Use for: UI scroll events, metrics collection, frequent updates
         */
        fun forHighFrequency(logger: Logger = NoOpLogger): InProcessEventBus {
            return InProcessEventBus(
                bufferCapacity = 256,
                replayCount = 0,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                logger = logger
            )
        }

        /**
         * Create an instance optimized for critical events
         * Use for: Data consistency events, state changes, transactions
         */
        fun forCriticalEvents(logger: Logger = NoOpLogger): InProcessEventBus {
            return InProcessEventBus(
                bufferCapacity = 1024,
                replayCount = 0,
                onBufferOverflow = BufferOverflow.SUSPEND, // Never drop critical events
                logger = logger
            )
        }

        /**
         * Create a test instance with logging
         */
        fun forTesting(logger: Logger = NoOpLogger): InProcessEventBus {
            return InProcessEventBus(
                bufferCapacity = 16,
                replayCount = 0,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                logger = logger
            )
        }
    }
}

/**
 * Marker interface for critical events that should never be dropped
 * Implement this interface for events that are essential for data consistency
 */
interface CriticalEvent

/**
 * Statistics about the event bus
 */
data class EventBusStats(
    val totalPublished: Int,
    val totalDropped: Int,
    val criticalEventsForced: Int,
    val subscriberCounts: Map<KClass<*>, Int>,
    val bufferCapacity: Int,
    val replayCount: Int,
    val bufferOverflow: BufferOverflow,
    val isClosed: Boolean
) {
    /**
     * Calculate drop rate as percentage
     */
    val dropRate: Double
        get() = if (totalPublished > 0) {
            (totalDropped.toDouble() / totalPublished) * 100
        } else 0.0

    /**
     * Check if drop rate is concerning (>5%)
     */
    fun hasHighDropRate(): Boolean = dropRate > 5.0

    override fun toString(): String {
        return """
            EventBusStats(
                published=$totalPublished, 
                dropped=$totalDropped (${String.format("%.2f", dropRate)}%), 
                criticalForced=$criticalEventsForced,
                subscribers=${subscriberCounts.size} types,
                capacity=$bufferCapacity,
                overflow=$bufferOverflow,
                closed=$isClosed
            )
        """.trimIndent()
    }
}

/**
 * Testing helper - collect events from a flow
 */
class EventCollector<T : Any> {
    private val events = mutableListOf<EventEnvelope<T>>()

    fun collect(envelope: EventEnvelope<T>) {
        events.add(envelope)
    }

    fun getEvents(): List<EventEnvelope<T>> = events.toList()

    fun getPayloads(): List<T> = events.map { it.event }

    fun count(): Int = events.size

    fun clear() = events.clear()

    fun isEmpty(): Boolean = events.isEmpty()

    fun last(): EventEnvelope<T>? = events.lastOrNull()

    fun first(): EventEnvelope<T>? = events.firstOrNull()
}

/**
 * Extension to collect events into a collector
 */
suspend fun <T : Any> Flow<EventEnvelope<T>>.collectInto(collector: EventCollector<T>) {
    collect { collector.collect(it) }
}
