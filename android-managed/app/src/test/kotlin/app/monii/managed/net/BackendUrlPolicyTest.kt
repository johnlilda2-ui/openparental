package app.monii.managed.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendUrlPolicyTest {
    @Test
    fun accepts_https_remote_backend_and_adds_trailing_slash() {
        assertEquals(
            "https://api.example.com/v1/",
            BackendUrlPolicy.normalize(" https://api.example.com/v1 "),
        )
    }

    @Test
    fun allows_android_emulator_host_for_local_development() {
        assertEquals(
            "http://10.0.2.2:3000/",
            BackendUrlPolicy.normalize("http://10.0.2.2:3000"),
        )
    }

    @Test
    fun rejects_ipv6_loopback_http_not_allowed_by_network_security_config() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("http://[::1]:3000")
        }
    }

    @Test
    fun rejects_cleartext_remote_backend() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("http://api.example.com")
        }
    }

    @Test
    fun rejects_credentials_embedded_in_backend_url() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("https://user:password@api.example.com")
        }
    }

    @Test
    fun rejects_query_strings_and_fragments() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("https://api.example.com?target=other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("https://api.example.com#section")
        }
    }

    @Test
    fun rejects_non_http_schemes_and_invalid_urls() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("ftp://api.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendUrlPolicy.normalize("api.example.com")
        }
    }
}
