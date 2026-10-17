package io.github.semiflux.enough.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Every day of the week; the explicit default everywhere days appear. */
val AllDays: Set<Int> = (1..7).toSet()

/**
 * One blocked time window, recurring on selected days.
 * Times are minutes-from-midnight in the device zone (0..1439).
 * Days use java.time.DayOfWeek values 1 (Mon)..7 (Sun); empty means
 * every day (only legacy/hand-made data — the UI always keeps at
 * least one day selected).
 * If endMinute <= startMinute the window crosses midnight.
 */
@Serializable
data class BlockWindow(
    val id: String = UUID.randomUUID().toString(),
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    val days: Set<Int> = AllDays
)

/**
 * Blacklist rule: everything is allowed except what is listed here.
 * A rule with [dailyLimitMinutes] == null has no quota, only schedules.
 * A rule with empty [windows] has no schedules, only a quota.
 * Both the quota ([limitDays]) and each window ([BlockWindow.days])
 * apply only on their selected days.
 * The quota keeps its minutes when [dailyLimitEnabled] is off, so
 * disabling is temporary and re-enabling restores the same value.
 */
@Serializable
data class AppRule(
    val packageName: String,
    val dailyLimitMinutes: Int? = 60,
    val dailyLimitEnabled: Boolean = true,
    val windows: List<BlockWindow> = emptyList(),
    val enabled: Boolean = true,
    val limitDays: Set<Int> = AllDays
)
