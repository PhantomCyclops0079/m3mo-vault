package com.m3mo.vault.core.event

import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.common.IdGenerator
import com.m3mo.vault.core.common.SnowflakeIdGenerator
import com.m3mo.vault.core.common.SystemClock

/**
 * EventEnvelope - Wrapper containing event + metadata
 *
 * Every event is wrapped in an envelope that provides:
 * - Unique event ID (for deduplication/debugging)
 * - Timestamp (when the event was created)
 * - Trace ID (for distributed tracing / debugging)
 * - Event type name (for logging/filtering)
 *
 * This enables:
 * - Event sourcing patterns
 * - Debugging with trace IDs
 * - Metrics collection
 * - Event replay
 */
data class EventEnvelope<T : Any>(
    /**
     * Unique identifier for this event instance
     */
    val eventId: String,

    /**
     * The actual event payload
     */
    val event: T,

    /**
     * When the event was created (epoch milliseconds)
     */
    val timestamp: Long,

    /**
     * Type name for logging/filtering
     */
    val eventType: String,

    /**
     * Optional trace ID for debugging/correlation
     */
    val traceId: String? = null,

    /**
     * Additional metadata
     */
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        private val idGenerator: IdGenerator = SnowflakeIdGenerator.instance
        private val clock: Clock = SystemClock.instance

        /**
         * Create an envelope for an event
         */
        fun <T : Any> wrap(
            event: T,
            traceId: String? = null,
            metadata: Map<String, String> = emptyMap(),
            clock: Clock = this.clock,
            idGenerator: IdGenerator = this.idGenerator
        ): EventEnvelope<T> {
            return EventEnvelope(
                eventId = "evt_${idGenerator.nextId()}",
                event = event,
                timestamp = clock.nowMillis(),
                eventType = event::class.simpleName ?: "Unknown",
                traceId = traceId,
                metadata = metadata
            )
        }

        /**
         * Create with explicit event ID (for replay/testing)
         */
        fun <T : Any> create(
            eventId: String,
            event: T,
            timestamp: Long,
            traceId: String? = null,
            metadata: Map<String, String> = emptyMap()
        ): EventEnvelope<T> {
            return EventEnvelope(
                eventId = eventId,
                event = event,
                timestamp = timestamp,
                eventType = event::class.simpleName ?: "Unknown",
                traceId = traceId,
                metadata = metadata
            )
        }
    }

    /**
     * Create a new envelope with additional metadata
     */
    fun withMetadata(key: String, value: String): EventEnvelope<T> {
        return copy(metadata = metadata + (key to value))
    }

    /**
     * Create a new envelope with a trace ID
     */
    fun withTraceId(traceId: String): EventEnvelope<T> {
        return copy(traceId = traceId)
    }

    /**
     * Get age of this event in milliseconds
     */
    fun ageMillis(clock: Clock = SystemClock.instance): Long {
        return clock.nowMillis() - timestamp
    }

    /**
     * Check if event is older than given milliseconds
     */
    fun isOlderThan(millis: Long, clock: Clock = SystemClock.instance): Boolean {
        return ageMillis(clock) > millis
    }

    /**
     * Format for logging
     */
    fun toLogString(): String {
        val trace = traceId?.let { " trace=$it" } ?: ""
        return "[$eventType] id=$eventId ts=$timestamp$trace"
    }

    override fun toString(): String {
        return "EventEnvelope(eventId=$eventId, type=$eventType, ts=$timestamp, traceId=$traceId)"
    }
}

/**
 * Builder for creating event envelopes with fluent API
 */
class EventEnvelopeBuilder<T : Any>(private val event: T) {
    private var traceId: String? = null
    private val metadata = mutableMapOf<String, String>()
    private var clock: Clock = SystemClock.instance
    private var idGenerator: IdGenerator = SnowflakeIdGenerator.instance

    fun traceId(traceId: String?) = apply { this.traceId = traceId }
    fun metadata(key: String, value: String) = apply { metadata[key] = value }
    fun clock(clock: Clock) = apply { this.clock = clock }
    fun idGenerator(idGenerator: IdGenerator) = apply { this.idGenerator = idGenerator }

    fun build(): EventEnvelope<T> {
        return EventEnvelope.wrap(
            event = event,
            traceId = traceId,
            metadata = metadata.toMap(),
            clock = clock,
            idGenerator = idGenerator
        )
    }
}

/**
 * Extension for fluent envelope creation
 */
fun <T : Any> T.toEnvelope(): EventEnvelope<T> = EventEnvelope.wrap(this)

fun <T : Any> T.toEnvelopeWith(
    traceId: String? = null,
    metadata: Map<String, String> = emptyMap()
): EventEnvelope<T> = EventEnvelope.wrap(this, traceId, metadata)

fun <T : Any> T.envelopeBuilder(): EventEnvelopeBuilder<T> = EventEnvelopeBuilder(this)
