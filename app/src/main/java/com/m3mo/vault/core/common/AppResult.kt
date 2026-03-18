package com.m3mo.vault.core.common

/**
 * AppResult - Unified Result Wrapper
 *
 * A sealed class that represents either success or failure,
 * similar to Rust's Result<T, E> or Arrow's Either.
 *
 * Benefits:
 * - Forces explicit error handling
 * - No exceptions flying around
 * - Composable with map/flatMap
 * - Works beautifully with coroutines
 */
sealed class AppResult<out T, out E> {

    /**
     * Success case - contains the value
     */
    data class Success<T>(val value: T) : AppResult<T, Nothing>()

    /**
     * Failure case - contains the error
     */
    data class Failure<E>(val error: E) : AppResult<Nothing, E>()

    /**
     * Check if this is a success
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Check if this is a failure
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Get the value or null
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /**
     * Get the error or null
     */
    fun errorOrNull(): E? = when (this) {
        is Success -> null
        is Failure -> error
    }

    /**
     * Get the value or a default
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    /**
     * Get the value or compute a default from the error
     */
    inline fun getOrElse(onFailure: (E) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> onFailure(error)
    }

    /**
     * Transform the success value
     */
    inline fun <R> map(transform: (T) -> R): AppResult<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /**
     * Transform the error value
     */
    inline fun <F> mapError(transform: (E) -> F): AppResult<T, F> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(error))
    }

    /**
     * Chain operations that return AppResult (flatMap)
     */
    inline fun <R> flatMap(transform: (T) -> AppResult<R, @UnsafeVariance E>): AppResult<R, E> =
        when (this) {
            is Success -> transform(value)
            is Failure -> this
        }

    /**
     * Chain suspend operations that return AppResult
     */
    suspend inline fun <R> suspendFlatMap(crossinline transform: suspend (T) -> AppResult<R, @UnsafeVariance E>): AppResult<R, E> =
        when (this) {
            is Success -> transform(value)
            is Failure -> this
        }

    /**
     * Perform side effect on success
     */
    inline fun onSuccess(action: (T) -> Unit): AppResult<T, E> {
        if (this is Success) action(value)
        return this
    }

    /**
     * Perform suspend side effect on success
     */
    suspend inline fun suspendOnSuccess(crossinline action: suspend (T) -> Unit): AppResult<T, E> {
        if (this is Success) action(value)
        return this
    }

    /**
     * Perform side effect on failure
     */
    inline fun onFailure(action: (E) -> Unit): AppResult<T, E> {
        if (this is Failure) action(error)
        return this
    }

    /**
     * Perform suspend side effect on failure
     */
    suspend inline fun suspendOnFailure(crossinline action: suspend (E) -> Unit): AppResult<T, E> {
        if (this is Failure) action(error)
        return this
    }

    /**
     * Fold into a single value
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (E) -> R
    ): R = when (this) {
            is Success -> onSuccess(value)
            is Failure -> onFailure(error)
        }

    companion object {
        /**
         * Create a success result
         */
        fun <T> success(value: T): AppResult<T, Nothing> = Success(value)

        /**
         * Create a failure result
         */
        fun <E> failure(error: E): AppResult<Nothing, E> = Failure(error)

        /**
         * Wrap a nullable value - null becomes failure
         */
        fun <T, E> fromNullable(value: T?, error: () -> E): AppResult<T, E> =
            if (value != null) Success(value) else Failure(error())

        /**
         * Catch exceptions and wrap as AppResult
         */
        inline fun <T> catching(block: () -> T): AppResult<T, Throwable> =
            try {
                Success(block())
            } catch (e: Throwable) {
                Failure(e)
            }

        /**
         * Catch exceptions with custom error mapping
         */
        inline fun <T, E> catchingMapped(
            errorMapper: (Throwable) -> E,
            block: () -> T
        ): AppResult<T, E> =
            try {
                Success(block())
            } catch (e: Throwable) {
                Failure(errorMapper(e))
            }
    }
}

/**
 * Combine two results - both must succeed
 */
fun <T1, T2, E> AppResult<T1, E>.zip(
    other: AppResult<T2, E>
): AppResult<Pair<T1, T2>, E> = flatMap { v1 ->
    other.map { v2 -> v1 to v2 }
}

/**
 * Combine three results
 */
fun <T1, T2, T3, E> AppResult<T1, E>.zip(
    second: AppResult<T2, E>,
    third: AppResult<T3, E>
): AppResult<Triple<T1, T2, T3>, E> = flatMap { v1 ->
    second.flatMap { v2 ->
        third.map { v3 -> Triple(v1, v2, v3) }
    }
}

/**
 * Sequence a list of results into a result of list
 */
fun <T, E> List<AppResult<T, E>>.sequence(): AppResult<List<T>, E> {
    val results = mutableListOf<T>()
    for (result in this) {
        when (result) {
            is AppResult.Success -> results.add(result.value)
            is AppResult.Failure -> return result
        }
    }
    return AppResult.Success(results)
}

/**
 * Convert nullable to AppResult
 */
fun <T> T?.toResult(errorMessage: String = "Value is null"): AppResult<T, String> =
    if (this != null) AppResult.Success(this)
    else AppResult.Failure(errorMessage)

/**
 * Require non-null or fail with error
 */
inline fun <T, E> T?.requireOrFail(error: () -> E): AppResult<T, E> =
    if (this != null) AppResult.Success(this)
    else AppResult.Failure(error())
