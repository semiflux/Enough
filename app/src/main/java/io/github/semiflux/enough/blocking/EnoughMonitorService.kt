package io.github.semiflux.enough.blocking

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.github.semiflux.enough.MainActivity
import io.github.semiflux.enough.R
import io.github.semiflux.enough.data.AppRule
import java.time.ZonedDateTime

/**
 * Enforcer for schedules and quotas.
 *
 * Battery design:
 * - Polls on a background thread every 2s, but ONLY while the screen is on
 *   and the device is unlocked. Screen off or locked = no app usage exists,
 *   so the loop is parked (the service itself stays alive as a foreground
 *   service for instant resume).
 * - Each poll is one narrow UsageEvents query (sliding window, usually a
 *   handful of events) plus a cached "usage since midnight" map (30s TTL)
 *   only when the foreground app is actually limited. That map is
 *   integrated from raw events in 1h-chunked queries, never from the
 *   system's pre-aggregated daily buckets (see docs/usage-accounting.md).
 * - No wakelock is ever held.
 */
class EnoughMonitorService : LifecycleService() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastBlockAt = 0L
    private var lastBlockPkg: String? = null
    private var lastCheckAt = 0L
    private var lastKnownPkg: String? = null
    // Packages warned today (quota 5-min heads-up). Day-keyed so each
    // rule warns at most once daily; a restart may repeat one warning.
    private val warnedToday = mutableMapOf<String, String>()

    private val tick = object : Runnable {
        override fun run() {
            checkOnce()
            handler?.postDelayed(this, POLL_MS)
        }
    }

    /** Parks the loop when the screen goes off; resumes it on screen on. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler?.removeCallbacks(tick)
                Intent.ACTION_SCREEN_ON -> {
                    // Bounded window: unlock always produces fresh resume
                    // events, so no full-day scan is needed here either.
                    lastCheckAt = System.currentTimeMillis() - SCREEN_ON_WINDOW_MS
                    handler?.removeCallbacks(tick)
                    handler?.post(tick)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        // Diagnostic only: records whether the OS observer API the system
        // usage app enforces with exists on this device. Enforcement
        // stays on polling until a device proves otherwise (see docs).
        Log.d("Enough", "observerApiPresent=" + runCatching {
            EnoughServiceLocator.appOrNull(this)?.usage?.observerApiPresent()
        }.getOrNull())
        val t = HandlerThread("enough-monitor", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        thread = t
        handler = Handler(t.looper)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        handler?.removeCallbacks(tick)
        handler?.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    private fun checkOnce() {
        val blocked = findBlockedPackage()
        if (blocked == null) {
            Blocker.clearActive(this)
            return
        }
        // Debounce: don't relaunch while the block screen is already up.
        val now = System.currentTimeMillis()
        if (blocked == lastBlockPkg && now - lastBlockAt < DEBOUNCE_MS) return
        if (BlockActivity.isShowing(blocked)) return
        lastBlockPkg = blocked
        lastBlockAt = now
        Blocker.block(this, blocked)
    }

    /** Foreground package that is currently blocked, or null. */
    private fun findBlockedPackage(): String? {
        val app = EnoughServiceLocator.appOrNull(this) ?: return null
        if (!Permissions.hasUsageAccess(this)) return null
        val now = System.currentTimeMillis()
        // Screen off or locked: no app can be in use, and we must never
        // pop the block screen over the lock screen. Keep the window
        // sliding so resume stays cheap.
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        // First run after (re)start: one wide scan; afterwards a sliding
        // window with 1s overlap so no event is missed between ticks.
        // lastCheckAt is seeded by the screen-on receiver, so unlocks
        // never trigger a full-day scan either.
        val since = if (lastCheckAt == 0L) {
            runCatching { app.usage.dayStartMillis(now) }.getOrDefault(now - WINDOW_FALLBACK_MS)
        } else {
            lastCheckAt - OVERLAP_MS
        }
        lastCheckAt = now
        if (keyguard?.isKeyguardLocked == true) return null
        val foreground = runCatching { app.usage.foregroundPackage(since, now) }.getOrNull()
        val current = foreground ?: lastKnownPkg ?: return null
        lastKnownPkg = current
        if (current == packageName) return null
        val rule = app.rulesSnapshot().firstOrNull { it.packageName == current } ?: return null
        val used = runCatching { app.usage.usageFor(current, now) }.getOrDefault(0L)
        val reason = BlockEvaluator.evaluate(rule, used, ZonedDateTime.now())
        if (reason == null) {
            maybeWarnQuota(current, rule, used)
            return null
        }
        return current
    }

    /** 5-minute quota heads-up, at most once per rule per day. */
    private fun maybeWarnQuota(packageName: String, rule: AppRule, usedMillis: Long) {
        if (!rule.enabled || !rule.dailyLimitEnabled) return
        if (!BlockEvaluator.isActiveDay(rule.limitDays, java.time.ZonedDateTime.now().dayOfWeek)) return
        if (!BlockEvaluator.shouldWarnQuota(rule.dailyLimitMinutes, usedMillis)) return
        val today = java.time.LocalDate.now().toString()
        if (warnedToday[packageName] == today) return
        warnedToday[packageName] = today
        Reminder.warn(this, packageName)
    }

    private fun startForegroundNotification() {
        val channelId = CHANNEL_ID
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(getString(R.string.monitor_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    companion object {
        private const val POLL_MS = 2_000L
        private const val OVERLAP_MS = 1_000L
        private const val SCREEN_ON_WINDOW_MS = 60_000L
        private const val WINDOW_FALLBACK_MS = 60_000L
        private const val DEBOUNCE_MS = 3_000L
        private const val CHANNEL_ID = "enough_monitor"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, EnoughMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
