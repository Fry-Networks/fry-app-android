package com.frynetworks.fryapp.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L

/** Pure time helpers for the miner screens (no Android dependencies, unit-tested). */
object TimeFormat {

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.US)

    /** ISO-8601 (`2026-09-11T00:05:00.000Z`) or epoch-millis digit string -> epoch millis; null when unparseable. */
    fun parseMillis(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toLongOrNull()
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }

    /** `3d 4h`, `2h 5m`, `4m 20s`, `45s`, or `now` once elapsed. */
    fun duration(millis: Long): String {
        if (millis <= 0) return "now"
        val totalSeconds = millis / MILLIS_PER_SECOND
        val days = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY)
        val hours = (totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)) % HOURS_PER_DAY
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** `just now`, `5m ago`, `3h ago`, `2d ago`, or the date for anything older than a week. */
    fun relative(thenMillis: Long, nowMillis: Long): String {
        val elapsed = nowMillis - thenMillis
        if (elapsed < MILLIS_PER_SECOND * SECONDS_PER_MINUTE) return "just now"
        val minutes = elapsed / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE)
        if (minutes < MINUTES_PER_HOUR) return "${minutes}m ago"
        val hours = minutes / MINUTES_PER_HOUR
        if (hours < HOURS_PER_DAY) return "${hours}h ago"
        val days = hours / HOURS_PER_DAY
        if (days < DAYS_PER_WEEK) return "${days}d ago"
        return date(thenMillis)
    }

    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Formats an ISO string as a date, or returns [fallback] when it cannot be parsed. */
    fun dateOr(value: String?, fallback: String = "—"): String = parseMillis(value)?.let { date(it) } ?: fallback
}
