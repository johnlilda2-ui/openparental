package app.monii.managed.collectors

import android.content.Context
import app.monii.managed.enforcement.PolicyRules
import app.monii.managed.enforcement.UsageTracker
import app.monii.managed.repo.MoniiRepository

/**
 * Periodic visibility collector. Location is intentionally excluded here:
 * coordinates are reported only for an explicit parent-requested location
 * command, matching the child-visible permission explanation and minimizing data.
 * Usage summaries are throttled to at most one event every 15 minutes.
 */
class VisibilityCollector(context: Context) {
    private val appContext = context.applicationContext
    private val usage = UsageTracker(appContext)
    private val prefs =
        appContext.getSharedPreferences("monii_collector", Context.MODE_PRIVATE)

    suspend fun collect(repo: MoniiRepository) {
        if (!summaryDue()) return

        val rules = PolicyRules.load(appContext)
        val watched = rules?.appLimits?.keys ?: emptySet()
        if (watched.isNotEmpty()) {
            val summary = watched.associateWith { usage.todayMinutes(it) }
            repo.bufferEvent("USAGE_SUMMARY", mapOf("minutesByApp" to summary))
        }
        prefs.edit().putLong("last_summary", System.currentTimeMillis()).apply()
    }

    private fun summaryDue(): Boolean {
        val last = prefs.getLong("last_summary", 0L)
        return System.currentTimeMillis() - last > 15 * 60 * 1000L
    }
}
