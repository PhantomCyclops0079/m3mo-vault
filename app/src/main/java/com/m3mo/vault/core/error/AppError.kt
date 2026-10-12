package com.m3mo.vault.core.error

/**
 * AppError - Unified Error Hierarchy
 *
 * A comprehensive error type system that:
 * - Provides type-safe error handling
 * - Enables precise error recovery strategies
 * - Supports user-friendly error messages
 * - Facilitates debugging with error codes
 *
 * Error Categories:
 * - Database errors
 * - IO/File errors
 * - Network errors (future)
 * - Validation errors
 * - Business logic errors
 * - System/Resource errors
 */
sealed class AppError {
    /**
     * Unique error code for logging/debugging
     */
    abstract val code: String

    /**
     * User-friendly message (safe to display)
     */
    abstract val userMessage: String

    /**
     * Technical details (for logging)
     */
    abstract val technicalMessage: String

    /**
     * Original cause if any
     */
    open val cause: Throwable? = null

    /**
     * Whether this error is recoverable
     */
    open val isRecoverable: Boolean = true

    /**
     * Suggested recovery action
     */
    open val recoveryHint: String? = null


    sealed class Database : AppError() {
        override val code: String get() = "DB_${this::class.simpleName?.uppercase()}"

        data class ConnectionFailed(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Unable to access local storage"
            override val recoveryHint = "Try restarting the app"
        }

        data class QueryFailed(
            val query: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to retrieve data"
        }

        data class InsertFailed(
            val table: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to save data"
            override val recoveryHint = "Please try again"
        }

        data class UpdateFailed(
            val table: String,
            val id: Long,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to update data"
        }

        data class DeleteFailed(
            val table: String,
            val id: Long,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to delete data"
        }

        data class TransactionFailed(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Operation failed"
            override val recoveryHint = "Please try again"
        }

        data class MigrationFailed(
            val fromVersion: Int,
            val toVersion: Int,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Database upgrade failed"
            override val isRecoverable = false
            override val recoveryHint = "Please reinstall the app"
        }

        data class Corrupted(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Local storage is corrupted"
            override val isRecoverable = false
            override val recoveryHint = "Please restore from backup"
        }

        data class Locked(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Storage is temporarily busy"
            override val recoveryHint = "Please wait and try again"
        }

        /**
         * Generic write error for repository operations
         */
        data class WriteError(
            val operation: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to save changes"
            override val recoveryHint = "Please try again"
        }

        /**
         * Generic read error for repository operations
         */
        data class ReadError(
            val operation: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Database() {
            override val userMessage = "Failed to load data"
            override val recoveryHint = "Please try again"
        }
    }


    sealed class IO : AppError() {
        override val code: String get() = "IO_${this::class.simpleName?.uppercase()}"

        data class FileNotFound(
            val path: String,
            override val technicalMessage: String = "File not found: $path"
        ) : IO() {
            override val userMessage = "File not found"
        }

