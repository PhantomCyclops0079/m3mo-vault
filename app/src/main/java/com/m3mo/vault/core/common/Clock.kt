package com.m3mo.vault.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Clock - Time Abstraction Layer
 *
 * Why abstract time?
 * 1. Testability - Can freeze/control time in tests
 * 2. Consistency - Single source of truth for "now"
 * 3. SmartDecay - Critical for decay calculations (daysSinceTouch)
 * 4. Weekly Review - Needs deterministic week boundaries
 *
 * An injected clock makes time-dependent calculations repeatable in tests.
 */
interface Clock {
    /**
     * Current time in milliseconds since epoch
     */
    fun nowMillis(): Long

    /**
     * Current time as Instant
     */
    fun nowInstant(): Instant

    /**
     * Current local date-time
     */
    fun nowLocal(): LocalDateTime

    /**
     * Current zoned date-time
     */
    fun nowZoned(): ZonedDateTime

    /**
     * Current date only
     */
    fun today(): LocalDate

    /**
     * Current time zone
     */
    fun zone(): ZoneId
}

/**
 * Production clock using system time
 */
class SystemClock(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : Clock {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun nowInstant(): Instant = Instant.now()

    override fun nowLocal(): LocalDateTime = LocalDateTime.now(zoneId)

    override fun nowZoned(): ZonedDateTime = ZonedDateTime.now(zoneId)

    override fun today(): LocalDate = LocalDate.now(zoneId)

    override fun zone(): ZoneId = zoneId

    companion object {
        val instance: Clock by lazy { SystemClock() }
    }
}

/**
 * Controllable clock for testing
 *
 * Usage:
 * ```
 * val clock = FakeClock()
 * clock.setNow(someInstant)
 * clock.advanceBy(7, ChronoUnit.DAYS)
 * ```
 */
class FakeClock(
    initialMillis: Long = System.currentTimeMillis(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : Clock {

    private val currentMillis = AtomicLong(initialMillis)

    override fun nowMillis(): Long = currentMillis.get()

    override fun nowInstant(): Instant = Instant.ofEpochMilli(currentMillis.get())

    override fun nowLocal(): LocalDateTime =
        LocalDateTime.ofInstant(nowInstant(), zoneId)

    override fun nowZoned(): ZonedDateTime =
        ZonedDateTime.ofInstant(nowInstant(), zoneId)

    override fun today(): LocalDate = nowLocal().toLocalDate()

    override fun zone(): ZoneId = zoneId

    // ========== Test Control Methods ==========

    /**
     * Set the current time to a specific instant
     */
    fun setNow(instant: Instant) {
        currentMillis.set(instant.toEpochMilli())
    }

    /**
     * Set the current time to a specific millis value
     */
    fun setNowMillis(millis: Long) {
        currentMillis.set(millis)
    }

    /**
     * Set the current time to a specific local date-time
     */
    fun setNow(localDateTime: LocalDateTime) {
        val instant = localDateTime.atZone(zoneId).toInstant()
        currentMillis.set(instant.toEpochMilli())
    }

    /**
     * Set the current time to a specific date at midnight
     */
    fun setDate(localDate: LocalDate) {
        val instant = localDate.atStartOfDay(zoneId).toInstant()
        currentMillis.set(instant.toEpochMilli())
    }

    /**
     * Advance time by a specific amount
     */
    fun advanceBy(amount: Long, unit: ChronoUnit) {
        val newInstant = nowInstant().plus(amount, unit)
        currentMillis.set(newInstant.toEpochMilli())
    }

    /**
     * Advance time by milliseconds
     */
    fun advanceByMillis(millis: Long) {
        currentMillis.addAndGet(millis)
    }

    /**
     * Advance time by days (common for decay testing)
     */
    fun advanceByDays(days: Int) {
        advanceBy(days.toLong(), ChronoUnit.DAYS)
    }

    /**
     * Advance time by hours
     */
    fun advanceByHours(hours: Int) {
        advanceBy(hours.toLong(), ChronoUnit.HOURS)
    }

    /**
     * Reset to current system time
     */
    fun resetToSystemTime() {
        currentMillis.set(System.currentTimeMillis())
    }
}

// ========== Extension Functions ==========

/**
 * Calculate days between two timestamps
 * Used by SmartDecay for recency calculations
 */
fun Clock.daysBetween(pastMillis: Long, nowMillis: Long = this.nowMillis()): Int {
    val dayMs = 86_400_000L
    return (((nowMillis - pastMillis).coerceAtLeast(0)) / dayMs).toInt()
}

/**
 * Calculate days since a timestamp
 */
fun Clock.daysSince(pastMillis: Long): Int = daysBetween(pastMillis)

/**
 * Check if a timestamp is within the last N days
 */
fun Clock.isWithinDays(pastMillis: Long, days: Int): Boolean {
    return daysSince(pastMillis) <= days
}

/**
 * Get the start of today (midnight)
 */
fun Clock.startOfToday(): Long {
    return today().atStartOfDay(zone()).toInstant().toEpochMilli()
}

/**
 * Get the start of this week (Monday midnight)
 */
fun Clock.startOfWeek(): Long {
    val today = today()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return monday.atStartOfDay(zone()).toInstant().toEpochMilli()
}

/**
 * Get the end of this week (Sunday 23:59:59)
 */
fun Clock.endOfWeek(): Long {
    val today = today()
    val sunday = today.plusDays((7 - today.dayOfWeek.value).toLong())
    return sunday.atTime(LocalTime.MAX).atZone(zone()).toInstant().toEpochMilli()
}

/**
 * Format timestamp for display
 */
fun Clock.formatTimestamp(
    millis: Long,
    pattern: String = "yyyy-MM-dd HH:mm"
): String {
    val instant = Instant.ofEpochMilli(millis)
    val dateTime = LocalDateTime.ofInstant(instant, zone())
    return dateTime.format(DateTimeFormatter.ofPattern(pattern))
}

/**
 * Get relative time description (e.g., "2 days ago")
 */
fun Clock.relativeTime(millis: Long): String {
    val days = daysSince(millis)
    return when {
        days == 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}
