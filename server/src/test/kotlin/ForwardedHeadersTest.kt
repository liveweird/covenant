package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the trusted-hop selection used by per-IP rate limits when proxy handling is enabled. */
class ForwardedHeadersTest {

    private val seeded = mutableListOf<UInt>()

    @AfterTest
    fun removeSeededAccounts() = runBlocking {
        seeded.forEach { TestUsers.softDelete(it) }
        seeded.clear()
    }

    private suspend fun HttpClient.login(forwardedFor: String?): HttpStatusCode {
        val email = uniqueEmail("xff")
        seeded += TestUsers.seed(email, "the-right-password", role = UserRole.USER)
        return post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
            setBody(LoginRequest(email, "wrong"))
        }.status
    }

    @Test
    fun `behind a proxy the login bucket keys on the last X-Forwarded-For value`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()

        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1, 203.0.113.7"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1, 203.0.113.7"))
        assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.99.1, 203.0.113.8"))
    }

    @Test
    fun `proxyHops 2 trusts the value before the last one`() = testApplication {
        configureApp(
            "http.behindProxy" to "true",
            "http.proxyHops" to "2",
            "security.rateLimit.loginPerMinute" to "10",
        )
        startApplication()
        val client = jsonClient()

        repeat(10) { i ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.login("10.9.$i.1, 203.0.113.7, 198.51.100.1"),
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.login("10.9.99.1, 203.0.113.7, 198.51.100.1"),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.login("10.9.99.1, 203.0.113.9, 198.51.100.1"),
        )
    }

    @Test
    fun `without behindProxy X-Forwarded-For is ignored`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()

        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1"))
    }

    @Test
    fun `a non-numeric proxyHops fails startup naming the key`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "abc")
        assertStartupFails("Config \"http.proxyHops\" must be an integer") { startApplication() }
    }

    @Test
    fun `proxyHops below 1 fails startup`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "0")
        assertStartupFails("http.proxyHops") { startApplication() }
    }
}
