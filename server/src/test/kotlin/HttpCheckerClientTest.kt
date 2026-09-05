package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.CheckerUnavailableException
import ch.nokillswit.contracts.checks.HttpCheckerClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The real client against a 127.0.0.1 fixture: the request shape, the token, and every failure → unavailable. */
class HttpCheckerClientTest {

    private fun withFixture(configure: (HttpServer) -> Unit, block: (base: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(server)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.respond(
        status: Int,
        body: String,
        capture: (path: String, token: String?, body: String) -> Unit = { _, _, _ -> },
    ) {
        createContext("/") { exchange ->
            val request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            capture(exchange.requestURI.path, exchange.requestHeaders.getFirst("X-Checker-Token"), request)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `posts the request with the token and maps the findings`() = withFixture(
        configure = { server ->
            server.respond(
                200,
                """{"findings":[{"severity":"WARN","source":"LINT","code":"info-contact","message":"m",""" +
                    """"path":"/info","line":2,"column":1}],"engine":[{"name":"x","version":"1"}]}""",
            ) { path, token, body ->
                assertEquals("/check", path)
                assertEquals("secret", token)
                assertTrue(body.contains("\"type\":\"OPENAPI\""))
                assertTrue(body.contains("openapi: 3.1.0"))
            }
        },
    ) { base ->
        val response = runBlocking { HttpCheckerClient("$base/", "secret", 5_000).check(ContractType.OPENAPI, "openapi: 3.1.0\n") }
        assertEquals("info-contact", response.findings.single().code)
        assertEquals(2, response.findings.single().line)
        assertEquals("x", response.engine.single().name)
    }

    @Test
    fun `a non-200, a malformed body, and an unreachable host are all unavailable`() {
        withFixture(configure = { it.respond(401, """{"title":"Unauthorized","status":401}""") }) { base ->
            val e = assertFailsWith<CheckerUnavailableException> {
                runBlocking { HttpCheckerClient(base, null, 5_000).check(ContractType.OPENAPI, "x") }
            }
            assertTrue(e.message!!.contains("401"))
        }
        withFixture(configure = { it.respond(200, "not json") }) { base ->
            assertFailsWith<CheckerUnavailableException> {
                runBlocking { HttpCheckerClient(base, null, 5_000).check(ContractType.ASYNCAPI, "x") }
            }
        }
        assertFailsWith<CheckerUnavailableException> {
            runBlocking { HttpCheckerClient("http://127.0.0.1:1", null, 2_000).check(ContractType.OPENAPI, "x") }
        }
    }
}
