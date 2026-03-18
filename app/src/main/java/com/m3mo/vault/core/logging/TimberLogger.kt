package com.m3mo.vault.core.logging

import android.util.Log
import java.util.regex.Pattern

/**
 * ProductionLogger - Production-ready Logger using Android Log
 *
 * Features:
 * - No external dependencies (no Timber needed)
 * - Customizable log formatting
 * - Privacy-aware redaction
 * - Tag filtering support
 * - Different behavior for debug vs release
 */
class ProductionLogger(
    private val isDebug: Boolean = true
) : Logger {

    private var minLevel = if (isDebug) LogLevel.VERBOSE else LogLevel.WARN
    private val tagFilters = mutableListOf<Pattern>()

    override fun v(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.VERBOSE)) {
            val msg = formatMessage(message)
            if (throwable != null) Log.v(tag, msg, throwable)
            else Log.v(tag, msg)
        }
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.DEBUG)) {
            val msg = formatMessage(message)
            if (throwable != null) Log.d(tag, msg, throwable)
            else Log.d(tag, msg)
        }
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.INFO)) {
            val msg = formatMessage(message)
            if (throwable != null) Log.i(tag, msg, throwable)
            else Log.i(tag, msg)
        }
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.WARN)) {
            val msg = formatMessage(message, redact = !isDebug)
            if (throwable != null) Log.w(tag, msg, throwable)
            else Log.w(tag, msg)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.ERROR)) {
            val msg = formatMessage(message, redact = !isDebug)
            if (throwable != null) Log.e(tag, msg, throwable)
            else Log.e(tag, msg)
        }
    }

    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.ASSERT)) {
            val msg = formatMessage(message, redact = !isDebug)
            if (throwable != null) Log.wtf(tag, msg, throwable)
            else Log.wtf(tag, msg)
        }
    }

    override fun isLoggable(tag: String, level: LogLevel): Boolean {
        return shouldLog(tag, level)
    }

    override fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    override fun addTagFilter(pattern: String) {
        tagFilters.add(Pattern.compile(pattern))
    }

    override fun clearTagFilters() {
        tagFilters.clear()
    }

    private fun shouldLog(tag: String, level: LogLevel): Boolean {
        if (!level.isAtLeast(minLevel)) return false
        if (tagFilters.isNotEmpty()) {
            return tagFilters.any { it.matcher(tag).matches() }
        }
        return true
    }

    private fun formatMessage(message: String, redact: Boolean = false): String {
        var result = message
        if (isDebug) {
            result = "[${Thread.currentThread().name}] $result"
        }
        if (redact) {
            result = redactSensitiveInfo(result)
        }
        return result
    }

    companion object {
        @Volatile
        private var instance: ProductionLogger? = null

        fun getInstance(isDebug: Boolean = true): Logger {
            return instance ?: synchronized(this) {
                instance ?: ProductionLogger(isDebug).also { instance = it }
            }
        }

        fun init(isDebug: Boolean): Logger {
            return ProductionLogger(isDebug).also { instance = it }
        }
    }
}

private fun redactSensitiveInfo(message: String): String {
    var redacted = message
    val sensitivePatterns = listOf(
        Pattern.compile("password[\"']?\\s*[:=]\\s*[\"']?[^\"'\\s]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("token[\"']?\\s*[:=]\\s*[\"']?[^\"'\\s]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("api[_-]?key[\"']?\\s*[:=]\\s*[\"']?[^\"'\\s]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"),
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    )
    for (pattern in sensitivePatterns) {
        redacted = pattern.matcher(redacted).replaceAll("[REDACTED]")
    }
    return redacted
}

class DebugLogger : Logger {
    private var minLevel = LogLevel.VERBOSE
    private val tagFilters = mutableListOf<Pattern>()

    override fun v(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.VERBOSE)) Log.v(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.DEBUG)) Log.d(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.INFO)) Log.i(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.WARN)) Log.w(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.ERROR)) Log.e(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(tag, LogLevel.ASSERT)) Log.wtf(tag, "[${Thread.currentThread().name}] $message", throwable)
    }
    override fun isLoggable(tag: String, level: LogLevel): Boolean = shouldLog(tag, level)
    override fun setMinLevel(level: LogLevel) { minLevel = level }
    override fun addTagFilter(pattern: String) { tagFilters.add(Pattern.compile(pattern)) }
    override fun clearTagFilters() { tagFilters.clear() }

    private fun shouldLog(tag: String, level: LogLevel): Boolean {
        if (!level.isAtLeast(minLevel)) return false
        if (tagFilters.isNotEmpty()) return tagFilters.any { it.matcher(tag).matches() }
        return true
    }

    companion object {
        val instance: Logger by lazy { DebugLogger() }
    }
}

object LoggerFactory {
    fun create(isDebug: Boolean): Logger = if (isDebug) DebugLogger.instance else ProductionLogger.getInstance(false)
    fun noOp(): Logger = NoOpLogger
}