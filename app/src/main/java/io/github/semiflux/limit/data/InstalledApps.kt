package io.github.semiflux.limit.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

/** Launcher-visible apps, no QUERY_ALL_PACKAGES needed (uses a <queries> intent). */
class InstalledAppsRepository(private val context: Context) {

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm: PackageManager = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(main, 0)
        infos.mapNotNull { resolve ->
            val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = resolve.loadLabel(pm)?.toString() ?: pkg
            val icon = runCatching { resolve.loadIcon(pm) }.getOrNull()
            InstalledApp(pkg, label, icon)
        }.sortedBy { it.label.lowercase() }
    }

    fun labelFor(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun iconFor(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    // Cached off-main-thread variants. labelFor/iconFor hit PackageManager
    // (binder + resource inflation) and must never run inside composition —
    // a cold detail push would drop frames on them.
    private val labelCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, Drawable>()

    suspend fun labelForCached(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { labelFor(packageName) }
        labelCache[packageName] = loaded
        return loaded
    }

    suspend fun iconForCached(packageName: String): Drawable? {
        iconCache[packageName]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { iconFor(packageName) }
        if (loaded != null) iconCache[packageName] = loaded
        return loaded
    }
}
