package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.tryit.Conformance
import ch.nokillswit.contracts.tryit.TryHttpRequest
import ch.nokillswit.contracts.tryit.TryHttpResponse
import ch.nokillswit.environments.EnvironmentRequest
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
 * The HTTP try leg against a 127.0.0.1 fixture — a legal environment (the trust boundary is the
 * ADMIN registry, not a public-host guard), so no seam is needed.
 */
class TryHttpTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val petstore = """
        openapi: 3.0.3
        info: { title: Pets, version: 1.0.0 }
        paths:
          /pets/{id}:
            get:
              parameters:
                - { name: id, in: path, required: true, schema: { type: integer } }
              responses:
                "200":
                  description: ok
                  headers:
                    X-Trace: { schema: { type: string } }
                  content:
                    application/json:
                      schema: { ${'$'}ref: "#/components/schemas/Pet" }
          /pets:
            post:
              requestBody:
                required: true
                content:
                  application/json: { schema: { ${'$'}ref: "#/components/schemas/Pet" } }
              responses:
                "201": { description: created }
        components:
          schemas:
            Pet:
              type: object
              required: [id, name]
              properties:
                id: { type: integer }
                name: { type: string }
    """.trimIndent()

    private class Fixture(val server: HttpServer) {
        val base get() = "http://127.0.0.1:${server.address.port}"
        var lastHeaders: Map<String, List<String>> = emptyMap()
        var lastBody = ""
        var lastPath = ""
    }

    private fun startFixture(): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = Fixture(server)
        server.createContext("/") { ex ->
            fixture.lastHeaders = ex.requestHeaders.mapKeys { it.key.lowercase() }
            fixture.lastBody = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            fixture.lastPath = ex.requestURI.toString()
            val (status, body) = when (ex.requestURI.path) {
                "/pets/1" -> 200 to """{"id": 1, "name": "Rex"}"""
                "/pets/2" -> 200 to """{"id": "two"}"""
                "/pets/3" -> 418 to "short and stout"
                "/pets/4" -> {
                    ex.responseHeaders.add("Location", "$fixture/pets/1")
                    302 to ""
                }
                "/pets/5" -> 200 to "x".repeat(1024 * 1024 + 10)
                "/pets" -> 201 to ""
                else -> 404 to "nope"
            }
            val json = status == 200 && ex.requestURI.path != "/pets/5"
            ex.responseHeaders.add("Content-Type", if (json) "application/json" else "text/plain")
            ex.responseHeaders.add("Set-Cookie", "session=secret")
            ex.responseHeaders.add("X-Trace", "abc")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        return fixture
    }

    private suspend fun HttpClient.contract(prefix: String, systemId: UInt, type: ContractType = ContractType.OPENAPI): ContractResponse {
        val teamId = TestTeams.seed(name("t"))
        return postJson("/api/v1/contracts", ContractCreateRequest(systemId, type, name(prefix), null, ownerTeamId = teamId)).body()
    }

    private suspend fun environment(systemId: UInt, baseUrl: String): UInt =
        TestEnvironments.service.create(EnvironmentRequest(systemId, name("env"), null, baseUrl, null, null))

    private suspend fun HttpClient.tryHttp(c: ContractResponse, v: VersionResponse, request: TryHttpRequest): HttpResponse =
        postJson("/api/v1/contracts/${c.id}/versions/${v.id}/try/http", request)

    private fun get(env: UInt, id: String, headers: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap()) =
        TryHttpRequest(env, "get", "/pets/{id}", mapOf("id" to id), query, headers)

    @Test
    fun `a request goes out with the caller's headers, the observation comes back bounded and measured`() = testApplication {
        usePostgresTestcontainer()
        val fixture = startFixture()
        try {
            val admin = seededClient("tryhttp", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("tryhttp")
            val c = admin.contract("tryhttp", systemId)
            val v = admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", petstore)).body<VersionResponse>()
            val env = environment(systemId, fixture.base)

            val capture = LogCapture("ch.nokillswit.audit")
            val ok = try {
                admin.tryHttp(c, v, get(env, "1", mapOf("Authorization" to "Bearer user-typed", "X-Req" to "1"), mapOf("verbose" to "a b")))
            } finally {
                capture.detach()
            }
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            val body = ok.body<TryHttpResponse>()
            assertEquals(200, body.status)
            assertEquals("${fixture.base}/pets/1", body.url, "the query never rides the response")
            assertEquals("/pets/1?verbose=a%20b", fixture.lastPath)
            assertEquals(listOf("Bearer user-typed"), fixture.lastHeaders["authorization"], "the caller's headers are forwarded")
            assertEquals("""{"id": 1, "name": "Rex"}""", body.body)
            assertFalse(body.bodyTruncated)
            assertEquals("abc", body.headers["x-trace"])
            assertNull(body.headers["set-cookie"], "cookies never cross")
            assertEquals(emptyList(), body.conformance.findings, body.conformance.toString())
            assertEquals("/paths/~1pets~1{id}/get/responses/200/content/application~1json/schema", body.conformance.validatedAgainst)
            val event = capture.events.single { it.message == "contract.tried_http" }
            assertTrue(event.hasKeyValue("host", "127.0.0.1") && event.hasKeyValue("outcome", "answered"), event.toString())
            assertTrue(event.hasKeyValue("pathTemplate", "/pets/{id}"))
            assertFalse(event.keyValuePairs.orEmpty().any { "${it.value}".contains("user-typed") }, "never a header value")

            val mismatch = admin.tryHttp(c, v, get(env, "2")).body<TryHttpResponse>()
            assertEquals(listOf(Conformance.RESPONSE_SCHEMA_MISMATCH), mismatch.conformance.findings.map { it.code }.distinct())
            assertTrue(mismatch.conformance.findings.all { it.source == FindingSource.CONFORMANCE && it.severity == Severity.ERROR })
            assertTrue(mismatch.conformance.errors >= 1)

            val teapot = admin.tryHttp(c, v, get(env, "3")).body<TryHttpResponse>()
            assertEquals(418, teapot.status)
            assertEquals(listOf(Conformance.STATUS_UNDECLARED), teapot.conformance.findings.map { it.code })

            val redirect = admin.tryHttp(c, v, get(env, "4")).body<TryHttpResponse>()
            assertEquals(302, redirect.status, "never followed - shown")
            assertTrue(redirect.conformance.findings.any { it.code == Conformance.STATUS_UNDECLARED })

            val huge = admin.tryHttp(c, v, get(env, "5")).body<TryHttpResponse>()
            assertTrue(huge.bodyTruncated)
            assertEquals(1024 * 1024, huge.body!!.length)
            assertTrue(huge.conformance.findings.any { it.code == Conformance.BODY_NOT_VALIDATED })

            val posted = admin.tryHttp(
                c, v, TryHttpRequest(env, "POST", "/pets", body = """{"id": "nope"}"""),
            ).body<TryHttpResponse>()
            assertEquals(201, posted.status)
            assertEquals("""{"id": "nope"}""", fixture.lastBody, "sent despite the warning")
            assertEquals(listOf("application/json"), fixture.lastHeaders["content-type"])
            assertTrue(posted.conformance.findings.all { it.code == Conformance.REQUEST_SCHEMA_MISMATCH && it.severity == Severity.WARN })
        } finally {
            fixture.server.stop(0)
        }
    }

    @Test
    fun `the 400 matrix - undeclared operation, unbound template, forbidden headers, body without a declaration, wrong system or type`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("tryhttp-bad", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("tryhttp-bad")
            val c = admin.contract("tryhttp-bad", systemId)
            val v = admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", petstore)).body<VersionResponse>()
            val env = environment(systemId, "http://127.0.0.1:9")
            suspend fun detail(request: TryHttpRequest, expected: HttpStatusCode = HttpStatusCode.BadRequest): String {
                val r = admin.tryHttp(c, v, request)
                assertEquals(expected, r.status, r.bodyAsText())
                return r.body<ProblemDetail>().detail.orEmpty()
            }
            assertTrue(detail(TryHttpRequest(env, "DELETE", "/pets/{id}", mapOf("id" to "1"))).contains("declares no DELETE"))
            assertTrue(detail(TryHttpRequest(env, "TRACE", "/pets/{id}", mapOf("id" to "1"))).contains("declares no TRACE"))
            assertTrue(detail(TryHttpRequest(env, "GET", "/pets/1")).contains("declares no GET /pets/1"), "the template, not a path")
            assertTrue(detail(TryHttpRequest(env, "GET", "/pets/{id}")).contains("'id' is missing"))
            assertTrue(detail(get(env, "1").copy(pathParams = mapOf("id" to "1", "x" to "2"))).contains("'x' is not in the template"))
            assertTrue(detail(get(env, "1", mapOf("Host" to "evil"))).contains("'Host'"))
            assertTrue(detail(get(env, "1", mapOf("Proxy-Authorization" to "x"))).contains("'Proxy-Authorization'"))
            assertTrue(detail(get(env, "1", mapOf("Cookie" to "a=b"))).contains("'Cookie'"))
            assertTrue(detail(get(env, "1", mapOf("X-Bad" to "a\r\nInjected: yes"))).contains("invalid value"))
            assertTrue(detail(get(env, "1", mapOf("bad header" to "x"))).contains("not a valid token"))
            assertTrue(detail(get(env, "1").copy(body = "{}")).contains("declares no request body"))
            detail(TryHttpRequest(env, "POST", "/pets", body = "x".repeat(1024 * 1024 + 1)), HttpStatusCode.PayloadTooLarge)
            val otherSystem = TestContracts.seedSystem("tryhttp-other")
            val foreign = environment(otherSystem, "http://127.0.0.1:9")
            assertTrue(detail(get(foreign, "1")).contains("another system"))
            val noHttp = TestEnvironments.service.create(
                EnvironmentRequest(
                    systemId, name("nohttp"), null, null, null,
                    ch.nokillswit.environments.PostgresTargetRequest("jdbc:postgresql://db:5432/x", "u", "p"),
                ),
            )
            assertTrue(detail(get(noHttp, "1")).contains("no HTTP base URL"))
            val odcs = admin.contract("tryhttp-odcs", systemId, ContractType.ODCS)
            val ov = admin.postJson("/api/v1/contracts/${odcs.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.odcs))
                .body<VersionResponse>()
            val wrongType = admin.tryHttp(odcs, ov, get(env, "1"))
            assertEquals(HttpStatusCode.BadRequest, wrongType.status)
            assertTrue(wrongType.body<ProblemDetail>().detail.orEmpty().contains("not OPENAPI"))
            assertEquals(HttpStatusCode.NotFound, admin.tryHttp(c, v, get(999_999u, "1")).status, "an unknown environment")
            assertEquals(HttpStatusCode.NotFound, admin.tryHttp(c, ov, get(env, "1")).status, "a foreign version")
            val unreachable = admin.tryHttp(c, v, get(env, "1"))
            assertEquals(HttpStatusCode.BadGateway, unreachable.status, "port 9 answers nobody")
            assertEquals("The environment could not be reached", unreachable.body<ProblemDetail>().detail)
        }

    @Test
    fun `the tryIt bucket answers 429 once exhausted`() = testApplication {
        configureApp("security.rateLimit.tryPerMinute" to "1")
        startApplication()
        val admin = seededClient("tryhttp-rl", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("tryhttp-rl")
        val c = admin.contract("tryhttp-rl", systemId)
        val v = admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", petstore)).body<VersionResponse>()
        val env = environment(systemId, "http://127.0.0.1:9")
        assertEquals(HttpStatusCode.BadGateway, admin.tryHttp(c, v, get(env, "1")).status)
        val throttled = admin.tryHttp(c, v, get(env, "1"))
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertEquals(429, throttled.body<ProblemDetail>().status)
    }
}
