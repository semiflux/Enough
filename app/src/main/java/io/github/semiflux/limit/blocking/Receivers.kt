package io.github.semiflux.limit.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts enforcement after reboot / update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EnoughMonitorService::class.java)
            )
        }
    }
}
