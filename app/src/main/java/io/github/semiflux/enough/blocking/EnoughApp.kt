package io.github.semiflux.enough.blocking

import android.app.Application
import android.content.Context
import io.github.semiflux.enough.data.AppRule
import io.github.semiflux.enough.data.InstalledAppsRepository
import io.github.semiflux.enough.data.EnoughRepository
import io.github.semiflux.enough.data.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EnoughApp : Application() {

    // Lazy: the DataStore delegate inside EnoughRepository touches the base
    // context, which is still null while Application field initializers run
    // (that NPE'd on startup). First access happens after attach.
    val limits: EnoughRepository by lazy { EnoughRepository(this) }
    val usage: UsageRepository by lazy { UsageRepository(this) }
    val apps: InstalledAppsRepository by lazy { InstalledAppsRepository(this) }

    /**
     * Bumped every time a rule returns to the home list (undo or re-add).
     * The home list keys each row by (package, epoch) so a returning row
     * always composes in a virgin scope — otherwise it can reuse the
     * removed row's dismiss state and delete itself again.
     */
    private val _listEpoch = MutableStateFlow(0)
    val listEpoch: StateFlow<Int> = _listEpoch

    fun bumpListEpoch() {
        _listEpoch.value += 1
    }

    @Volatile
    private var cachedRules: List<AppRule> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            limits.rules.collectLatest { rules ->
                cachedRules = rules
            }
        }
    }

    /** Synchronous snapshot for services that cannot collect flows. */
    fun rulesSnapshot(): List<AppRule> = cachedRules
}

/** Services (accessibility, monitor) reach the app container through here. */
object EnoughServiceLocator {
    fun appOrNull(context: Context): EnoughApp? =
        context.applicationContext as? EnoughApp
}
