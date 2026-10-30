package app.monii.managed.identity

import android.content.Context
import java.util.UUID

/** Device identity and backend configuration. */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun baseUrl(): String = prefs.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
    fun setBaseUrl(url: String) = prefs.edit().putString("base_url", url.trim()).apply()

    fun installationId(): String {
        val existing = prefs.getString("installation_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", created).commit()
        return prefs.getString("installation_id", created) ?: created
    }

    fun token(): String? = prefs.getString("device_token", null)
    fun setToken(token: String) = prefs.edit().putString("device_token", token).apply()
    fun deviceId(): String? = prefs.getString("device_id", null)
    fun deviceSecret(): String? = prefs.getString("device_secret", null)
    fun isPaired(): Boolean = token() != null && deviceId() != null

    fun savePairing(deviceId: String, token: String, secret: String) {
        prefs.edit().putString("device_id", deviceId).putString("device_token", token)
            .putString("device_secret", secret).apply()
    }
    fun clearPairing() {
        prefs.edit().remove("device_id").remove("device_token").remove("device_secret").apply()
    }

    fun fcmToken(): String? = prefs.getString("fcm_token", null)
    fun setFcmToken(token: String) {
        if (token != fcmToken()) prefs.edit().putString("fcm_token", token).putBoolean("fcm_needs_sync", true).apply()
    }
    /** Force re-registration on app startup, even if Firebase returns the same cached token. */
    fun requestFcmSync() = prefs.edit().putBoolean("fcm_needs_sync", true).apply()
    fun fcmNeedsSync(): Boolean = prefs.getBoolean("fcm_needs_sync", false) && fcmToken() != null
    fun markFcmSynced() = prefs.edit().putBoolean("fcm_needs_sync", false).apply()

    companion object {
        private const val PREFS = "monii_identity"
        const val DEFAULT_BASE = "https://openparental-api-jxh4.onrender.com"
    }
}
