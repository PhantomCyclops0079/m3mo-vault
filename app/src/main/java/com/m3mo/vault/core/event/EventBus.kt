package com.m3mo.vault.core.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

/**
 * EventBus - In-Process Event Bus Interface
 *
 * A decoupled event system for communication between components.
 *
 * Why use an event bus?
 * 1. Decoupling - Components don't need direct references
 * 2. Flexibility - Easy to add new listeners without changing publishers
 * 3. Testing - Can mock/intercept events easily
 * 4. Metrics - Central place to track all events
 *
 * Use cases in M3mo:
 * - Memory created → Update search index
 * - Memory deleted → Remove from cache
 * - Task completed → Update weekly review progress
 * - Optimization run → Log metrics
 *
 * Thread safety: All implementations must be thread-safe.
 */
interface EventBus {

    /**
     * Publish an event to all subscribers
     *
     * This is non-blocking - subscribers receive events asynchronously.
     * Events are delivered in order per subscriber, but different
     * subscribers may receive events at different times.
     *
     * @param event The event to publish
     */
    suspend fun publish(event: Any)

    /**
     * Publish an event wrapped in an envelope with metadata
     *
     * @param event The event to publish
     * @param traceId Optional trace ID for debugging
     */
    suspend fun publishWithTrace(event: Any, traceId: String? = null)

    /**
     * Subscribe to events of a specific type
     *
     * Returns a cold Flow that emits events matching the given type.
     * The Flow completes when the EventBus is closed.
     *
     * @param eventType The class of events to subscribe to
     * @return A Flow of events
     */
    fun <T : Any> subscribe(eventType: KClass<T>): Flow<EventEnvelope<T>>

    /**
     * Subscribe to all events
     */
    fun subscribeAll(): Flow<EventEnvelope<Any>>

    /**
     * Check if there are any subscribers for an event type
     */
    fun hasSubscribers(eventType: KClass<*>): Boolean

    /**
     * Get the number of subscribers for an event type
     */
    fun subscriberCount(eventType: KClass<*>): Int

    /**
     * Clear all subscribers (mainly for testing)
     */
    fun clearSubscribers()

    /**
     * Close the event bus and release resources
     */
    fun close()
}

/**
 * Extension function for easier subscription with reified type
 */
inline fun <reified T : Any> EventBus.subscribe(): Flow<EventEnvelope<T>> {
    return subscribe(T::class)
}

/**
 * Convenience extension to get just the event without envelope
 */
inline fun <reified T : Any> EventBus.events(): Flow<T> {
    return subscribe(T::class).map { it.event }
}

/**
 * Publish multiple events in order
 */
suspend fun EventBus.publishAll(vararg events: Any) {
    events.forEach { publish(it) }
}

/**
 * Publish multiple events with the same trace ID
 */
suspend fun EventBus.publishAllWithTrace(traceId: String, vararg events: Any) {
    events.forEach { publishWithTrace(it, traceId) }
}
