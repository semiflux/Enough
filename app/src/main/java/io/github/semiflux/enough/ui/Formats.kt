package io.github.semiflux.enough.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.semiflux.enough.R
import io.github.semiflux.enough.data.BlockWindow

/**
 * Renders model values as text. Everything the user reads goes through a
 * string resource so the app stays translatable.
 *
 * Clock times are a fixed 24-hour form. Formatting through DateFormat would
 * follow the device's 12/24-hour setting, but it would also change how
 * existing values render, so that stays a separate decision.
 */

/** "15m", "2h", "1h 30m". */
@Composable
fun durationLabel(millis: Long): String {
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.duration_hm, hours, minutes)
        hours > 0 -> stringResource(R.string.duration_h, hours)
        else -> stringResource(R.string.duration_m, minutes)
    }
}

/** Minute-of-day as a clock time, e.g. "9:05". */
@Composable
fun minuteOfDayLabel(minute: Int): String =
    stringResource(
        R.string.time_hm,
        (minute / 60).coerceIn(0, 23),
        (minute % 60).coerceIn(0, 59)
    )

/** "9:00–17:00". */
@Composable
fun windowLabel(window: BlockWindow): String =
    stringResource(
        R.string.window_range,
        minuteOfDayLabel(window.startMinute),
        minuteOfDayLabel(window.endMinute)
    )
