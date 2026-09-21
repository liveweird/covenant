package ch.nokillswit

import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.validateEnvironmentRequest
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TargetValidationTest {
    private fun validate(host: String) = validateEnvironmentRequest(
        EnvironmentRequest(systemId = 1u, name = "literal", httpBaseUrl = "http://$host/service"),
        passwordRequired = false,
    )

    @Test
    fun `link local classification uses address bytes including abbreviated and mapped literals`() {
        val hosts = listOf(
            "169.254.169.254", "169.254.0.0", "169.254.255.255",
            "2852039166", "0169.0254.0169.0254", "[fe80::1]", "[fe90::1]", "[fea0::1]", "[febf:ffff::1]",
            "[FE80:0:0:0:0:0:0:1]", "[fe80::1%25unavailable]", "[::ffff:169.254.169.254]", "[::ffff:a9fe:a9fe]",
        )
        for (host in hosts) {
            val error = assertFailsWith<BadRequestException>(host) { validate(host) }
            assertTrue(error.message.orEmpty().contains("link-local"), host)
        }
    }

    @Test
    fun `abbreviated dotted forms already rejected by URI remain invalid`() {
        listOf("169.254.43518", "169.16689662").forEach { host ->
            assertFailsWith<BadRequestException>(host) { validate(host) }
        }
    }

    @Test
    fun `internal targets and non link local boundary addresses remain permitted without DNS`() {
        listOf(
            "localhost", "gateway.internal", "unresolved-target.invalid", "169.253.255.255", "169.255.0.0",
            "127.0.0.1", "2130706433", "10.0.0.1", "192.168.0.1", "172.16.0.1",
            "[::1]", "[fd00::1]", "[fe7f:ffff::1]", "[fec0::1]", "[::ffff:10.0.0.1]",
        ).forEach(::validate)
    }
}
