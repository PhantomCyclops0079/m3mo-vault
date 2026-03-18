package com.m3mo.vault.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * AppDispatchers - Centralized Dispatcher Provider
 *
 * No Dispatchers.IO directly?
 * 1. Testability - Can inject TestDispatchers for unit tests
 * 2. Consistency - Single source of truth for all dispatchers
 * 3. Flexibility - Can add custom dispatchers (e.g., for DB operations)
 * 4. Monitoring - Can wrap with metrics/tracing in one place
 *
 * Usage:
 * ```
 * class MyRepository(private val dispatchers: AppDispatchers) {
 *     suspend fun loadData() = withContext(dispatchers.io) {
 *         // IO-bound work
 *     }
 * }
 * ```
 */
interface AppDispatchers {
    /**
     * For CPU-intensive work (parsing, computation, etc.)
     * Parallelism = number of CPU cores
     */
    val default: CoroutineDispatcher

    /**
     * For IO-bound work (disk, network, database)
     * Parallelism = 64 threads (or more)
     */
    val io: CoroutineDispatcher

    /**
     * For UI updates (Android Main thread)
     * Single-threaded
     */
    val main: CoroutineDispatcher

    /**
     * Immediate execution on Main (no dispatch if already on Main)
     * Use for UI callbacks that need immediate response
     */
    val mainImmediate: CoroutineDispatcher

    /**
     * Unconfined - starts in caller thread, resumes in suspending thread
     * Use sparingly - mainly for simple transformations
     */
    val unconfined: CoroutineDispatcher

    /**
     * Dedicated dispatcher for database operations
     * Limits concurrent DB access to prevent contention
     */
    val database: CoroutineDispatcher

    /**
     * Dedicated dispatcher for memory optimization pipeline
     * Isolated to prevent impacting other operations
     */
    val optimization: CoroutineDispatcher
}

/**
 * Production implementation using real Dispatchers
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAppDispatchers : AppDispatchers {

    override val default: CoroutineDispatcher = Dispatchers.Default

    override val io: CoroutineDispatcher = Dispatchers.IO

    override val main: CoroutineDispatcher = Dispatchers.Main

    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate

    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined

    // Limit database operations to 4 concurrent threads
    // Prevents SQLite "database is locked" errors
    override val database: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(4)

    // Optimization runs on 2 threads - enough for parallelism
    // but doesn't starve other operations
    override val optimization: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(2)

    companion object {
        /**
         * Singleton instance for simple DI scenarios
         * Prefer constructor injection when possible
         */
        val instance: AppDispatchers by lazy { DefaultAppDispatchers() }
    }
}

/**
 * Test implementation - all dispatchers use the same TestDispatcher
 * Allows controlling coroutine execution in tests
 *
 * Usage in tests:
 * ```
 * @Test
 * fun myTest() = runTest {
 *     val testDispatchers = TestAppDispatchers(StandardTestDispatcher(testScheduler))
 *     val repo = MyRepository(testDispatchers)
 *     // Test code...
 *     advanceUntilIdle() // Process all pending coroutines
 * }
 * ```
 */
class TestAppDispatchers(
    private val testDispatcher: CoroutineDispatcher
) : AppDispatchers {
    override val default: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val mainImmediate: CoroutineDispatcher = testDispatcher
    override val unconfined: CoroutineDispatcher = testDispatcher
    override val database: CoroutineDispatcher = testDispatcher
    override val optimization: CoroutineDispatcher = testDispatcher
}

/**
 * Extension to run a block with a specific dispatcher
 */
suspend inline fun <T> AppDispatchers.withIo(
    crossinline block: suspend () -> T
): T = kotlinx.coroutines.withContext(io) { block() }

suspend inline fun <T> AppDispatchers.withDefault(
    crossinline block: suspend () -> T
): T = kotlinx.coroutines.withContext(default) { block() }

suspend inline fun <T> AppDispatchers.withMain(
    crossinline block: suspend () -> T
): T = kotlinx.coroutines.withContext(main) { block() }

suspend inline fun <T> AppDispatchers.withDatabase(
    crossinline block: suspend () -> T
): T = kotlinx.coroutines.withContext(database) { block() }

suspend inline fun <T> AppDispatchers.withOptimization(
    crossinline block: suspend () -> T
): T = kotlinx.coroutines.withContext(optimization) { block() }