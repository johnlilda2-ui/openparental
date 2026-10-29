package app.monii.managed.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates the configured API origin before it is persisted or used.
 *
 * HTTPS is mandatory for remote backends because the device token and pairing
 * credentials travel over this connection. Plain HTTP is reserved for explicit
 * local development endpoints; never permit arbitrary cleartext URLs.
 */
object BackendUrlPolicy {
    private val localDevelopmentHosts = setOf(
        "10.0.2.2", // Android emulator -> host machine
        "localhost",
        "127.0.0.1",
        "::1",
    )

    fun normalize(candidate: String): String {
        val raw = candidate.trim()
        val url = raw.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid absolute backend URL, including https://")

        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Backend URL must not contain a username or password"
        }
        require(url.encodedQuery == null && url.encodedFragment == null) {
            "Backend URL must not contain a query string or fragment"
        }

        if (url.scheme == "http" && url.host !in localDevelopmentHosts) {
            throw IllegalArgumentException(
                "HTTPS is required for remote backends. HTTP is allowed only for local development (localhost or Android emulator host 10.0.2.2).",
            )
        }

        return url.newBuilder()
            .encodedPath(if (url.encodedPath.endsWith("/")) url.encodedPath else "${url.encodedPath}/")
            .build()
            .toString()
    }
}
