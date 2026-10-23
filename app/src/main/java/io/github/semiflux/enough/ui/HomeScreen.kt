package io.github.semiflux.enough.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.semiflux.enough.R
import io.github.semiflux.enough.blocking.BlockEvaluator
import io.github.semiflux.enough.blocking.BlockReason
import io.github.semiflux.enough.blocking.Blocker
import io.github.semiflux.enough.blocking.EnoughApp
import io.github.semiflux.enough.blocking.Permissions
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAdd: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val limitApp = context.applicationContext as? EnoughApp
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val listEpoch by remember(limitApp) {
        limitApp?.listEpoch ?: MutableStateFlow(0)
    }.collectAsStateWithLifecycle()
    var permissionTick by remember { mutableIntStateOf(0) }

    // Re-check system permissions every time we return to the screen
    // (e.g. back from Settings).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshUsage()
        viewModel.ensureMonitor()
    }

    // Read fresh whenever the screen resumes; permissionTick forces recompute.
    val hasUsage = remember(permissionTick) { Permissions.hasUsageAccess(context) }
    val accessibilityOn = remember(permissionTick) { Permissions.isAccessibilityEnabled(context) }
    val ignoringBattery = remember(permissionTick) {
        Permissions.isIgnoringBatteryOptimizations(context)
    }
    val canFullScreen = remember(permissionTick) { Blocker.canFullScreen(context) }
    val hasOverlay = remember(permissionTick) { Permissions.hasOverlay(context) }
    val hasNotifications = remember(permissionTick) {
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionTick++ }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.action_add_limit)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onAdd
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (!hasUsage) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_usage_title),
                        text = stringResource(R.string.perm_usage_text),
                        action = stringResource(R.string.action_allow),
                        onAction = {
                            context.startActivity(Permissions.usageAccessIntent())
                        }
                    )
                }
            }
            if (!hasNotifications && Build.VERSION.SDK_INT >= 33) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_notif_title),
                        text = stringResource(R.string.perm_notif_text),
                        action = stringResource(R.string.action_allow),
                        onAction = {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }
            if (hasUsage && !accessibilityOn) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_accessibility_title),
                        text = stringResource(R.string.perm_accessibility_text),
                        action = stringResource(R.string.action_open_settings),
                        onAction = {
                            context.startActivity(Permissions.accessibilityIntent())
                        }
                    )
                }
            }
            if (hasUsage && !ignoringBattery) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_battery_title),
                        text = stringResource(R.string.perm_battery_text),
                        action = stringResource(R.string.action_open_settings),
                        onAction = {
                            context.startActivity(Permissions.batterySettingsIntent())
                        }
                    )
                }
            }
            if (hasUsage && hasNotifications && !canFullScreen) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_fullscreen_title),
                        text = stringResource(R.string.perm_fullscreen_text),
                        action = stringResource(R.string.action_open_settings),
                        onAction = {
                            runCatching {
                                context.startActivity(Blocker.fullScreenSettingsIntent())
                            }
                        }
                    )
                }
            }
            if (hasUsage && !hasOverlay) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.perm_overlay_title),
                        text = stringResource(R.string.perm_overlay_text),
                        action = stringResource(R.string.action_allow),
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Permissions.overlayIntent(context.packageName)
                                )
                            }
                        }
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    EmptyState(hasUsage = hasUsage)
                }
            }

            items(rows, key = { it.rule.packageName }) { row ->
                // The tick changes whenever a rule returns to the list, so a
                // restored row composes in a virgin scope. Without this, the
                // returning row can reuse the removed row's dismiss state
                // (same list key): it comes back already-dismissed and
                // onDismiss fires again, deleting the just-restored rule
                // a second time.
                key(row.rule.packageName, listEpoch) {
                val reason = BlockEvaluator.evaluate(row.rule, row.usedMillis, ZonedDateTime.now())
                val removedMessage = stringResource(R.string.snackbar_limit_removed, row.label)
                val undoLabel = stringResource(R.string.action_undo)
                val dismissState = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    onDismiss = {
                        viewModel.removeRule(row.rule.packageName)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = removedMessage,
                                actionLabel = undoLabel
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restoreRule(row.rule)
                            }
                        }
                    },
                    backgroundContent = {
                        val deleting =
                            dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (deleting) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = if (deleting) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        }
                    }
                ) {
                    HomeRowItem(
                        label = row.label,
                        icon = { AsyncAppIcon(row.rule.packageName) },
                        rule = row.rule,
                        usedMillis = row.usedMillis,
                        reason = reason,
                        onOpen = { onOpenDetail(row.rule.packageName) },
                        onToggle = { viewModel.toggleEnabled(row.rule) }
                    )
                }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun PermissionCard(title: String, text: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState(hasUsage: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasUsage) stringResource(R.string.empty_body)
            else stringResource(R.string.empty_body_usage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusLine(
    icon: ImageVector,
    text: String,
    alert: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (alert) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(text)
    }
}

@Composable
private fun HomeRowItem(
    label: String,
    icon: @Composable () -> Unit,
    rule: io.github.semiflux.enough.data.AppRule,
    usedMillis: Long,
    reason: BlockReason?,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val limit = rule.dailyLimitMinutes
    val quotaToday = BlockEvaluator.isActiveDay(
        rule.limitDays, java.time.ZonedDateTime.now().dayOfWeek
    )
    val quotaOn = rule.dailyLimitEnabled
    val progress = if (limit != null && limit > 0 && quotaToday && quotaOn) {
        (usedMillis.toFloat() / (limit * 60_000f)).coerceIn(0f, 1f)
    } else null

    val quotaLine: Pair<String, Boolean> = when {
        reason is BlockReason.Quota -> stringResource(R.string.status_limit_reached) to true
        limit != null && quotaToday && quotaOn -> {
            val left = BlockEvaluator.remainingMillis(usedMillis, limit) ?: 0L
            stringResource(
                R.string.status_remaining_of,
                durationLabel(left),
                durationLabel(limit * 60_000L)
            ) to false
        }
        // No active quota (off, off-day, or never set): usage against no cap.
        else -> stringResource(
            R.string.status_used_unlimited, durationLabel(usedMillis)
        ) to false
    }

    val scheduleLine: Pair<String, Boolean>? = when {
        reason is BlockReason.Schedule ->
            stringResource(R.string.status_blocked_window, windowLabel(reason.window)) to true
        rule.windows.any { it.enabled } -> stringResource(R.string.status_not_blocked) to false
        else -> null
    }

    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                if (!rule.enabled) {
                    StatusLine(
                        icon = Icons.Filled.PauseCircle,
                        text = stringResource(R.string.status_paused),
                        alert = true
                    )
                } else {
                    val (quotaText, quotaAlert) = quotaLine
                    StatusLine(
                        icon = Icons.Outlined.HourglassEmpty,
                        text = quotaText,
                        alert = quotaAlert
                    )
                    scheduleLine?.let { (scheduleText, scheduleAlert) ->
                        StatusLine(
                            icon = Icons.Filled.Schedule,
                            text = scheduleText,
                            alert = scheduleAlert
                        )
                    }
                }
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        leadingContent = icon,
        trailingContent = {
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        },
        modifier = Modifier.clickable(onClick = onOpen)
    )
}
