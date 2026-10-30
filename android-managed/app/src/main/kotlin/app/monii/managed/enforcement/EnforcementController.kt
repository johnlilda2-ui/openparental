package app.monii.managed.enforcement

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log

/** Evaluates foreground apps and records whether Android accepted enforcement. */
class EnforcementController(context: Context) {
    private val appContext = context.applicationContext
    private val usage = UsageTracker(appContext)
    private var lastForegroundPackage: String? = null

    fun onForegroundApp(pkg: String, service: AccessibilityService) {
        if (pkg == appContext.packageName) return

        // Log only foreground transitions, not every repeated accessibility event.
        if (pkg != lastForegroundPackage) {
            Log.i(TAG, "Foreground package observed=$pkg")
            lastForegroundPackage = pkg
        }

        val rules = PolicyRules.load(appContext)
        if (rules == null) {
            Log.w(TAG, "No readable policy; cannot evaluate foreground package=$pkg")
            return
        }

        val reason = blockReason(pkg, rules)
        if (reason == null) {
            Log.d(
                TAG,
                "No block match for package=$pkg; blockedApps=${rules.blockedApps.size}, appLimits=${rules.appLimits.size}",
            )
            return
        }

        Log.w(TAG, "Block rule matched package=$pkg reason=$reason; requesting HOME action")
        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (accepted) {
            // Android accepting the action is not proof that the target app stayed closed.
            Log.i(TAG, "HOME action accepted for package=$pkg reason=$reason")
        } else {
            Log.e(TAG, "HOME action rejected for package=$pkg reason=$reason")
        }
    }

    private fun blockReason(pkg: String, rules: PolicyRules): String? {
        if (pkg in rules.blockedApps) return "blocked-app-list"
        if (rules.isWithinBlockingSchedule()) return "schedule"
        val limit = rules.appLimits[pkg]
        if (limit != null) {
            val usedMinutes = usage.todayMinutes(pkg)
            if (usedMinutes >= limit) {
                return "daily-limit-used=${usedMinutes}m-limit=${limit}m"
            }
        }
        return null
    }

    private companion object { const val TAG = "MoniiEnforcement" }
}
