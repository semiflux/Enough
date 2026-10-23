package io.github.semiflux.enough.ui

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.semiflux.enough.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val LOOPS = 500

/**
 * Two-column duration wheel (hours 0–23, minutes 0–59), same interaction
 * shape as the system timer pickers: fling to scroll, selection settles
 * in the middle row. Endless in both directions.
 *
 * Mechanics: one spacer row top and bottom plus snap-to-center, so the
 * settled centered row is always the selection, with one neighbor visible
 * above and below.
 */
@Composable
fun DurationWheel(
    hours: Int,
    minutes: Int,
    onChange: (hours: Int, minutes: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Single funnel for both columns: each settle updates its own half here
    // on the UI thread and reports the pair. This way a settle can never
    // pair its fresh value with a stale capture of the sibling column
    // (the previous design closed over the sibling prop, so two
    // near-simultaneous settles could commit a crossed pair and the last
    // write won — looking exactly like a "reset").
    var hourSel by remember { mutableIntStateOf(hours.coerceIn(0, 23)) }
    var minSel by remember { mutableIntStateOf(minutes.coerceIn(0, 59)) }

    LaunchedEffect(hours) { hourSel = hours.coerceIn(0, 23) }
    LaunchedEffect(minutes) { minSel = minutes.coerceIn(0, 59) }

    fun report(h: Int, m: Int) {
        if (h != hours || m != minutes) onChange(h, m)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WheelColumn(
            value = hourSel,
            size = 24,
            label = stringResource(R.string.unit_hours),
            onChange = { hourSel = it; report(it, minSel) },
            modifier = Modifier.width(110.dp)
        )
        WheelColumn(
            value = minSel,
            size = 60,
            step = 5,
            label = stringResource(R.string.unit_minutes),
            onChange = { minSel = it; report(hourSel, it) },
            modifier = Modifier.width(110.dp)
        )
    }
}

@Composable
private fun WheelColumn(
    value: Int,
    size: Int,
    label: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1
) {
    val itemHeight = 44.dp
    // Item 0 = top spacer, then repeating values, last = bottom spacer.
    // Snap-to-center keeps one neighbor visible above and below.
    val slotCount = size / step
    val slots = slotCount * LOOPS
    val startItem = remember(size, step, value) {
        val middle = slots / 2 - (slots / 2 % slotCount)
        middle + (value / step).coerceIn(0, slotCount - 1)
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startItem)

    // With center snap, the settled selection is the centered row, i.e. the
    // first visible row (the row above is fully visible, the one below too).
    fun slotOf(firstVisible: Int) = (((firstVisible % slotCount) + slotCount) % slotCount)
    fun valueOf(slot: Int) = slot * step

    // Report the settled selection (ignore pass-through rows while flinging).
    LaunchedEffect(state, size, step) {
        snapshotFlow { state.firstVisibleItemIndex to state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (first, scrolling) ->
                if (!scrolling) onChange(valueOf(slotOf(first)))
            }
    }

    // Jump the wheel when the value changes from outside.
    LaunchedEffect(value, size, step) {
        if (valueOf(slotOf(state.firstVisibleItemIndex)) != value && !state.isScrollInProgress) {
            val middle = slots / 2 - (slots / 2 % slotCount)
            state.scrollToItem(middle + (value / step).coerceIn(0, slotCount - 1))
        }
    }

    // Styling follows the centermost row live — including mid-scroll — so
    // the highlight travels smoothly instead of jumping at settle time.
    val infos = state.layoutInfo.visibleItemsInfo
    val centerPx =
        (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    val selectedSlot = infos
        .filter { (it.key as? String)?.startsWith("v") == true }
        .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - centerPx) }
        ?.index
        ?.let { (((it - 1) % slotCount) + slotCount) % slotCount }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.height(itemHeight * 3)
        ) {
            item(key = "top") {
                Box(Modifier.height(itemHeight).fillMaxWidth())
            }
            items(
                count = slots,
                key = { "v$it" }
            ) { index ->
                val slot = index % slotCount
                val selected = selectedSlot != null && slot == selectedSlot
                Box(
                    modifier = Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(slot * step),
                        style = if (selected) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(if (selected) 1f else 0.55f)
                    )
                }
            }
            item(key = "bottom") {
                Box(Modifier.height(itemHeight).fillMaxWidth())
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
