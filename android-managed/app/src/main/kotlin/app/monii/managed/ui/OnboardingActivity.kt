package app.monii.managed.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.monii.managed.R
import app.monii.managed.admin.AdminManager
import app.monii.managed.databinding.ActivityOnboardingBinding
import app.monii.managed.databinding.ItemPermissionBinding
import app.monii.managed.permissions.SpecialAccess
import app.monii.managed.vpn.VpnFilterService

/**
 * Guided special-access setup. Each step card opens the right Settings screen
 * and the list re-checks its status every time the user returns. Optional steps
 * don't block other features if the family declines them.
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Step(
        val icon: String,
        val title: String,
        val desc: String,
        val granted: () -> Boolean,
        val open: () -> Unit,
        val optional: Boolean = false,
    )

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var steps: List<Step>
    private val rows = mutableListOf<ItemPermissionBinding>()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val backgroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val admin = AdminManager(this)
        val setupSteps = mutableListOf(
            Step(
                "📍",
                "Location sharing",
                "Allow location access so a paired parent can request the child's current location. You can deny this and use other features.",
                { locationGranted() },
                { requestLocation() },
                optional = true,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupSteps.add(
                Step(
                    "🗺️",
                    "Background location for remote requests",
                    "To receive a location request while this app is in the background, set OpenParental Location permission to 'Allow all the time'. Android 11+ requires changing this in Settings.",
                    { backgroundLocationGranted() },
                    { requestBackgroundLocation() },
                    optional = true,
                ),
            )
        }
        setupSteps.addAll(
            listOf(
                Step(
                    "🔔",
                    "Notifications",
                    "Let OpenParental show its ongoing status.",
                    { notificationsGranted() },
                    { requestNotifications() },
                    optional = true,
                ),
                Step(
                    "🔋",
                    "Unrestricted battery",
                    "Stops the system from killing OpenParental in the background.",
                    { isIgnoringBattery() },
                    { requestIgnoreBattery() },
                ),
                Step(
                    "🛡️",
                    "Accessibility",
                    "Lets OpenParental enforce app and time limits.",
                    { SpecialAccess.isAccessibilityEnabled(this) },
                    { startSafe(SpecialAccess.accessibilitySettingsIntent()) },
                ),
                Step(
                    "📊",
                    "Usage access",
                    "Lets OpenParental measure screen time.",
                    { SpecialAccess.isUsageAccessGranted(this) },
                    { startSafe(SpecialAccess.usageAccessSettingsIntent()) },
                ),
                Step(
                    "🔒",
                    "Device admin",
                    "Lets OpenParental lock the device remotely.",
                    { admin.isActive() },
                    { startSafe(admin.enableIntent()) },
                ),
                Step(
                    "🌐",
                    "Content filter",
                    "Block adult & unsafe sites with a private on-device DNS filter.",
                    { false },
                    { enableContentFilter() },
                    optional = true,
                ),
                Step(
                    "🚀",
                    "Auto-start (some phones)",
                    "On Xiaomi/Oppo/Vivo, allow OpenParental to start on boot.",
                    { false },
                    { openOemAutostart() },
                    optional = true,
                ),
            ),
        )
        steps = setupSteps
        buildRows()
        binding.btnDone.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildRows() {
        steps.forEach { step ->
            val row = ItemPermissionBinding.inflate(layoutInflater, binding.steps, false)
            row.itemIcon.text = step.icon
            row.itemTitle.text = step.title
            row.itemDesc.text = step.desc
            row.root.setOnClickListener { step.open() }
            binding.steps.addView(row.root)
            rows.add(row)
        }
    }

    private fun refresh() {
        val required = steps.filterNot { it.optional }
        var grantedCount = 0
        steps.forEachIndexed { i, step ->
            val row = rows[i]
            val granted = runCatching { step.granted() }.getOrDefault(false)
            if (granted && !step.optional) grantedCount++
            when {
                granted -> chip(row, R.string.chip_done, R.color.op_online, R.color.op_online_container)
                step.optional -> chip(row, R.string.chip_optional, R.color.op_muted, R.color.op_neutral_container)
                else -> chip(row, R.string.chip_setup, R.color.op_attention, R.color.op_attention_container)
            }
        }
        binding.progress.max = required.size
        binding.progress.setProgressCompat(grantedCount, true)
        binding.txtProgress.text = getString(R.string.onb_progress, grantedCount, required.size)
        binding.btnDone.text =
            getString(if (grantedCount == required.size) R.string.onb_all_set else R.string.onb_done)
    }

    private fun chip(row: ItemPermissionBinding, text: Int, @ColorRes fg: Int, @ColorRes bg: Int) {
        row.itemStatus.text = getString(text)
        row.itemStatus.setTextColor(ContextCompat.getColor(this, fg))
        row.itemStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, bg))
    }

    private fun startSafe(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    private fun enableContentFilter() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnConsent.launch(prep) else startVpn()
    }

    private fun startVpn() {
        runCatching { startService(Intent(this, VpnFilterService::class.java)) }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun locationGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    private fun backgroundLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || backgroundLocationGranted()) return
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            startSafe(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBattery() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openOemAutostart() {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        for ((pkg, cls) in candidates) {
            if (runCatching { startActivity(Intent().setClassName(pkg, cls)) }.isSuccess) return
        }
        startSafe(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }
}
