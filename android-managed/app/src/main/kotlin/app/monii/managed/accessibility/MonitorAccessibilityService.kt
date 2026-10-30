package app.monii.managed.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.monii.managed.enforcement.EnforcementController

/** Accessibility service used for foreground-app enforcement. */
class MonitorAccessibilityService : AccessibilityService() {
    private val enforcement by lazy { EnforcementController(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected; app enforcement is active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        enforcement.onForegroundApp(pkg, this)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.w(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    private companion object { const val TAG = "MoniiAccessibility" }
}
