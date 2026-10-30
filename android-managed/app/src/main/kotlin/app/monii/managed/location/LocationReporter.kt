package app.monii.managed.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.monii.managed.repo.MoniiRepository

/** Reports the newest available GPS/network fix to the backend. */
class LocationReporter(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Returns true only when a location was actually uploaded. */
    suspend fun reportLastKnown(repo: MoniiRepository): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "Location update skipped: location permission is not granted")
            return false
        }
        val locationManager = manager ?: return false
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue
            val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                ?: continue
            if (best == null || location.time > best.time) best = location
        }
        val location = best ?: run {
            Log.w(TAG, "No cached GPS/network location is available")
            return false
        }
        repo.reportLocation(
            location.latitude,
            location.longitude,
            location.accuracy.toDouble(),
        )
        Log.i(TAG, "Location uploaded; ageMs=${System.currentTimeMillis() - location.time}, accuracyM=${location.accuracy}")
        return true
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object { const val TAG = "MoniiLocation" }
}
