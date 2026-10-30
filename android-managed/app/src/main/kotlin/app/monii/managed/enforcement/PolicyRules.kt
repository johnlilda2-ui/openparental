package app.monii.managed.enforcement

import android.content.Context
import android.util.Log
import app.monii.managed.data.PolicyStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.time.LocalTime

/** Typed view over the active policy rules stored by SET_POLICY. */
class PolicyRules(
    val blockedApps: Set<String>,
    val appLimits: Map<String, Int>,
    val schedules: List<Schedule>,
) {
    data class Schedule(val start: LocalTime, val end: LocalTime, val blockAll: Boolean)

    fun isWithinBlockingSchedule(now: LocalTime = LocalTime.now()): Boolean =
        schedules.any { it.blockAll && inWindow(now, it.start, it.end) }

    private fun inWindow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start <= end) now >= start && now < end else now >= start || now < end

    companion object {
        private const val TAG = "MoniiPolicy"

        fun load(context: Context): PolicyRules? {
            val json = PolicyStore.loadJson(context) ?: return null
            return try {
                parse(json).also { rules ->
                    Log.i(
                        TAG,
                        "Loaded policy: blockedApps=${rules.blockedApps.size}, appLimits=${rules.appLimits.size}, schedules=${rules.schedules.size}",
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Stored policy could not be parsed; enforcement skipped", error)
                null
            }
        }

        internal fun parse(json: String): PolicyRules {
            val obj = Gson().fromJson(json, JsonObject::class.java)
                ?: throw IllegalArgumentException("Policy JSON is empty")
            val blocked = obj.getAsJsonArray("blockedApps")
                ?.mapNotNull { element ->
                    runCatching { element.asString.trim() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                }?.toSet() ?: emptySet()

            val limits = mutableMapOf<String, Int>()
            obj.getAsJsonArray("appLimits")?.forEach { element ->
                val rule = element.asJsonObject
                val pkg = rule.get("pkg")?.asString?.trim()
                val minutes = rule.get("dailyMinutes")?.asInt
                if (!pkg.isNullOrEmpty() && minutes != null && minutes >= 0) {
                    limits[pkg] = minutes
                }
            }

            val schedules = mutableListOf<Schedule>()
            obj.getAsJsonArray("schedules")?.forEach { element ->
                val rule = element.asJsonObject
                val start = rule.get("start")?.asString
                val end = rule.get("end")?.asString
                val blockAll = rule.get("blockAll")?.asBoolean ?: false
                if (start != null && end != null) {
                    runCatching {
                        schedules.add(Schedule(LocalTime.parse(start), LocalTime.parse(end), blockAll))
                    }.onFailure { Log.w(TAG, "Ignoring invalid schedule entry", it) }
                }
            }
            return PolicyRules(blocked, limits, schedules)
        }
    }
}
