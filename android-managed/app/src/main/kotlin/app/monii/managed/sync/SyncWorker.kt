package app.monii.managed.sync

import android.content.Context
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.monii.managed.admin.AdminManager
import app.monii.managed.commands.CommandDispatcher
import app.monii.managed.identity.DeviceStore
import app.monii.managed.location.LocationReporter
import app.monii.managed.repo.MoniiRepository

/** WorkManager backstop: heartbeat, location, events, FCM token and commands. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = DeviceStore(applicationContext)
        if (!store.isPaired()) return Result.success()
        val repo = MoniiRepository(applicationContext, store)
        val admin = AdminManager(applicationContext)
        return try {
            // Keep FCM current after Firebase rotates the registration token.
            repo.syncFcmToken()
            repo.heartbeat(batteryPct())
            // Upload the newest cached fix without holding the sync cycle for a GPS timeout.
            // Explicit REQUEST_LOCATION commands request a fresh fix.
            LocationReporter(applicationContext).reportLastKnown(repo)
            repo.flushEvents()
            CommandDispatcher(applicationContext, repo, admin).syncAndExecute()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun batteryPct(): Int? {
        val bm = applicationContext.getSystemService(BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }
}
