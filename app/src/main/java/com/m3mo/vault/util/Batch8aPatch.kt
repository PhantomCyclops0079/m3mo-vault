package com.m3mo.vault.util

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.error.AppError

/**
 * Batch8aPatch - Bridge extensions for AppResult and AppError
 *
 * These extensions fill gaps between the domain layer API
 * and what the UI layer needs.
 */

/**
 * Extract data from AppResult, or null on failure.
 *
 * Usage:
 * ```kotlin
 * val memories = repository.getByState(state).dataOrNull() ?: emptyList()
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <T, E> AppResult<T, E>.dataOrNull(): T? {
    return when (this) {
        is AppResult.Success -> {
            // AppResult.Success stores data in .value property
            // If your Success class uses a different property name, change here.
            try {
                val field = this::class.java.declaredFields.firstOrNull {
                    it.name == "value" || it.name == "data"
                }
                field?.isAccessible = true
                field?.get(this) as? T
            } catch (e: Exception) {
                null
            }
        }
        else -> null
    }
}

/**
 * Generic technicalMessage accessor for error objects.
 *
 * Works with AppError (which has technicalMessage in subclasses)
 * and any Throwable.
 */
val Any.technicalMessage: String
    get() = when (this) {
        is AppError -> this.toString()
        is Throwable -> this.message ?: "Unknown error"
        else -> this.toString()
    }