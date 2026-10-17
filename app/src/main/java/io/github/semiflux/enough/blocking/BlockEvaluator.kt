package io.github.semiflux.enough.blocking

import io.github.semiflux.enough.data.AppRule
import io.github.semiflux.enough.data.BlockWindow
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

sealed interface BlockReason {
    data class Schedule(val window: BlockWindow) : BlockReason
    data object Quota : BlockReason
}

/**
 * Pure blocking logic, no Android dependencies (unit-testable).
 *
 * Semantics (blacklist model):
 * - Disabled rules never block.
 * - A matching [BlockWindow] is a hard block ("no usage from 9-17").
 * - [AppRule.dailyLimitMinutes] is a quota counted since midnight
 *   ("15 minutes allowed per day"). Null = no quota. It applies only
 *   when [AppRule.dailyLimitEnabled] is on; turning it off keeps the
 *   minutes so re-enabling restores them.
 * - Quotas and windows apply only on their selected days.
 * - Schedule wins over quota when both match.
 */
object BlockEvaluator {

    /** True on selected days; empty means every day (legacy data only). */
    fun isActiveDay(days: Set<Int>, day: DayOfWeek): Boolean =
        days.isEmpty() || day.value in days

    fun isInWindow(time: LocalTime, day: DayOfWeek, window: BlockWindow): Boolean {
        val minute = time.hour * 60 + time.minute
        return if (window.endMinute > window.startMinute) {
            isActiveDay(window.days, day) && minute in window.startMinute until window.endMinute
        } else {
            // Overnight, e.g. a Monday 22:00-06:00 window remains active
            // Tuesday morning because the selected day is the start day.
            when {
                minute >= window.startMinute -> isActiveDay(window.days, day)
                minute < window.endMinute -> isActiveDay(window.days, day.minus(1))
                else -> false
            }
        }
    }

    fun matchingWindow(rule: AppRule, now: ZonedDateTime): BlockWindow? =
        rule.windows.filter { it.enabled }
            .firstOrNull { isInWindow(now.toLocalTime(), now.dayOfWeek, it) }

    fun isQuotaExceeded(usedMillis: Long, dailyLimitMinutes: Int?): Boolean {
        if (dailyLimitMinutes == null) return false
        return usedMillis >= dailyLimitMinutes * 60_000L
    }

    /** Minutes of warning before a quota hits, matching iOS Screen Time. */
    const val WARN_MINUTES = 5L

    /**
     * True when a quota warning is due: limit set, above the warn floor so
     * tiny limits don't warn the instant they're opened, not yet exceeded
     * (the block screen covers that), and within the warn window.
     * Fire-once-per-day tracking lives with the caller.
     */
    fun shouldWarnQuota(dailyLimitMinutes: Int?, usedMillis: Long): Boolean {
        if (dailyLimitMinutes == null || dailyLimitMinutes <= WARN_MINUTES) return false
        val remaining = dailyLimitMinutes * 60_000L - usedMillis
        return remaining in 1..WARN_MINUTES * 60_000L
    }

    fun evaluate(
        rule: AppRule,
        usedMillis: Long,
        now: ZonedDateTime
    ): BlockReason? {
        if (!rule.enabled) return null
        matchingWindow(rule, now)?.let { return BlockReason.Schedule(it) }
        if (rule.dailyLimitEnabled &&
            isActiveDay(rule.limitDays, now.dayOfWeek) &&
            isQuotaExceeded(usedMillis, rule.dailyLimitMinutes)
        ) return BlockReason.Quota
        return null
    }

    fun remainingMillis(usedMillis: Long, dailyLimitMinutes: Int?): Long? {
        if (dailyLimitMinutes == null) return null
        return (dailyLimitMinutes * 60_000L - usedMillis).coerceAtLeast(0L)
    }

    // -- formatting helpers for the minimal UI --

    fun formatDuration(millis: Long): String {
        val totalMin = millis / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    fun formatMinuteOfDay(minute: Int): String {
        val h = (minute / 60).coerceIn(0, 23)
        val m = (minute % 60).coerceIn(0, 59)
        return "%d:%02d".format(h, m)
    }

    fun formatWindow(window: BlockWindow): String =
        "${formatMinuteOfDay(window.startMinute)}–${formatMinuteOfDay(window.endMinute)}"

    fun formatDays(days: Set<Int>): String {
        if (days.isEmpty()) return "Every day"
        val names = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
        val sorted = days.sorted()
        if (sorted == listOf(1, 2, 3, 4, 5, 6, 7)) return "Every day"
        if (sorted == listOf(1, 2, 3, 4, 5)) return "Weekdays"
        if (sorted == listOf(6, 7)) return "Weekends"
        return sorted.mapNotNull { names[it] }.joinToString(" ")
    }
}
