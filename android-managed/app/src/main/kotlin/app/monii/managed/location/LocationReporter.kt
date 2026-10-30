package app.monii.managed.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import app.monii.managed.repo.MoniiRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Acquires a fresh GPS/network fix, falling back to cached data only if needed. */
class LocationReporter(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Returns true only when coordinates were uploaded successfully. */
    suspend fun reportLastKnown(repo: MoniiRepository): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "Location update skipped: location permission is not granted")
            return false
        }
        val locationManager = manager ?: return false
        val fresh = requestFreshFix(locationManager)
        val location = fresh ?: newestCachedFix(locationManager)
        if (location == null) {
            Log.w(TAG, "No fresh or cached GPS/network fix is available")
            return false
        }
        return try {
            repo.reportLocation(location.latitude, location.longitude, location.accuracy.toDouble())
            Log.i(TAG, "Location uploaded; fresh=${fresh != null}, ageMs=${System.currentTimeMillis() - location.time}, accuracyM=${location.accuracy}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Location upload failed", error)
            throw error
        }
    }

    private suspend fun requestFreshFix(locationManager: LocationManager): Location? =
        withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }
                    @Deprecated("Deprecated by Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                try {
                    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                        .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
                    if (providers.isEmpty()) {
                        continuation.resume(null)
                    } else {
                        providers.forEach { provider ->
                            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        }
                        continuation.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
                    }
                } catch (error: SecurityException) {
                    Log.e(TAG, "Android denied fresh location request", error)
                    if (continuation.isActive) continuation.resume(null)
                } catch (error: Exception) {
                    Log.w(TAG, "Fresh location request failed", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun newestCachedFix(locationManager: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue
            val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || location.time > best.time) best = location
        }
        return best
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MoniiLocation"
        const val FRESH_FIX_TIMEOUT_MS = 20_000L
    }
}
