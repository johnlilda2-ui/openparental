package app.monii.managed.net

import app.monii.managed.identity.DeviceStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the stored device token to authenticated device requests. Pairing and
 * reauthentication are bootstrap endpoints and must never receive an old
 * bearer token, especially when a user changes the configured backend URL.
 */
class AuthInterceptor(private val store: DeviceStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.trimStart('/')
        val isBootstrapRequest =
            path == "pairings/claim" || path == "devices/token"

        val token = if (isBootstrapRequest) null else store.token()
        val authenticatedRequest =
            if (token != null) {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                request.newBuilder()
                    .removeHeader("Authorization")
                    .build()
            }
        return chain.proceed(authenticatedRequest)
    }
}
