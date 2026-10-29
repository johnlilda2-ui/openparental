package app.monii.managed.enforcement

import android.content.Context
import app.monii.managed.data.PolicyStore
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalTime

/**
 * Typed view over rules supported by the Android enforcement engine:
 *   { blockedApps: [pkg], appLimits: [{pkg, dailyMinutes}],
 *     schedules: [{start:"22:00", end:"06:30", blockAll:true}] }
 *
 * Validation is strict at command application time. A malformed rule must not
 * be silently skipped and reported as if enforcement had been applied.
 */
class PolicyRules(
    val blockedApps: Set<String>,
    val appLimits: Map<String, Int>,
    val schedules: List<Schedule>,
) {
    data class Schedule(val start: LocalTime, val end: LocalTime, val blockAll: Boolean)

    fun isWithinBlockingSchedule(now: LocalTime = LocalTime.now()): Boolean =
        schedules.any { it.blockAll && inWindow(now, it.start, it.end) }

    private fun inWindow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start <= end) now >= start && now < end
        else now >= start || now < end

    companion object {
        private val supportedFields = setOf("blockedApps", "appLimits", "schedules")
        private val packageNamePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

        fun load(context: Context): PolicyRules? {
            val json = PolicyStore.loadJson(context) ?: return null
            return runCatching { parse(json) }.getOrNull()
        }

        /** Validate an untrusted remote policy before saving it. */
        fun validate(rules: Map<String, Any?>): PolicyRules = parse(Gson().toJson(rules))

        private fun parse(json: String): PolicyRules {
            val obj = Gson().fromJson(json, JsonObject::class.java)
                ?: throw IllegalArgumentException("Policy rules must be a JSON object")
            val unknown = obj.keySet() - supportedFields
            require(unknown.isEmpty()) {
                "Unsupported policy fields: ${unknown.sorted().joinToString(", ")}"
            }

            val blockedElement = obj.get("blockedApps")
            val blocked = stringArray(blockedElement, "blockedApps")
                .onEach(::validatePackageName)
                .toSet()

            val limits = mutableMapOf<String, Int>()
            val limitsElement = obj.get("appLimits")
            if (limitsElement != null && !limitsElement.isJsonNull) {
                require(limitsElement.isJsonArray) { "appLimits must be an array" }
                limitsElement.asJsonArray.forEachIndexed { index, element ->
                    require(element.isJsonObject) { "appLimits[$index] must be an object" }
                    val rule = element.asJsonObject
                    val pkg = requiredString(rule, "pkg", "appLimits[$index]")
                    validatePackageName(pkg)
                    val minutes = requiredInt(rule, "dailyMinutes", "appLimits[$index]")
                    require(minutes in 0..1440) {
                        "appLimits[$index].dailyMinutes must be between 0 and 1440"
                    }
                    limits[pkg] = minutes
                }
            }

            val schedules = mutableListOf<Schedule>()
            val schedulesElement = obj.get("schedules")
            if (schedulesElement != null && !schedulesElement.isJsonNull) {
                require(schedulesElement.isJsonArray) { "schedules must be an array" }
                schedulesElement.asJsonArray.forEachIndexed { index, element ->
                    require(element.isJsonObject) { "schedules[$index] must be an object" }
                    val rule = element.asJsonObject
                    val startRaw = requiredString(rule, "start", "schedules[$index]")
                    val endRaw = requiredString(rule, "end", "schedules[$index]")
                    val blockAll = rule.get("blockAll")
                    require(blockAll != null && blockAll.isJsonPrimitive && blockAll.asJsonPrimitive.isBoolean) {
                        "schedules[$index].blockAll must be a boolean"
                    }
                    val start = runCatching { LocalTime.parse(startRaw) }.getOrElse {
                        throw IllegalArgumentException("schedules[$index].start must use HH:mm format")
                    }
                    val end = runCatching { LocalTime.parse(endRaw) }.getOrElse {
                        throw IllegalArgumentException("schedules[$index].end must use HH:mm format")
                    }
                    schedules.add(Schedule(start, end, blockAll.asBoolean))
                }
            }

            return PolicyRules(blocked, limits, schedules)
        }

        private fun stringArray(element: JsonElement?, name: String): List<String> {
            if (element == null || element.isJsonNull) return emptyList()
            require(element.isJsonArray) { "$name must be an array" }
            return element.asJsonArray.mapIndexed { index, value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "$name[$index] must be a string"
                }
                value.asString
            }
        }

        private fun requiredString(obj: JsonObject, field: String, prefix: String): String {
            val value = obj.get(field)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "$prefix.$field must be a string"
            }
            val text = value.asString.trim()
            require(text.isNotEmpty()) { "$prefix.$field must not be empty" }
            return text
        }

        private fun requiredInt(obj: JsonObject, field: String, prefix: String): Int {
            val value = obj.get(field)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                "$prefix.$field must be an integer"
            }
            val numeric = runCatching { value.asString.toDouble() }.getOrNull()
                ?: throw IllegalArgumentException("$prefix.$field must be an integer")
            require(numeric.isFinite() && numeric % 1.0 == 0.0 &&
                numeric >= Int.MIN_VALUE && numeric <= Int.MAX_VALUE) {
                "$prefix.$field must be an integer"
            }
            return numeric.toInt()
        }

        private fun validatePackageName(pkg: String) {
            require(packageNamePattern.matches(pkg)) {
                "Invalid Android package name: $pkg"
            }
        }
    }
}
