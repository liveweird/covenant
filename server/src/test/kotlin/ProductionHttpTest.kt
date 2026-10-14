package ch.nokillswit

import ch.nokillswit.plugins.ReadinessProbe
import ch.nokillswit.plugins.ReadinessProbeKey
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Production redirect, proxy-alias, forwarded-port, and direct/proxied probe regressions. */
class ProductionHttpTest {

    private suspend fun ApplicationTestBuilder.bootProduction(behindProxy: Boolean) {
        configureApp(
            "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
            "jwt.secret" to strongJwtSecret(),
            "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
            "http.behindProxy" to behindProxy.toString(),
        )
        application { attributes.put(ReadinessProbeKey, ReadinessProbe { false }) }
        serverConfig { developmentMode = false }
        startApplication()
    }

    @Test
    fun `production probes reach real handlers without proxy trust while regular paths redirect`() =
        testApplication {
            withSeedRestored {
                bootProduction(behindProxy = false)
                val client = createClient { followRedirects = false }

                val health = client.get("/api/v1/health") {
                    header(HttpHeaders.XForwardedProto, "https")
                }
                assertEquals(HttpStatusCode.OK, health.status)
                val ready = client.get("/api/v1/ready") {
                    header(HttpHeaders.XForwardedProto, "https")
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
                val regular = client.get("/") {
                    header(HttpHeaders.XForwardedProto, "https")
                }
                assertEquals(
                    HttpStatusCode.MovedPermanently,
                    regular.status,
                    "an arbitrary forwarded header remains untrusted when proxy handling is disabled",
                )
            }
        }

    @Test
    fun `production probes reach real handlers behind proxy while regular paths redirect`() = testApplication {
        withSeedRestored {
            bootProduction(behindProxy = true)
            val client = createClient { followRedirects = false }

            val health = client.get("/api/v1/health") {
                header(HttpHeaders.XForwardedProto, "https")
            }
            assertEquals(HttpStatusCode.OK, health.status)
            val ready = client.get("/api/v1/ready") {
                header(HttpHeaders.XForwardedProto, "https")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
            assertEquals(HttpStatusCode.MovedPermanently, client.get("/").status)
        }
    }

    @Test
    fun `forwarded aliases cannot steer or suppress production redirects`() = testApplication {
        withSeedRestored {
            bootProduction(behindProxy = true)
            val client = createClient { followRedirects = false }

            val spoofedHost = client.get("/") {
                header("X-Forwarded-Server", "evil.example")
            }
            assertEquals(HttpStatusCode.MovedPermanently, spoofedHost.status)
            val location = assertNotNull(spoofedHost.headers[HttpHeaders.Location])
            assertFalse("evil.example" in location, "X-Forwarded-Server must not steer the redirect: $location")

            val spoofedScheme = client.get("/") {
                header("X-Forwarded-Protocol", "https")
                header("X-Forwarded-SSL", "on")
                header("Front-End-Https", "on")
            }
            assertEquals(HttpStatusCode.MovedPermanently, spoofedScheme.status)

            val canonical = client.get("/") {
                header(HttpHeaders.XForwardedProto, "https")
            }
            assertEquals(HttpStatusCode.NotFound, canonical.status)
        }
    }

    @Test
    fun `short forwarded chains and junk forwarded ports never fail requests`() = testApplication {
        withSeedRestored {
            configureApp(
                "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
                "jwt.secret" to strongJwtSecret(),
                "security.encryption.key" to strongEncryptionKey(),
                "mail.transport" to "disabled",
                "http.behindProxy" to "true",
                "http.proxyHops" to "3",
            )
            serverConfig { developmentMode = false }
            startApplication()
            val client = createClient { followRedirects = false }

            val response = client.get("/") {
                header(HttpHeaders.XForwardedFor, "203.0.113.5")
                header(HttpHeaders.XForwardedProto, "https")
                header("X-Forwarded-Port", "not-a-number")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }
}
