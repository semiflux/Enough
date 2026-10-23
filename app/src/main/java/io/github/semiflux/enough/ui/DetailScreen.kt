package io.github.semiflux.enough.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.semiflux.enough.R
import io.github.semiflux.enough.blocking.EnoughApp
import io.github.semiflux.enough.data.AllDays
import io.github.semiflux.enough.data.BlockWindow
import io.github.semiflux.enough.ui.theme.EnoughTheme
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rule by viewModel.rule.collectAsStateWithLifecycle()
    val used by viewModel.usedMillis.collectAsStateWithLifecycle()
    var showWindowEditor by remember { mutableStateOf(false) }
    var editingWindow by remember { mutableStateOf<BlockWindow?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshUsage() }

    val app = context.applicationContext as? EnoughApp
    val label by produceState(initialValue = viewModel.packageName, viewModel.packageName) {
        value = runCatching {
            app?.apps?.labelForCached(viewModel.packageName)
        }.getOrNull() ?: viewModel.packageName
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_app_limit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_remove_limit)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                ListItem(
                    headlineContent = { Text(label, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = { Text(viewModel.packageName) },
                    leadingContent = { AsyncAppIcon(viewModel.packageName) }
                )
            }

            item {
                val current = rule
                val limit = current?.dailyLimitMinutes
                val limitOn = limit != null && (current?.dailyLimitEnabled ?: true)
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .alpha(if (limitOn || limit == null) 1f else 0.5f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.HourglassEmpty, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.label_daily_limit),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = limit != null && (current?.dailyLimitEnabled ?: true),
                                onCheckedChange = { on ->
                                    viewModel.setDailyLimitEnabled(on)
                                }
                            )
                        }
                        if (limit != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.daily_limit_summary,
                                    durationLabel(limit * 60_000L),
                                    durationLabel(used)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            DurationWheel(
                                hours = limit / 60,
                                minutes = limit % 60,
                                onChange = { h, m ->
                                    viewModel.setDailyLimit((h * 60 + m).coerceAtLeast(1))
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            DayChipsRow(
                                selected = current?.limitDays ?: AllDays,
                                onToggle = { viewModel.toggleLimitDay(it) }
                            )
                        } else {
                            Text(
                                stringResource(R.string.daily_limit_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }
            }

            item {
                Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Schedule, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.label_blocked_times),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.blocked_times_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        val windows = rule?.windows.orEmpty()
                        if (windows.isEmpty()) {
                            Text(
                                stringResource(
                                    R.string.blocked_times_none,
                                    windowLabel(BlockWindow(startMinute = 9 * 60, endMinute = 17 * 60))
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        windows.forEach { window ->
                            WindowRow(
                                window = window,
                                onToggleDay = { day -> viewModel.toggleWindowDay(window.id, day) },
                                onEdit = {
                                    editingWindow = window
                                    showWindowEditor = true
                                },
                                onToggleEnabled = { viewModel.setWindowEnabled(window.id, it) },
                                onDelete = { viewModel.removeWindow(window.id) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            editingWindow = null
                            showWindowEditor = true
                        }) {
                            Text(stringResource(R.string.action_add_blocked_time))
                        }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showWindowEditor) {
        val editing = editingWindow
        key(editing?.id) {
            WindowEditorDialog(
                initial = editing,
                onDismiss = {
                    showWindowEditor = false
                    editingWindow = null
                },
                onSave = { start, end ->
                    if (editing != null) {
                        viewModel.updateWindow(editing.copy(startMinute = start, endMinute = end))
                    } else {
                        viewModel.addWindow(BlockWindow(startMinute = start, endMinute = end))
                    }
                    showWindowEditor = false
                    editingWindow = null
                }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.confirm_remove_title)) },
            text = { Text(stringResource(R.string.confirm_remove_text, label)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(onBack) }) {
                    Text(stringResource(R.string.action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** Single-letter day toggle; stock FilterChip colors. */
@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        modifier = modifier
    )
}

/** All seven days, strictly in a single row, named by the current locale. */
@Composable
private fun DayChipsRow(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        (1..7).forEach { value ->
            DayChip(
                label = DayOfWeek.of(value).getDisplayName(TextStyle.NARROW, locale),
                selected = value in selected,
                onToggle = { onToggle(value) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WindowRow(
    window: BlockWindow,
    onToggleDay: (Int) -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .alpha(if (window.enabled) 1f else 0.5f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                windowLabel(window),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = window.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_edit_blocked_time)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_remove_blocked_time)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        DayChipsRow(selected = window.days, onToggle = onToggleDay)
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowEditorDialog(
    initial: BlockWindow?,
    onDismiss: () -> Unit,
    onSave: (startMinute: Int, endMinute: Int) -> Unit
) {
    val startState = rememberTimePickerState(
        initialHour = (initial?.startMinute ?: 9 * 60) / 60,
        initialMinute = (initial?.startMinute ?: 0) % 60
    )
    val endState = rememberTimePickerState(
        initialHour = (initial?.endMinute ?: 17 * 60) / 60,
        initialMinute = (initial?.endMinute ?: 0) % 60
    )
    var step by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (step == 0) stringResource(R.string.window_block_from)
                else stringResource(R.string.window_block_until)
            )
        },
        text = {
            Column {
                if (step == 0) TimePicker(state = startState)
                else TimePicker(state = endState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step == 0) step++
                    else {
                        val start = startState.hour * 60 + startState.minute
                        val end = endState.hour * 60 + endState.minute
                        if (end != start) onSave(start, end)
                    }
                }
            ) {
                Text(
                    if (step == 0) stringResource(R.string.action_next)
                    else stringResource(R.string.action_save)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 0) onDismiss() else step-- }) {
                Text(stringResource(R.string.action_back))
            }
        }
    )
}
