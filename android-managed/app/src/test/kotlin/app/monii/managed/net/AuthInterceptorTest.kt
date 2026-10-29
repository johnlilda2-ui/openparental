package app.monii.managed.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInterceptorTest {
    @Test
    fun recognizes_bootstrap_routes_at_root_and_under_a_base_path_prefix() {
        assertTrue(AuthInterceptor.isBootstrapPath("/pairings/claim"))
        assertTrue(AuthInterceptor.isBootstrapPath("/devices/token"))
        assertTrue(AuthInterceptor.isBootstrapPath("/v1/pairings/claim"))
        assertTrue(AuthInterceptor.isBootstrapPath("/api/v1/devices/token"))
    }

    @Test
    fun does_not_treat_other_routes_as_bootstrap() {
        assertFalse(AuthInterceptor.isBootstrapPath("/heartbeat"))
        assertFalse(AuthInterceptor.isBootstrapPath("/v1/commands/pending"))
        assertFalse(AuthInterceptor.isBootstrapPath("/v1/pairings/claim/extra"))
    }
}
