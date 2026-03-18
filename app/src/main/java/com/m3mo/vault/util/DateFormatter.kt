package com.m3mo.vault.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * DateFormatter - Human-friendly time formatting for UI.
 */
object DateFormatter {

    /** "2 hours ago", "3 days ago", "Jan 15" */
    fun relativeTime(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestampMs

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1) -> {
                val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
                "${mins}m ago"
            }
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(diff)
                "${hours}h ago"
            }
            diff < TimeUnit.DAYS.toMillis(7) -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                "${days}d ago"
            }
            else -> shortDate(timestampMs)
        }
    }

    /** "Jan 15" */
    fun shortDate(timestampMs: Long): String {
        return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }

    /** "Jan 15, 2026" */
    fun fullDate(timestampMs: Long): String {
        return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
    }

    /** "Jan 15, 2:30 PM" */
    fun dateTime(timestampMs: Long): String {
        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestampMs))
    }
}
