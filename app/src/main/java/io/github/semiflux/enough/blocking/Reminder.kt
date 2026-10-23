package io.github.semiflux.enough.blocking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.semiflux.enough.MainActivity
import io.github.semiflux.enough.R

/**
 * Quota heads-up ("5 minutes left"), deliberately dumber than [Blocker]:
 * plain notification, default importance so it respects Do Not Disturb,
 * no full-screen intent, auto-cancel on tap. Tap opens the app's limit.
 */
object Reminder {

    private const val CHANNEL_REMINDER = "enough_reminders"
    private const val ID_BASE = 2000

    fun warn(context: Context, packageName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                context.getString(R.string.channel_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val app = EnoughServiceLocator.appOrNull(context)
        val label = app?.apps?.labelFor(packageName) ?: packageName
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PACKAGE, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, packageName.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(label)
            .setContentText(context.getString(R.string.reminder_notif_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notifyId(packageName), notification) }
    }

    /** A stale heads-up next to the block screen is just noise. */
    fun dismiss(context: Context, packageName: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { nm.cancel(notifyId(packageName)) }
    }

    private fun notifyId(packageName: String): Int = ID_BASE + (packageName.hashCode() and 0xFFFF)
}
