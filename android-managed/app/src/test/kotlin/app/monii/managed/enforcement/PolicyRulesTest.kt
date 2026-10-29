package app.monii.managed.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PolicyRulesTest {
    @Test
    fun acceptsSupportedRules() {
        val parsed = PolicyRules.validate(
            mapOf(
                "blockedApps" to listOf("com.example.app"),
                "appLimits" to listOf(mapOf("pkg" to "com.example.app", "dailyMinutes" to 60)),
                "schedules" to listOf(mapOf("start" to "22:00", "end" to "06:30", "blockAll" to true)),
            ),
        )

        assertEquals(setOf("com.example.app"), parsed.blockedApps)
        assertEquals(60, parsed.appLimits["com.example.app"])
        assertTrue(parsed.schedules.single().blockAll)
        assertEquals(22, parsed.schedules.single().start.hour)
        assertEquals(6, parsed.schedules.single().end.hour)
    }

    @Test
    fun acceptsEmptySupportedRules() {
        val parsed = PolicyRules.validate(mapOf("blockedApps" to emptyList<String>()))
        assertTrue(parsed.blockedApps.isEmpty())
        assertTrue(parsed.appLimits.isEmpty())
        assertTrue(parsed.schedules.isEmpty())
    }

    @Test
    fun rejectsUnsupportedRuleFields() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRules.validate(mapOf("screenTime" to mapOf("dailyMinutes" to 60)))
        }
    }

    @Test
    fun rejectsInvalidPackageNames() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRules.validate(mapOf("blockedApps" to listOf("not a package")))
        }
    }

    @Test
    fun rejectsAppLimitsOutsideAllowedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRules.validate(
                mapOf(
                    "appLimits" to listOf(
                        mapOf("pkg" to "com.example.app", "dailyMinutes" to 1441),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsMalformedSchedules() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRules.validate(
                mapOf(
                    "schedules" to listOf(
                        mapOf("start" to "25:70", "end" to "06:00", "blockAll" to true),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsWrongTypesRatherThanSilentlyIgnoringThem() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRules.validate(mapOf("blockedApps" to "com.example.app"))
        }
    }
}
