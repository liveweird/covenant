package ch.nokillswit

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.contracts.BlockedUrlException
import ch.nokillswit.contracts.ContractUrlFetcher
import ch.nokillswit.contracts.MAX_FETCH_BYTES
import ch.nokillswit.contracts.isBlockedAddress
import ch.nokillswit.contracts.parseFetchUrl
import ch.nokillswit.contracts.requirePublicHost
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The URL fetch's SSRF posture and response handling. The guards are tested for real; the
 * response-handling logic runs against a plain-HTTP 127.0.0.1 fixture server through the
 * test-only lenient validator (production wiring — the default constructor — keeps the full
 * guard chain; the route tests land with the contracts feature).
 */
class UrlFetchTest {

    // ---- static guard rules -------------------------------------------------------------

    @Test
    fun `parseFetchUrl rejects everything but a clean absolute https URL`() {
        assertFailsWith<BlockedUrlException> { parseFetchUrl("") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("   ") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https://x.example/" + "a".repeat(2100)) }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("not a url ::") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("http://example.com/openapi.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("ftp://example.com/openapi.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("example.com/openapi.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https://user:pass@example.com/x.yaml") }
        assertFailsWith<BlockedUrlException> { parseFetchUrl("https:///openapi.yaml") }

        val uri = parseFetchUrl("  https://example.com:8443/openapi.yaml  ")
        assertEquals("example.com", uri.host)
        assertEquals(8443, uri.port)
    }

    @Test
    fun `isBlockedAddress covers every private and special range, and only those`() {
        val blocked = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "0.0.0.0", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            // The JDK-predicate gaps: CGNAT, IETF protocol assignments, benchmarking, and
            // NAT64 embedding a private IPv4 (a NAT64 gateway would connect to 10.0.0.1).
            "100.64.0.1", "100.127.255.254", "192.0.0.170", "198.18.0.1", "198.19.255.1",
            "64:ff9b::10.0.0.1", "64:ff9b::7f00:1",
        )
        for (literal in blocked) {
            assertTrue(InetAddress.getByName(literal).isBlockedAddress(), "expected blocked: $literal")
        }
        // 64:ff9b:: embedding a PUBLIC IPv4 stays allowed — IPv6-only networks reach the
        // public internet through NAT64, and the embedded target is judged like a native one.
        val public = listOf("1.1.1.1", "140.82.121.3", "2606:4700::1111", "100.128.0.1", "198.20.0.1", "64:ff9b::101:101")
        for (literal in public) {
            assertTrue(!InetAddress.getByName(literal).isBlockedAddress(), "expected public: $literal")
        }
    }

    @Test
    fun `requirePublicHost rejects private literals and unresolvable hosts`() {
        assertFailsWith<BlockedUrlException> { requirePublicHost("127.0.0.1") }
        assertFailsWith<BlockedUrlException> { requirePublicHost("localhost") }
        // .invalid is reserved (RFC 2606) and guaranteed not to resolve.
        assertFailsWith<BlockedUrlException> { requirePublicHost("no-such-host.invalid") }
        val blocked = assertFailsWith<BlockedUrlException> { requirePublicHost("192.168.0.10") }
        assertEquals("192.168.0.10", blocked.host)
    }

    // ---- response handling against the 127.0.0.1 fixture server ------------------------

    private fun fixtureFetcher() = ContractUrlFetcher(urlValidator = { URI(it) })

    private fun withFixtureServer(
        configure: (HttpServer) -> Unit,
        block: (base: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(server)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.respond(path: String, status: Int, body: ByteArray, location: String? = null) {
        createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `a 200 returns the body text`() = withFixtureServer(
        configure = { it.respond("/ok", 200, "openapi: 3.1.0\ninfo:\n  title: fetched\n".toByteArray()) },
    ) { base ->
        val fetched = runBlocking { fixtureFetcher().fetch("$base/ok") }
        assertTrue(fetched.content.contains("title: fetched"))
    }

    @Test
    fun `non-200, redirect, oversize, and unreachable all become 502-grade failures`() =
        withFixtureServer(
            configure = { server ->
                server.respond("/missing", 404, "not here".toByteArray())
                server.respond("/moved", 302, ByteArray(0), location = "https://example.com/final")
                server.respond("/huge", 200, ByteArray(MAX_FETCH_BYTES + 1))
            },
        ) { base ->
            runBlocking {
                assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/missing") }
                val redirect = assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/moved") }
                assertTrue(redirect.message!!.contains("redirects"))
                val oversize = assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("$base/huge") }
                assertTrue(oversize.message!!.contains("1 MB"))
                // A connection-refused IOException (nothing listens on the reserved port 1).
                assertFailsWith<BadGatewayException> { fixtureFetcher().fetch("http://127.0.0.1:1/x") }
            }
        }
}
