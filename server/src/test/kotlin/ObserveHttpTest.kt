package ch.nokillswit

import ch.nokillswit.contracts.infer.ObserveHttpRequest
import ch.nokillswit.contracts.infer.ObserveHttpResponse
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.PostgresTargetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP observe leg (`contracts/infer/`) against a 127.0.0.1 fixture — the same trust boundary
 * and the same `HttpTry` code as try-it, minus a document to check the operation against.
 */
class ObserveHttpTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private class Fixture(val server: HttpServer) {
        val base get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun startFixture(): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = Fixture(server)
        server.createContext("/") { ex ->
            val (status, body) = when (ex.requestURI.path) {
                "/orders/42" -> 200 to """{"id": 42, "total": 19.5}"""
                "/big" -> 200 to "x".repeat(1024 * 1024 + 10)
                else -> 404 to "nope"
            }
            val json = ex.requestURI.path != "/big"
            ex.responseHeaders.add("Content-Type", if (json) "application/json" else "text/plain")
            ex.responseHeaders.add("Set-Cookie", "session=secret")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        return fixture
    }

    private suspend fun environment(systemId: UInt, baseUrl: String?): UInt =
        TestEnvironments.service.create(EnvironmentRequest(systemId, name("env"), null, baseUrl, null, null))

    private suspend fun HttpClient.observeHttp(request: ObserveHttpRequest): HttpResponse =
        postJson("/api/v1/contracts/infer/observe/http", request)

    @Test
    fun `a request goes out with the caller's headers - the sample carries names and the scheme only, never the value`() =
        testApplication {
            usePostgresTestcontainer()
            val fixture = startFixture()
            try {
                val admin = seededClient("observehttp", UserRole.ADMIN)
                val systemId = TestContracts.seedSystem("observehttp")
                val env = environment(systemId, fixture.base)

                val capture = LogCapture("ch.nokillswit.audit")
                val response = try {
                    admin.observeHttp(
                        ObserveHttpRequest(env, "get", "/orders/42", headers = mapOf("Authorization" to "Bearer user-typed")),
                    )
                } finally {
                    capture.detach()
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val body = response.body<ObserveHttpResponse>()
                assertEquals("GET", body.sample.method)
                assertEquals("${fixture.base}/orders/42", body.sample.url)
                assertEquals(200, body.sample.status)
                assertTrue("authorization" in body.sample.requestHeaders)
                assertEquals("bearer", body.sample.authorizationScheme)
                assertTrue("set-cookie" !in body.sample.responseHeaders, "cookies never cross")
                assertFalse(response.bodyAsText().contains("user-typed"), "the header value never rides the response")
                val event = capture.events.single { it.message == "contract.observed_http" }
                assertTrue(event.hasKeyValue("host", "127.0.0.1") && event.hasKeyValue("outcome", "answered"), event.toString())
                assertTrue(event.hasKeyValue("method", "GET"))
                assertFalse(event.keyValuePairs.orEmpty().any { "${it.value}".contains("user-typed") }, "never a header value")

                val huge = admin.observeHttp(ObserveHttpRequest(env, "get", "/big")).body<ObserveHttpResponse>()
                assertNull(huge.sample.responseBody)
                assertTrue(huge.notes.any { it.code == "INFER_BODY_SKIPPED" }, huge.notes.toString())
            } finally {
                fixture.server.stop(0)
            }
        }

    @Test
    fun `the 400 and 404 matrix - unknown method, templated path, forbidden header, unknown environment, no HTTP target`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("observehttp-bad", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("observehttp-bad")
            suspend fun detail(request: ObserveHttpRequest, expected: HttpStatusCode = HttpStatusCode.BadRequest): String {
                val r = admin.observeHttp(request)
                assertEquals(expected, r.status, r.bodyAsText())
                return r.body<ProblemDetail>().detail.orEmpty()
            }
            val env = environment(systemId, "http://127.0.0.1:9")
            assertTrue(detail(ObserveHttpRequest(env, "TRACE", "/x")).contains("Unknown HTTP method"))
            assertTrue(detail(ObserveHttpRequest(env, "GET", "/orders/{id}")).contains("must be concrete"))
            val spaced = detail(ObserveHttpRequest(env, "GET", "/orders 1"))
            assertTrue(spaced.contains("must be concrete"), "an unencoded space is a 400, not a 500")
            assertTrue(detail(ObserveHttpRequest(env, "GET", "/x", headers = mapOf("Cookie" to "a=b"))).contains("'Cookie'"))
            detail(ObserveHttpRequest(env, "POST", "/x", body = "x".repeat(1024 * 1024 + 1)), HttpStatusCode.PayloadTooLarge)
            assertEquals(HttpStatusCode.NotFound, admin.observeHttp(ObserveHttpRequest(999_999u, "GET", "/x")).status)
            val noHttp = TestEnvironments.service.create(
                EnvironmentRequest(
                    systemId, name("nohttp"), null, null, null,
                    PostgresTargetRequest("jdbc:postgresql://db:5432/x", "u", "p"),
                ),
            )
            assertTrue(detail(ObserveHttpRequest(noHttp, "GET", "/x")).contains("no HTTP base URL"))
            val unreachable = admin.observeHttp(ObserveHttpRequest(env, "GET", "/x"))
            assertEquals(HttpStatusCode.BadGateway, unreachable.status, "port 9 answers nobody")
        }

    @Test
    fun `the tryIt bucket answers 429 once exhausted`() = testApplication {
        configureApp("security.rateLimit.tryPerMinute" to "1")
        startApplication()
        val admin = seededClient("observehttp-rl", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("observehttp-rl")
        val env = environment(systemId, "http://127.0.0.1:9")
        assertEquals(HttpStatusCode.BadGateway, admin.observeHttp(ObserveHttpRequest(env, "GET", "/x")).status)
        val throttled = admin.observeHttp(ObserveHttpRequest(env, "GET", "/x"))
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertEquals(429, throttled.body<ProblemDetail>().status)
    }
}