        data class ReadFailed(
            val path: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : IO() {
            override val userMessage = "Failed to read file"
        }

        data class WriteFailed(
            val path: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : IO() {
            override val userMessage = "Failed to save file"
            override val recoveryHint = "Check available storage space"
        }

        data class InsufficientStorage(
            val requiredBytes: Long,
            val availableBytes: Long,
            override val technicalMessage: String = "Need $requiredBytes bytes, only $availableBytes available"
        ) : IO() {
            override val userMessage = "Not enough storage space"
            override val recoveryHint = "Free up some space and try again"
        }

        data class PermissionDenied(
            val path: String,
            val permission: String,
            override val technicalMessage: String = "Permission denied for $path"
        ) : IO() {
            override val userMessage = "Permission required"
            override val recoveryHint = "Grant the required permission in Settings"
        }
    }


    sealed class Validation : AppError() {
        override val code: String get() = "VAL_${this::class.simpleName?.uppercase()}"
        override val isRecoverable = true

        data class InvalidInput(
            val field: String,
            val reason: String,
            override val technicalMessage: String = "Invalid $field: $reason"
        ) : Validation() {
            override val userMessage = reason
        }

        data class MissingRequired(
            val field: String,
            override val technicalMessage: String = "Required field missing: $field"
        ) : Validation() {
            override val userMessage = "$field is required"
        }

        data class OutOfRange(
            val field: String,
            val value: Any,
            val min: Any,
            val max: Any,
            override val technicalMessage: String = "$field value $value is out of range [$min, $max]"
        ) : Validation() {
            override val userMessage = "$field must be between $min and $max"
        }

        data class TooLong(
            val field: String,
            val actual: Int,
            val maxLength: Int,
            override val technicalMessage: String = "$field is too long: $actual > $maxLength"
        ) : Validation() {
            override val userMessage = "$field is too long (max $maxLength characters)"
        }

        data class InvalidFormat(
            val field: String,
            val expectedFormat: String,
            override val technicalMessage: String = "$field has invalid format, expected: $expectedFormat"
        ) : Validation() {
            override val userMessage = "Invalid format for $field"
        }
    }


    sealed class Business : AppError() {
        override val code: String get() = "BIZ_${this::class.simpleName?.uppercase()}"

        data class NotFound(
            val entity: String,
            val id: Any,
            override val technicalMessage: String = "$entity with id $id not found"
        ) : Business() {
            override val userMessage = "$entity not found"
        }

        data class DuplicateEntry(
            val entity: String,
            override val technicalMessage: String
        ) : Business() {
            override val userMessage = "This $entity already exists"
        }

        data class OperationNotAllowed(
            val operation: String,
            val reason: String,
            override val technicalMessage: String = "Operation $operation not allowed: $reason"
        ) : Business() {
            override val userMessage = reason
        }

        data class StateConflict(
            val entity: String,
            val currentState: String,
            val requiredState: String,
            override val technicalMessage: String = "$entity is in state $currentState but $requiredState is required"
        ) : Business() {
            override val userMessage = "Cannot perform this action right now"
            override val recoveryHint = "Wait for the current operation to complete"
        }

        /**
         * Optimistic lock conflict - data was modified by another operation
         * Used for concurrent access control
         */
        data class OptimisticLockConflict(
            val entity: String,
            val id: Any,
            val expectedVersion: Long,
            val actualVersion: Long,
            override val technicalMessage: String = "$entity(id=$id) version conflict: expected $expectedVersion but was $actualVersion"
        ) : Business() {
            override val userMessage = "Data was modified by another operation"
            override val recoveryHint = "Please refresh and try again"
            override val isRecoverable: Boolean = true
        }
    }


    sealed class Resource : AppError() {
        override val code: String get() = "RES_${this::class.simpleName?.uppercase()}"

        data class OutOfMemory(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Resource() {
            override val userMessage = "Not enough memory"
            override val recoveryHint = "Close other apps and try again"
        }

        data class Timeout(
            val operation: String,
            val timeoutMs: Long,
            override val technicalMessage: String = "$operation timed out after ${timeoutMs}ms"
        ) : Resource() {
            override val userMessage = "Operation took too long"
            override val recoveryHint = "Please try again"
        }

        data class Cancelled(
            val operation: String,
            override val technicalMessage: String = "$operation was cancelled"
        ) : Resource() {
            override val userMessage = "Operation was cancelled"
        }

        data class DeviceBusy(
            override val technicalMessage: String
        ) : Resource() {
            override val userMessage = "Device is busy"
            override val recoveryHint = "Please wait a moment and try again"
        }

        data class ThermalThrottling(
            override val technicalMessage: String = "Device is thermal throttling"
        ) : Resource() {
            override val userMessage = "Device is too warm"
            override val recoveryHint = "Let the device cool down"
        }
    }


    sealed class Internal : AppError() {
        override val code: String get() = "INTERNAL_${this::class.simpleName?.uppercase()}"

        data class UnexpectedError(
            val message: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Internal() {
            override val userMessage = message
            override val recoveryHint = "Please try again or restart the app"
        }

        data class ConfigurationError(
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Internal() {
            override val userMessage = "Configuration error"
            override val recoveryHint = "Please reinstall the app"
        }

        data class InitializationFailed(
            val component: String,
            override val technicalMessage: String,
            override val cause: Throwable? = null
        ) : Internal() {
            override val userMessage = "Failed to initialize $component"
            override val recoveryHint = "Please restart the app"
        }
    }


    data class Unknown(
        override val technicalMessage: String,
        override val cause: Throwable? = null,
        override val userMessage: String = "An unexpected error occurred"
    ) : AppError() {
        override val code = "UNKNOWN"
        override val recoveryHint = "Please try again or restart the app"
    }
}

/**
 * Convert to user-facing message
 */
fun AppError.toDisplayMessage(): String {
    return if (recoveryHint != null) {
        "$userMessage. $recoveryHint"
    } else {
        userMessage
    }
}

/**
 * Convert to log message
 */
fun AppError.toLogMessage(): String {
    return "[$code] $technicalMessage" + (cause?.let { " | Cause: ${it.message}" } ?: "")
}