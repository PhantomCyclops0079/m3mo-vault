package com.m3mo.vault.core.error

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * ErrorMapper - Converts exceptions to AppError
 *
 * Centralizes exception-to-error mapping for consistent error handling.
 * Each module can register its own mappers for domain-specific errors.
 */
interface ErrorMapper {
    /**
     * Map a throwable to an AppError
     * Returns null if this mapper doesn't handle this throwable
     */
    fun map(throwable: Throwable): AppError?
}

/**
 * Composite mapper that tries multiple mappers in order
 */
class CompositeErrorMapper(
    private val mappers: List<ErrorMapper>
) : ErrorMapper {

    override fun map(throwable: Throwable): AppError {
        for (mapper in mappers) {
            mapper.map(throwable)?.let { return it }
        }
        // Fallback to unknown error
        return AppError.Unknown(
            technicalMessage = throwable.message ?: throwable::class.simpleName ?: "Unknown error",
            cause = throwable
        )
    }

    companion object {
        /**
         * Create a default mapper with all built-in mappers
         */
        fun default(): CompositeErrorMapper = CompositeErrorMapper(
            listOf(
                DatabaseErrorMapper(),
                IoErrorMapper(),
                SystemErrorMapper()
            )
        )
    }
}

/**
 * Maps database-related exceptions
 */
class DatabaseErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable): AppError? {
        return when (throwable) {
            is SQLiteDatabaseCorruptException -> AppError.Database.Corrupted(
                technicalMessage = throwable.message ?: "Database corrupted",
                cause = throwable
            )

            is SQLiteDatabaseLockedException -> AppError.Database.Locked(
                technicalMessage = throwable.message ?: "Database locked",
                cause = throwable
            )

            is SQLiteFullException -> AppError.IO.InsufficientStorage(
                requiredBytes = 0, // Unknown
                availableBytes = 0,
                technicalMessage = throwable.message ?: "Database full"
            )

            is SQLiteConstraintException -> {
                val message = throwable.message ?: ""
                when {
                    message.contains("UNIQUE", ignoreCase = true) ->
                        AppError.Business.DuplicateEntry(
                            entity = extractTableName(message) ?: "Record",
                            technicalMessage = message
                        )
                    message.contains("FOREIGN KEY", ignoreCase = true) ->
                        AppError.Database.InsertFailed(
                            table = extractTableName(message) ?: "Unknown",
                            technicalMessage = message,
                            cause = throwable
                        )
                    else -> AppError.Database.InsertFailed(
                        table = "Unknown",
                        technicalMessage = message,
                        cause = throwable
                    )
                }
            }

            is SQLiteException -> {
                val message = throwable.message ?: ""
                when {
                    message.contains("locked", ignoreCase = true) ||
                            message.contains("busy", ignoreCase = true) ->
                        AppError.Database.Locked(message, throwable)
                    message.contains("no such table", ignoreCase = true) ->
                        AppError.Database.MigrationFailed(0, 0, message, throwable)
                    else ->
                        AppError.Database.QueryFailed("Unknown", message, throwable)
                }
            }

            else -> null
        }
    }

    private fun extractTableName(message: String): String? {
        // Try to extract table name from constraint message
        // e.g., "UNIQUE constraint failed: memory_facts.content"
        val regex = Regex("(\\w+)\\.(\\w+)")
        return regex.find(message)?.groupValues?.getOrNull(1)
    }
}

/**
 * Maps IO-related exceptions
 */
class IoErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable): AppError? {
        return when (throwable) {
            is FileNotFoundException -> AppError.IO.FileNotFound(
                path = extractPath(throwable.message) ?: "Unknown",
                technicalMessage = throwable.message ?: "File not found"
            )

            is SecurityException -> {
                val message = throwable.message ?: ""
                AppError.IO.PermissionDenied(
                    path = extractPath(message) ?: "Unknown",
                    permission = extractPermission(message) ?: "Unknown",
                    technicalMessage = message
                )
            }

            is IOException -> {
                val message = throwable.message ?: ""
                when {
                    message.contains("No space left", ignoreCase = true) ->
                        AppError.IO.InsufficientStorage(0, 0, message)
                    message.contains("Permission denied", ignoreCase = true) ->
                        AppError.IO.PermissionDenied(
                            path = extractPath(message) ?: "Unknown",
                            permission = "READ/WRITE",
                            technicalMessage = message
                        )
                    else -> AppError.IO.ReadFailed(
                        path = extractPath(message) ?: "Unknown",
                        technicalMessage = message,
                        cause = throwable
                    )
                }
            }

            else -> null
        }
    }

    private fun extractPath(message: String?): String? {
        if (message == null) return null
        // Try common path patterns
        val patterns = listOf(
            Regex("""'([^']+)'"""),
            Regex(""""([^"]+)""""),
            Regex("""\[([^\]]+)\]"""),
            Regex("""(/[\w/.-]+)""")
        )
        for (pattern in patterns) {
            pattern.find(message)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun extractPermission(message: String?): String? {
        if (message == null) return null
        return when {
            message.contains("READ", ignoreCase = true) -> "READ_EXTERNAL_STORAGE"
            message.contains("WRITE", ignoreCase = true) -> "WRITE_EXTERNAL_STORAGE"
            else -> null
        }
    }
}

/**
 * Maps system-level exceptions
 */
class SystemErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable): AppError? {
        return when (throwable) {
            is OutOfMemoryError -> AppError.Resource.OutOfMemory(
                technicalMessage = throwable.message ?: "Out of memory",
                cause = throwable
            )

            is TimeoutException -> AppError.Resource.Timeout(
                operation = "Unknown",
                timeoutMs = 0,
                technicalMessage = throwable.message ?: "Timeout"
            )

            is CancellationException -> AppError.Resource.Cancelled(
                operation = "Unknown",
                technicalMessage = throwable.message ?: "Cancelled"
            )

            is InterruptedException -> AppError.Resource.Cancelled(
                operation = "Unknown",
                technicalMessage = throwable.message ?: "Interrupted"
            )

            is IllegalArgumentException -> AppError.Validation.InvalidInput(
                field = "Unknown",
                reason = throwable.message ?: "Invalid argument",
                technicalMessage = throwable.message ?: "IllegalArgumentException"
            )

            is IllegalStateException -> AppError.Business.StateConflict(
                entity = "Unknown",
                currentState = "Unknown",
                requiredState = "Unknown",
                technicalMessage = throwable.message ?: "Illegal state"
            )

            is NullPointerException -> AppError.Unknown(
                technicalMessage = "NullPointerException: ${throwable.message}",
                cause = throwable,
                userMessage = "An unexpected error occurred"
            )

            else -> null
        }
    }
}

// ========== Extensions ==========

/**
 * Map any throwable to AppError
 */
fun Throwable.toAppError(mapper: ErrorMapper = CompositeErrorMapper.default()): AppError {
    return mapper.map(this) ?: AppError.Unknown(
        technicalMessage = message ?: this::class.simpleName ?: "Unknown error",
        cause = this
    )
}

/**
 * Execute a block and map exceptions to AppError
 */
inline fun <T> runCatchingToAppError(
    mapper: ErrorMapper = CompositeErrorMapper.default(),
    block: () -> T
): com.m3mo.vault.core.common.AppResult<T, AppError> {
    return try {
        com.m3mo.vault.core.common.AppResult.success(block())
    } catch (e: Throwable) {
        com.m3mo.vault.core.common.AppResult.failure(e.toAppError(mapper))
    }
}

/**
 * Execute a suspending block and map exceptions to AppError
 */
suspend inline fun <T> runSuspendCatchingToAppError(
    mapper: ErrorMapper = CompositeErrorMapper.default(),
    crossinline block: suspend () -> T
): com.m3mo.vault.core.common.AppResult<T, AppError> {
    return try {
        com.m3mo.vault.core.common.AppResult.success(block())
    } catch (e: Throwable) {
        com.m3mo.vault.core.common.AppResult.failure(e.toAppError(mapper))
    }
}
