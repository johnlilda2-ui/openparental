package app.monii.managed.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device identity + backend base URL. API credentials are encrypted with a
 * non-exportable Android Keystore AES key; other non-secret settings remain in
 * private SharedPreferences. Legacy plaintext credentials are migrated on read.
 */
class DeviceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(): String = prefs.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
    fun setBaseUrl(url: String) = prefs.edit().putString("base_url", url.trim()).apply()

    fun token(): String? = readSensitive("device_token")
    fun setToken(token: String) = writeSensitive("device_token", token)

    fun deviceId(): String? = prefs.getString("device_id", null)
    fun deviceSecret(): String? = readSensitive("device_secret")

    fun isPaired(): Boolean = token() != null && deviceId() != null

    fun savePairing(deviceId: String, token: String, secret: String) {
        val encryptedToken = encrypt(token)
        val encryptedSecret = encrypt(secret)
        prefs.edit()
            .putString("device_id", deviceId)
            .putString("device_token_encrypted", encryptedToken)
            .putString("device_secret_encrypted", encryptedSecret)
            .remove("device_token")
            .remove("device_secret")
            .apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove("device_id")
            .remove("device_token")
            .remove("device_secret")
            .remove("device_token_encrypted")
            .remove("device_secret_encrypted")
            .apply()
    }

    fun fcmToken(): String? = prefs.getString("fcm_token", null)

    fun setFcmToken(token: String) {
        if (token != fcmToken()) {
            prefs.edit().putString("fcm_token", token).putBoolean("fcm_needs_sync", true).apply()
        }
    }

    fun fcmNeedsSync(): Boolean =
        prefs.getBoolean("fcm_needs_sync", false) && fcmToken() != null

    fun markFcmSynced() = prefs.edit().putBoolean("fcm_needs_sync", false).apply()

    private fun readSensitive(name: String): String? {
        val encrypted = prefs.getString("${name}_encrypted", null)
        if (encrypted != null) return runCatching { decrypt(encrypted) }.getOrNull()

        // Upgrade credentials stored by previous builds, then remove plaintext.
        val legacy = prefs.getString(name, null) ?: return null
        val upgraded = runCatching { encrypt(legacy) }.getOrNull()
        if (upgraded == null) {
            prefs.edit().remove(name).apply()
            return null
        }
        prefs.edit()
            .putString("${name}_encrypted", upgraded)
            .remove(name)
            .apply()
        return legacy
    }

    private fun writeSensitive(name: String, value: String) {
        val encrypted = encrypt(value)
        prefs.edit()
            .putString("${name}_encrypted", encrypted)
            .remove(name)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val combined = cipher.iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_BYTES) { "Encrypted credential is truncated" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    companion object {
        private const val PREFS = "monii_identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "openparental_device_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val KEY_LOCK = Any()

        // 10.0.2.2 = the host machine from the Android emulator.
        const val DEFAULT_BASE = "http://10.0.2.2:3000"
    }
}
