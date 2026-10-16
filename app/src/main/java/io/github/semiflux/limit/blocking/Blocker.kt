package io.github.semiflux.limit.blocking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Central block trigger.
 *
 * Launch mechanics, all verified in logcat on-device:
 *
 * - Direct startActivity works with a BAL token (accessibility window
 *   events carry one: BAL_ALLOW_TOKEN) or when holding the overlay
 *   permission (BAL_ALLOW_SAW_PERMISSION) — the latter is why the
 *   "display over apps" permission is required even though no overlay
 *   window is ever drawn. Without either, direct starts are BAL_BLOCK.
 * - The full-screen-intent notification is best-effort backup: the system
 *   arms it (temporary allowlist visible in dumpsys) but on current
 *   builds it does not reliably launch. Tapping it always works.
 * - Re-posting (updating) an existing notification launches nothing, and
 *   rapid re-posts trip notification cooldown. So notifications follow an
 *   episode model: one post per block episode, dismissed when it ends.
 */
object Blocker {

    private const val TAG = "Enough"
    private const val CHANNEL_BLOCK = "enough_block"
    private const val ID_BASE = 1000
    private const val REPOST_MS = 30_000L

    @Volatile
    private var activePkg: String? = null

    @Volatile
    private var activeAt = 0L

    fun block(context: Context, packageName: String) {
        // The direct start is attempted on every detection: it is cheap,
        // guarded by isShowing + the caller's debounce, and silently
        // dropped without a BAL exemption anyway. Only the notification
        // re-post is episode-gated below — gating the relaunch too would
        // hand the user a free-use window after backing out of the cover.
        if (!BlockActivity.isShowing(packageName)) {
            BlockActivity.start(context, packageName)
        }
        val now = System.currentTimeMillis()
        // Re-posting neither relaunches activities nor full-screen intents,
        // it only spams (and risks cooldown).
        if (activePkg == packageName && now - activeAt < REPOST_MS) return
        postFresh(context, packageName, now)
    }

    /** The trigger condition no longer holds — end the episode. */
    fun clearActive(context: Context) {
        val pkg = activePkg ?: return
        Log.i(TAG, "episode ended for $pkg")
        activePkg = null
        cancelQuietly(context, pkg)
    }

    /** False when the system would swallow the auto-popup (Android 14+ toggle). */
    fun canFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false)
    }

    fun fullScreenSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun postFresh(context: Context, packageName: String, now: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        Log.i(TAG, "posting block notification for $packageName canFullScreen=${canFullScreen(context)}")
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BLOCK,
                "App limits",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val app = EnoughServiceLocator.appOrNull(context)
        val label = app?.apps?.labelFor(packageName) ?: packageName
        val fullScreen = Intent(context, BlockActivity::class.java).apply {
            putExtra(BlockActivity.EXTRA_PACKAGE, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            context, packageName.hashCode(), fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCK)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(label)
            .setContentText(context.getString(io.github.semiflux.limit.R.string.block_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .build()
        cancelQuietly(context, packageName)
        runCatching {
            nm.notify(notifyId(packageName), notification)
        }
        Reminder.dismiss(context, packageName)
        activePkg = packageName
        activeAt = now
    }

    fun dismiss(context: Context, packageName: String) {
        if (activePkg == packageName) activePkg = null
        cancelQuietly(context, packageName)
    }

    private fun cancelQuietly(context: Context, packageName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { nm.cancel(notifyId(packageName)) }
    }

    private fun notifyId(packageName: String): Int = ID_BASE + (packageName.hashCode() and 0xFFFF)
}
