package com.m3mo.vault.core.event

import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.NoOpLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Subscriber - Base class for event subscribers
 */
abstract class Subscriber(
    @PublishedApi internal val eventBus: EventBus,
    @PublishedApi internal val logger: Logger = NoOpLogger
) {
    open val name: String = this::class.simpleName ?: "Subscriber"

    @PublishedApi
    internal val scope = CoroutineScope(
        SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    logger.e(LogTags.EVENT, "Uncaught exception in $name", throwable)
                    onError(throwable)
                }
    )

    @PublishedApi
    internal val subscriptionJobs = mutableListOf<Job>()
    private var isRunning = false

    /**
     * Register a handler for an event type
     */
    protected inline fun <reified T : Any> on(
        crossinline handler: suspend (T) -> Unit
    ) {
        onEnvelope<T> { envelope ->
            handler(envelope.event)
        }
    }

    /**
     * Register a handler that receives the full envelope
     */
    protected inline fun <reified T : Any> onEnvelope(
        crossinline handler: suspend (EventEnvelope<T>) -> Unit
    ) {
        val job = eventBus.subscribe(T::class)
            .onEach { envelope ->
                try {
                    handler(envelope)
                } catch (e: Exception) {
                    logger.e(
                        LogTags.EVENT,
                        "$name failed to handle ${envelope.eventType}: ${e.message}",
                        e
                    )
                    onHandlerError(envelope.event, e)
                }
            }
            .catch { e ->
                logger.e(LogTags.EVENT, "$name subscription error", e)
            }
            .launchIn(scope)

        subscriptionJobs.add(job)
    }

    /**
     * Register a handler with custom event class
     */
    protected fun <T : Any> on(
        eventClass: KClass<T>,
        handler: suspend (T) -> Unit
    ) {
        val job = eventBus.subscribe(eventClass)
            .onEach { envelope ->
                try {
                    handler(envelope.event)
                } catch (e: Exception) {
                    logger.e(
                        LogTags.EVENT,
                        "$name failed to handle ${envelope.eventType}: ${e.message}",
                        e
                    )
                    onHandlerError(envelope.event, e)
                }
            }
            .catch { e ->
                logger.e(LogTags.EVENT, "$name subscription error", e)
            }
            .launchIn(scope)

        subscriptionJobs.add(job)
    }

    open fun start() {
        if (isRunning) {
            logger.w(LogTags.EVENT, "$name is already running")
            return
        }
        isRunning = true
        logger.i(LogTags.EVENT, "$name started with ${subscriptionJobs.size} subscriptions")
        onStart()
    }

    open fun stop() {
        if (!isRunning) {
            logger.w(LogTags.EVENT, "$name is not running")
            return
        }
        isRunning = false
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        scope.cancel()
        logger.i(LogTags.EVENT, "$name stopped")
        onStop()
    }

    fun isActive(): Boolean = isRunning

    protected fun async(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    protected open fun onStart() {}
    protected open fun onStop() {}
    protected open fun onError(throwable: Throwable) {}
    protected open fun onHandlerError(event: Any, error: Exception) {}
}

class SingleEventSubscriber<T : Any>(
    eventBus: EventBus,
    private val eventClass: KClass<T>,
    private val handler: suspend (T) -> Unit,
    logger: Logger = NoOpLogger
) : Subscriber(eventBus, logger) {

    override val name: String = "SingleEventSubscriber[${eventClass.simpleName}]"

    init {
        on(eventClass, handler)
    }
}

inline fun <reified T : Any> EventBus.subscribe(
    logger: Logger = NoOpLogger,
    crossinline handler: suspend (T) -> Unit
): Subscriber {
    return SingleEventSubscriber(
        eventBus = this,
        eventClass = T::class,
        handler = { handler(it) },
        logger = logger
    ).also { it.start() }
}

class SubscriberRegistry(
    private val logger: Logger = NoOpLogger
) {
    private val subscribers = mutableListOf<Subscriber>()

    fun register(subscriber: Subscriber) {
        subscribers.add(subscriber)
        logger.d(LogTags.EVENT, "Registered subscriber: ${subscriber.name}")
    }

    fun startAll() {
        logger.i(LogTags.EVENT, "Starting ${subscribers.size} subscribers")
        subscribers.forEach { it.start() }
    }

    fun stopAll() {
        logger.i(LogTags.EVENT, "Stopping ${subscribers.size} subscribers")
        subscribers.forEach { it.stop() }
    }

    fun count(): Int = subscribers.size

    fun clear() {
        stopAll()
        subscribers.clear()
    }
}