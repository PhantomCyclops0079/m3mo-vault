package com.m3mo.vault.core.common

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * RetryPolicy - Intelligent Retry Mechanism
 *
 * Supports:
 * - Exponential backoff with jitter
 * - Configurable max attempts
 * - Selective retry based on exception type
 * - Circuit breaker integration (optional)
 *
 * Why retry?
 * - Database locks (SQLite busy)
 * - Transient IO errors
 * - Resource contention during optimization
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 100,
    val maxDelayMs: Long = 5000,
    val backoffMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.1,
    val retryOn: (Throwable) -> Boolean = { true }
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMs > 0) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be between 0.0 and 1.0" }
    }

    companion object {
        /**
         * No retry - fail immediately
         */
        val noRetry = RetryPolicy(maxAttempts = 1)

        /**
         * Default policy for database operations
         */
        val database = RetryPolicy(
            maxAttempts = 3,
            initialDelayMs = 50,
            maxDelayMs = 500,
            backoffMultiplier = 2.0,
            retryOn = { it.isRetryableDbError() }
        )

        /**
         * Policy for IO operations
         */
        val io = RetryPolicy(
            maxAttempts = 3,
            initialDelayMs = 100,
            maxDelayMs = 2000,
            backoffMultiplier = 2.0
        )

        /**
         * Aggressive retry for critical operations
         */
        val critical = RetryPolicy(
            maxAttempts = 5,
            initialDelayMs = 200,
            maxDelayMs = 10000,
            backoffMultiplier = 2.5,
            jitterFactor = 0.2
        )

        /**
         * Quick retry for transient failures
         */
        val transient = RetryPolicy(
            maxAttempts = 2,
            initialDelayMs = 10,
            maxDelayMs = 100,
            backoffMultiplier = 1.5
        )
    }

    /**
     * Calculate delay for a given attempt (0-indexed)
     */
    fun calculateDelay(attempt: Int): Long {
        val baseDelay = initialDelayMs * backoffMultiplier.pow(attempt.toDouble())
        val cappedDelay = min(baseDelay, maxDelayMs.toDouble())

        // Add jitter to prevent thundering herd
        val jitter = cappedDelay * jitterFactor * (Random.nextDouble() * 2 - 1)
        return (cappedDelay + jitter).toLong().coerceAtLeast(1)
    }
}

/**
 * Result of a retry operation
 */
sealed class RetryResult<out T> {
    data class Success<T>(val value: T, val attempts: Int) : RetryResult<T>()
    data class Exhausted<T>(val lastError: Throwable, val attempts: Int) : RetryResult<T>()
    data class NonRetryable<T>(val error: Throwable, val attempts: Int) : RetryResult<T>()
}

/**
 * Execute a block with retry policy
 */
suspend fun <T> RetryPolicy.execute(
    block: suspend (attempt: Int) -> T
): RetryResult<T> {
    var lastError: Throwable? = null

    for (attempt in 0 until maxAttempts) {
        try {
            val result = block(attempt)
            return RetryResult.Success(result, attempt + 1)
        } catch (e: Throwable) {
            lastError = e

            // Check if we should retry this error
            if (!retryOn(e)) {
                return RetryResult.NonRetryable(e, attempt + 1)
            }

            // Don't delay after the last attempt
            if (attempt < maxAttempts - 1) {
                delay(calculateDelay(attempt))
            }
        }
    }

    return RetryResult.Exhausted(lastError!!, maxAttempts)
}

/**
 * Execute with retry, returning AppResult
 */
suspend fun <T> RetryPolicy.executeToResult(
    block: suspend (attempt: Int) -> T
): AppResult<T, RetryError> {
    return when (val result = execute(block)) {
        is RetryResult.Success -> AppResult.success(result.value)
        is RetryResult.Exhausted -> AppResult.failure(
            RetryError.Exhausted(result.lastError, result.attempts)
        )
        is RetryResult.NonRetryable -> AppResult.failure(
            RetryError.NonRetryable(result.error, result.attempts)
        )
    }
}

/**
 * Execute with retry, throwing on failure
 */
suspend fun <T> RetryPolicy.executeOrThrow(
    block: suspend (attempt: Int) -> T
): T {
    return when (val result = execute(block)) {
        is RetryResult.Success -> result.value
        is RetryResult.Exhausted -> throw RetryExhaustedException(
            "Retry exhausted after ${result.attempts} attempts",
            result.lastError
        )
        is RetryResult.NonRetryable -> throw result.error
    }
}

/**
 * Error types for retry operations
 */
sealed class RetryError {
    abstract val cause: Throwable
    abstract val attempts: Int

    data class Exhausted(
        override val cause: Throwable,
        override val attempts: Int
    ) : RetryError()

    data class NonRetryable(
        override val cause: Throwable,
        override val attempts: Int
    ) : RetryError()
}

/**
 * Exception thrown when retries are exhausted
 */
class RetryExhaustedException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

/**
 * Check if a throwable is a retryable database error
 * Note: SQLiteBusyException doesn't exist in standard Android SDK,
 * so we check the message instead
 */
fun Throwable.isRetryableDbError(): Boolean {
    val message = this.message?.lowercase() ?: ""
    return message.contains("database is locked") ||
            message.contains("busy") ||
            message.contains("cannot start a transaction") ||
            this is android.database.sqlite.SQLiteDatabaseLockedException
}

/**
 * Builder for fluent RetryPolicy construction
 */
class RetryPolicyBuilder {
    private var maxAttempts: Int = 3
    private var initialDelayMs: Long = 100
    private var maxDelayMs: Long = 5000
    private var backoffMultiplier: Double = 2.0
    private var jitterFactor: Double = 0.1
    private var retryOn: (Throwable) -> Boolean = { true }

    fun maxAttempts(value: Int) = apply { maxAttempts = value }
    fun initialDelay(ms: Long) = apply { initialDelayMs = ms }
    fun maxDelay(ms: Long) = apply { maxDelayMs = ms }
    fun backoffMultiplier(value: Double) = apply { backoffMultiplier = value }
    fun jitter(factor: Double) = apply { jitterFactor = factor }
    fun retryOn(predicate: (Throwable) -> Boolean) = apply { retryOn = predicate }

    fun retryOnlyOn(vararg types: Class<out Throwable>) = apply {
        retryOn = { e -> types.any { it.isInstance(e) } }
    }

    fun build() = RetryPolicy(
        maxAttempts = maxAttempts,
        initialDelayMs = initialDelayMs,
        maxDelayMs = maxDelayMs,
        backoffMultiplier = backoffMultiplier,
        jitterFactor = jitterFactor,
        retryOn = retryOn
    )
}

/**
 * DSL for building retry policy
 */
fun retryPolicy(block: RetryPolicyBuilder.() -> Unit): RetryPolicy {
    return RetryPolicyBuilder().apply(block).build()
}

// ========== Extension for AppResult ==========

/**
 * Retry an operation that returns AppResult
 */
suspend fun <T, E> RetryPolicy.retryAppResult(
    block: suspend (attempt: Int) -> AppResult<T, E>
): AppResult<T, E> {
    var lastResult: AppResult<T, E>? = null

    for (attempt in 0 until maxAttempts) {
        val result = block(attempt)

        if (result.isSuccess) {
            return result
        }

        lastResult = result

        // Don't delay after the last attempt
        if (attempt < maxAttempts - 1) {
            delay(calculateDelay(attempt))
        }
    }

    return lastResult!!
}
