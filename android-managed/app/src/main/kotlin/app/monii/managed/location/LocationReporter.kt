package app.monii.managed.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import app.monii.managed.repo.MoniiRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reports a fresh, single location fix for an explicit parent-requested command.
 * No stale last-known coordinates are reported as if they were current.
 */
class LocationReporter(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    suspend fun reportCurrent(repo: MoniiRepository): Map<String, Any?> {
        if (!hasPermission()) {
            throw SecurityException("Location permission is not granted on the child device")
        }
        val manager = lm ?: throw IllegalStateException("Location service is unavailable")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            throw IllegalStateException("Location is turned off or no location provider is enabled")
        }

        var location: Location? = null
        for (provider in providers) {
            location = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    currentLocation(manager, provider)
                } else {
                    singleUpdate(manager, provider)
                }
            }
            if (location != null) break
        }
        val fix = location ?: throw IllegalStateException("Could not obtain a fresh location fix before timeout")

        repo.reportLocation(fix.latitude, fix.longitude, fix.accuracy.toDouble())
        return mapOf(
            "reported" to true,
            "latitude" to fix.latitude,
            "longitude" to fix.longitude,
            "accuracyM" to fix.accuracy.toDouble(),
            "locationTimeMs" to fix.time,
        )
    }

    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (error: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun singleUpdate(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
            try {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (error: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 10_000L
    }
}
