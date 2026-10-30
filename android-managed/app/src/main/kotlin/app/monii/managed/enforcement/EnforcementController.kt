package app.monii.managed.enforcement

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log

/** Evaluates foreground apps and records whether Android accepted enforcement. */
class EnforcementController(context: Context) {
    private val appContext = context.applicationContext
    private val usage = UsageTracker(appContext)

    fun onForegroundApp(pkg: String, service: AccessibilityService) {
        if (pkg == appContext.packageName) return

        val rules = PolicyRules.load(appContext)
        if (rules == null) {
            Log.w(TAG, "No readable policy; cannot evaluate foreground package=$pkg")
            return
        }
        if (!shouldBlock(pkg, rules)) return

        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (accepted) {
            Log.i(TAG, "Blocked foreground package=$pkg; Android accepted HOME action")
        } else {
            Log.e(TAG, "Blocking failed for package=$pkg; Android rejected HOME action")
        }
    }

    private fun shouldBlock(pkg: String, rules: PolicyRules): Boolean {
        if (pkg in rules.blockedApps) return true
        if (rules.isWithinBlockingSchedule()) return true
        val limit = rules.appLimits[pkg]
        if (limit != null && usage.todayMinutes(pkg) >= limit) return true
        return false
    }

    private companion object { const val TAG = "MoniiEnforcement" }
}
