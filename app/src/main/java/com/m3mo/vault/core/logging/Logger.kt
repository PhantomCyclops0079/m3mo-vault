package com.m3mo.vault.core.logging

/**
 * Logger - Abstracted Logging Interface
 *
 * Why abstract logging?
 * 1. Testability - Can use NoOpLogger in tests
 * 2. Flexibility - Can switch implementations (Timber, custom, etc.)
 * 3. Filtering - Can add tag-based filtering
 * 4. Privacy - Can redact sensitive data in one place
 *
 * Log levels follow Android's standard:
 * VERBOSE < DEBUG < INFO < WARN < ERROR < ASSERT
 */
interface Logger {

    /**
     * Log a verbose message (most detailed, usually disabled in production)
     */
    fun v(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Log a debug message (for development)
     */
    fun d(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Log an info message (significant but expected events)
     */
    fun i(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Log a warning message (something unexpected but recoverable)
     */
    fun w(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Log an error message (something went wrong)
     */
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Log a "what a terrible failure" message (should never happen)
     */
    fun wtf(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Check if a log level is enabled for a tag
     */
    fun isLoggable(tag: String, level: LogLevel): Boolean

    /**
     * Set minimum log level
     */
    fun setMinLevel(level: LogLevel)

    /**
     * Add a tag filter (only log matching tags)
     */
    fun addTagFilter(pattern: String)

    /**
     * Clear tag filters
     */
    fun clearTagFilters()
}

/**
 * Log levels
 */
enum class LogLevel(val priority: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    ASSERT(7);

    fun isAtLeast(other: LogLevel): Boolean = priority >= other.priority
}

/**
 * Log entry for structured logging
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * No-op logger for testing
 */
object NoOpLogger : Logger {
    override fun v(tag: String, message: String, throwable: Throwable?) {}
    override fun d(tag: String, message: String, throwable: Throwable?) {}
    override fun i(tag: String, message: String, throwable: Throwable?) {}
    override fun w(tag: String, message: String, throwable: Throwable?) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
    override fun wtf(tag: String, message: String, throwable: Throwable?) {}
    override fun isLoggable(tag: String, level: LogLevel): Boolean = false
    override fun setMinLevel(level: LogLevel) {}
    override fun addTagFilter(pattern: String) {}
    override fun clearTagFilters() {}
}

/**
 * Collecting logger for testing - stores all log entries
 */
class CollectingLogger : Logger {
    private val entries = mutableListOf<LogEntry>()
    private var minLevel = LogLevel.VERBOSE

    val logs: List<LogEntry> get() = entries.toList()

    override fun v(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.VERBOSE, tag, message, throwable)
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    override fun wtf(tag: String, message: String, throwable: Throwable?) {
        log(LogLevel.ASSERT, tag, message, throwable)
    }

    override fun isLoggable(tag: String, level: LogLevel): Boolean {
        return level.isAtLeast(minLevel)
    }

    override fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    override fun addTagFilter(pattern: String) {}
    override fun clearTagFilters() {}

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (isLoggable(tag, level)) {
            entries.add(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message,
                    throwable = throwable
                )
            )
        }
    }

    fun clear() = entries.clear()

    fun hasErrorLogs(): Boolean = entries.any { it.level == LogLevel.ERROR }

    fun hasWarningLogs(): Boolean = entries.any { it.level == LogLevel.WARN }

    fun getByTag(tag: String): List<LogEntry> = entries.filter { it.tag == tag }

    fun getByLevel(level: LogLevel): List<LogEntry> = entries.filter { it.level == level }
}

/**
 * Tagged logger - wraps a logger with a fixed tag
 */
class TaggedLogger(
    private val delegate: Logger,
    private val tag: String
) {
    fun v(message: String, throwable: Throwable? = null) = delegate.v(tag, message, throwable)
    fun d(message: String, throwable: Throwable? = null) = delegate.d(tag, message, throwable)
    fun i(message: String, throwable: Throwable? = null) = delegate.i(tag, message, throwable)
    fun w(message: String, throwable: Throwable? = null) = delegate.w(tag, message, throwable)
    fun e(message: String, throwable: Throwable? = null) = delegate.e(tag, message, throwable)
    fun wtf(message: String, throwable: Throwable? = null) = delegate.wtf(tag, message, throwable)
}

/**
 * Create a tagged logger
 */
fun Logger.withTag(tag: String): TaggedLogger = TaggedLogger(this, tag)

/**
 * Extension for lazy message evaluation
 */
inline fun Logger.v(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(tag, LogLevel.VERBOSE)) v(tag, message(), throwable)
}

inline fun Logger.d(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(tag, LogLevel.DEBUG)) d(tag, message(), throwable)
}

inline fun Logger.i(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(tag, LogLevel.INFO)) i(tag, message(), throwable)
}

inline fun Logger.w(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(tag, LogLevel.WARN)) w(tag, message(), throwable)
}

inline fun Logger.e(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(tag, LogLevel.ERROR)) e(tag, message(), throwable)
}

// ========== Standard Tags ==========

/**
 * Predefined tags for consistent logging across the app
 */
object LogTags {
    const val APP = "M3mo"
    const val DATA = "M3mo.Data"
    const val DATABASE = "M3mo.DB"
    const val WAL = "M3mo.WAL"
    const val CACHE = "M3mo.Cache"
    const val DECAY = "M3mo.Decay"
    const val SEMANTIC = "M3mo.Semantic"
    const val PIPELINE = "M3mo.Pipeline"
    const val SEARCH = "M3mo.Search"
    const val REVIEW = "M3mo.Review"
    const val WORKER = "M3mo.Worker"
    const val METRICS = "M3mo.Metrics"
    const val UI = "M3mo.UI"
    const val EVENT = "M3mo.Event"
}
