package io.github.semiflux.limit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.semiflux.limit.blocking.EnoughApp
import io.github.semiflux.limit.blocking.EnoughMonitorService
import io.github.semiflux.limit.data.AppRule
import io.github.semiflux.limit.data.BlockWindow
import io.github.semiflux.limit.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun appOf(application: Application): EnoughApp =
    application as EnoughApp

data class HomeRow(
    val rule: AppRule,
    val label: String,
    val usedMillis: Long
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = appOf(application)

    val rules: StateFlow<List<AppRule>> =
        app.limits.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val usage: StateFlow<Map<String, Long>> = _usage

    val rows: StateFlow<List<HomeRow>> =
        combine(rules, usage) { rules, usage ->
            rules.map { rule ->
                // labelFor hits PackageManager: keep this whole transform
                // off the main thread or every usage tick (~30s) and every
                // rules emission drops frames, including mid-transition
                // when opening the detail screen.
                HomeRow(rule, app.apps.labelFor(rule.packageName), usage[rule.packageName] ?: 0L)
            }.sortedBy { it.label.lowercase() }
        }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _usage.value = runCatching { app.usage.dailyUsage() }.getOrDefault(emptyMap())
                delay(30_000)
            }
        }
    }

    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _usage.value = runCatching { app.usage.dailyUsage() }.getOrDefault(emptyMap())
        }
    }

    fun toggleEnabled(rule: AppRule) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.upsert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun removeRule(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.remove(packageName)
        }
    }

    fun restoreRule(rule: AppRule) {
        app.bumpListEpoch()
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.upsert(rule)
        }
    }

    fun ensureMonitor() {
        runCatching { EnoughMonitorService.start(getApplication()) }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(application) as T
    }
}

class DetailViewModel(
    application: Application,
    val packageName: String
) : AndroidViewModel(application) {

    private val app = appOf(application)

    val rule: StateFlow<AppRule?> =
        app.limits.rules.map { list -> list.firstOrNull { it.packageName == packageName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _usage = MutableStateFlow(0L)
    val usedMillis: StateFlow<Long> = _usage

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _usage.value = runCatching { app.usage.usageFor(packageName) }.getOrDefault(0L)
                delay(15_000)
            }
        }
    }

    fun refreshUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _usage.value = runCatching { app.usage.usageFor(packageName) }.getOrDefault(0L)
        }
    }

    fun setDailyLimit(minutes: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (minutes == null) {
                app.limits.update(packageName) { it.copy(dailyLimitMinutes = null) }
            } else {
                app.limits.update(packageName) { it.copy(dailyLimitMinutes = minutes.coerceIn(1, 1440)) }
            }
        }
    }

    fun setDailyLimitEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { rule ->
                if (rule.dailyLimitMinutes == null && enabled) {
                    rule.copy(dailyLimitMinutes = 60, dailyLimitEnabled = true)
                } else {
                    rule.copy(dailyLimitEnabled = enabled)
                }
            }
        }
    }

    /** At least one quota day always stays selected. */
    fun toggleLimitDay(day: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { rule ->
                val days = if (day in rule.limitDays) rule.limitDays - day else rule.limitDays + day
                rule.copy(limitDays = days.ifEmpty { rule.limitDays })
            }
        }
    }

    fun addWindow(window: BlockWindow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { it.copy(windows = it.windows + window) }
        }
    }

    fun updateWindow(window: BlockWindow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { rule ->
                rule.copy(windows = rule.windows.map { w -> if (w.id == window.id) window else w })
            }
        }
    }

    fun removeWindow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { it.copy(windows = it.windows.filterNot { w -> w.id == id }) }
        }
    }

    fun setWindowEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { rule ->
                rule.copy(windows = rule.windows.map { w ->
                    if (w.id != id) w else w.copy(enabled = enabled)
                })
            }
        }
    }

    /** At least one window day always stays selected. */
    fun toggleWindowDay(windowId: String, day: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.update(packageName) { rule ->
                rule.copy(windows = rule.windows.map { w ->
                    if (w.id != windowId) w else {
                        val days = if (day in w.days) w.days - day else w.days + day
                        w.copy(days = days.ifEmpty { w.days })
                    }
                })
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.remove(packageName)
            launch(Dispatchers.Main) { onDone() }
        }
    }

    fun label(): String = app.apps.labelFor(packageName)

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val application: Application,
        private val packageName: String
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(application, packageName) as T
    }
}

class PickerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = appOf(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _existing = MutableStateFlow<Set<String>>(emptySet())

    val visible: StateFlow<List<InstalledApp>> =
        combine(_apps, _query, _existing) { apps, query, existing ->
            apps.filter { it.packageName !in existing }
                .filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            app.limits.rules.collect { rules ->
                _existing.value = rules.map { it.packageName }.toSet()
            }
        }
        reload()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _apps.value = runCatching { app.apps.load() }.getOrDefault(emptyList())
            _loading.value = false
        }
    }

    fun add(packageName: String, onAdded: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            app.bumpListEpoch()
            app.limits.upsert(AppRule(packageName = packageName))
            runCatching { EnoughMonitorService.start(getApplication()) }
            launch(Dispatchers.Main) { onAdded() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PickerViewModel(application) as T
    }
}
