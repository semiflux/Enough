package io.github.semiflux.enough.blocking

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional fast-path detector: the moment a blocked app comes to the
 * foreground, cover it with [BlockActivity]. Works even between the
 * monitor service's polling ticks. Enable in system Accessibility settings.
 */
class EnoughAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Rules are read lazily per event from the repository snapshot
        // via EnoughApp container to keep this service dependency-free.
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Never act over the lock screen (a pending full-screen intent
        // must not wake it either — see manifest flags on BlockActivity).
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return
        val app = EnoughServiceLocator.appOrNull(this) ?: return
        val rule = app.rulesSnapshot().firstOrNull { it.packageName == pkg }
        if (rule == null) {
            Blocker.clearActive(this)
            return
        }
        val used = app.usage.usageFor(pkg)
        val reason = BlockEvaluator.evaluate(rule, used, java.time.ZonedDateTime.now())
        if (reason != null && !BlockActivity.isShowing(pkg)) {
            Blocker.block(this, pkg)
        } else if (reason == null) {
            Blocker.clearActive(this)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        var instance: EnoughAccessibilityService? = null
            private set
    }
}
