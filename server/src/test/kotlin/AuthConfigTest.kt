package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

class AuthConfigTest {
    private fun assertRefused(key: String, value: String) = testApplication {
        configureApp(key to value)
        assertStartupFails(key) { startApplication() }
    }

    @Test
    fun `JWT lifetimes must be between one second and one year`() {
        assertRefused("jwt.accessExpiresInSeconds", "0")
        assertRefused("jwt.refreshExpiresInSeconds", Long.MAX_VALUE.toString())
    }

    @Test
    fun `lockout threshold and MFA attempts must be positive`() {
        assertRefused("security.lockout.threshold", "0")
        assertRefused("security.mfa.maxAttempts", "0")
    }

    @Test
    fun `millisecond-converted durations reject overflow-sized values`() {
        assertRefused("security.lockout.durationSeconds", Long.MAX_VALUE.toString())
        assertRefused("security.passwordReset.minIntervalSeconds", Long.MAX_VALUE.toString())
        assertRefused("security.mfa.codeTtlSeconds", Long.MAX_VALUE.toString())
    }

    @Test
    fun `the thirty-day duration ceiling boots`() = testApplication {
        val ceiling = (30L * 24 * 3600).toString()
        configureApp(
            "security.lockout.durationSeconds" to ceiling,
            "security.passwordReset.minIntervalSeconds" to ceiling,
            "security.mfa.codeTtlSeconds" to ceiling,
        )
        startApplication()
    }
}
