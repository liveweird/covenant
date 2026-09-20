package ch.nokillswit

import ch.nokillswit.toadie.HttpToadieGraphqlClient
import ch.nokillswit.toadie.ToadieFetchConfig
import ch.nokillswit.toadie.ToadieFetchException
import ch.nokillswit.toadie.ToadieMapping
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToadieGraphqlClientTest {
    private val mapper = ObjectMapper()
    private val blueprints = """[
        {"id":"1","identifier":"api","relations":{}},
        {"id":"2","identifier":"service","relations":{
          "provides_apis":{"target":"api","many":true},"consumes_apis":{"target":"api","many":true},
          "system":{"target":"system","many":false}}},
        {"id":"3","identifier":"system","relations":{}},
        {"id":"4","identifier":"_team","relations":{}}
    ]"""
    private val entities = """[
        {"id":"1","blueprint":"api","identifier":"orders","title":"Orders","team":null,"relations":{},"updatedAt":1},
        {"id":"2","blueprint":"api","identifier":"events","title":"Events","relations":{},"updatedAt":1},
        {"id":"3","blueprint":"service","identifier":"checkout","title":"Checkout","team":["retail"],
         "relations":{"provides_apis":["orders","events"],"consumes_apis":"events","system":"commerce"},"updatedAt":2},
        {"id":"4","blueprint":"system","identifier":"commerce","title":"Commerce","team":"retail",
         "relations":{},"updatedAt":1},
        {"id":"5","blueprint":"_team","identifier":"retail","title":"Retail","relations":{},"updatedAt":1}
    ]"""

    private class Fixture(val server: HttpServer) {
        val requests = CopyOnWriteArrayList<JsonNode>()
        val auth = CopyOnWriteArrayList<String>()
        var status = 200
        var delayMs = 0L
        var rawBody: String? = null
        var edit: (JsonNode, ObjectNode) -> Unit = { _, _ -> }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = Fixture(server)
        server.createContext("/integration/graphql") { exchange -> respond(exchange, fixture) }
        server.start()
        try { block(fixture) } finally { server.stop(0) }
    }

    private fun respond(exchange: HttpExchange, fixture: Fixture) {
        val request = mapper.readTree(exchange.requestBody.readAllBytes())
        fixture.requests.add(request)
        fixture.auth.add(exchange.requestHeaders.getFirst("Authorization"))
        val root = response(request)
        fixture.edit(request, root)
        if (fixture.delayMs > 0) Thread.sleep(fixture.delayMs)
        val bytes = (fixture.rawBody ?: root.toString()).toByteArray()
        if (fixture.status == 302) exchange.responseHeaders.add("Location", "${fixture.url}/redirect-target")
        exchange.sendResponseHeaders(fixture.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun response(request: JsonNode): ObjectNode {
        val variables = request.path("variables")
        val blueprint = variables.path("blueprint").textValue()
        val field = if (blueprint == null) "blueprints" else "entities"
        val all = if (blueprint == null) mapper.readTree(blueprints).toList()
            else mapper.readTree(entities).filter { it.path("blueprint").textValue() == blueprint }
        val page = variables.path("page").intValue()
        val pageSize = variables.path("pageSize").intValue()
        val rows = all.drop((page - 1) * pageSize).take(pageSize)
        return mapper.valueToTree(mapOf("data" to mapOf(field to mapOf(
            "items" to rows, "page" to page, "pageSize" to pageSize, "total" to all.size,
        ))))
    }

    private fun config(f: Fixture) = ToadieFetchConfig(f.url, "private-fixture-key", ToadieMapping())

    private fun assertFailure(f: Fixture, code: String, client: HttpToadieGraphqlClient = HttpToadieGraphqlClient()) {
        client.use {
            val error = assertFailsWith<ToadieFetchException> { runBlocking { it.fetch(config(f)) } }
            assertEquals(code, error.code)
            assertFalse(error.toString().contains("private-fixture-key"))
        }
    }

    @Test
    fun `fetches every page with server-only credentials and retains only declared metadata`() = fixture { f ->
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val snapshot = runBlocking { client.fetch(config(f)) }
            assertEquals(5, snapshot.entities.size)
            assertEquals("system", snapshot.systemBlueprint)
            val service = snapshot.entities.single { it.blueprint == "service" }
            assertEquals(listOf("orders", "events"), service.relations["provides_apis"])
            assertEquals(listOf("events"), service.relations["consumes_apis"])
            assertEquals(listOf("retail"), service.teamIdentifiers)
            assertEquals(listOf("retail"), snapshot.entities.single { it.blueprint == "system" }.teamIdentifiers)
            assertEquals(9, f.requests.size)
            assertTrue(f.auth.all { it == "Bearer private-fixture-key" })
            assertTrue(f.requests.all { it.path("operationName").asText() == "CovenantUsage" })
            assertTrue(f.requests.none { it.path("query").asText().contains("properties") })
        }
    }

    @Test
    fun `canonicalizes blueprint configuration while preserving exact relation identities`() = fixture { f ->
        HttpToadieGraphqlClient().use { client ->
            val result = runBlocking { client.fetch(config(f).copy(mapping = ToadieMapping(serviceBlueprint = "SERVICE"))) }
            assertEquals("service", result.entities.single { it.identifier == "checkout" }.blueprint)
        }
        f.edit = { _, root -> root.at("/data/entities/items").firstOrNull { it.path("id").asText() == "3" }?.let {
            (it.path("relations") as ObjectNode).put("consumes_apis", "Events")
        } }
        assertFailure(f, "INVALID_RESPONSE")
    }

    @Test
    fun `accepts Toadie entity identifiers and titles up to 200 characters`() = fixture { f ->
        val longIdentifier = "a".repeat(200)
        f.edit = { _, root -> root.at("/data/entities/items").forEach { entity ->
            if (entity.path("identifier").asText() == "orders") {
                (entity as ObjectNode).put("identifier", longIdentifier).put("title", "T".repeat(200))
            }
            if (entity.path("identifier").asText() == "checkout") {
                (entity.path("relations") as ObjectNode).putArray("provides_apis").add(longIdentifier).add("events")
            }
        } }
        HttpToadieGraphqlClient().use { client ->
            val snapshot = runBlocking { client.fetch(config(f)) }
            assertEquals(longIdentifier, snapshot.entities.single { it.id == "1" }.identifier)
        }
    }

    @Test
    fun `rejects redirects without forwarding the key and classifies denied or throttled requests`() = fixture { f ->
        for ((status, code) in mapOf(302 to "UPSTREAM_UNAVAILABLE", 401 to "AUTHENTICATION_FAILED", 429 to "RATE_LIMITED")) {
            f.status = status
            assertFailure(f, code)
        }
        assertEquals(3, f.requests.size)
    }

    @Test
    fun `rejects graphql partial errors and malformed response envelopes`() = fixture { f ->
        f.edit = { _, root -> root.putArray("errors").addObject().put("message", "private upstream error") }
        assertFailure(f, "GRAPHQL_ERROR")
        f.edit = { _, _ -> }
        for (body in listOf("not json", "null", "[]", "{}", "{\"data\":null}", "{} {}")) {
            f.rawBody = body
            assertFailure(f, "INVALID_RESPONSE")
        }
    }

    @Test
    fun `rejects schema drift instead of publishing an empty usage cache`() = fixture { f ->
        f.edit = { _, root -> root.at("/data/blueprints/items").firstOrNull { it.path("identifier").asText() == "service" }?.let {
            (it.at("/relations/consumes_apis") as ObjectNode).put("target", "resource")
        } }
        assertFailure(f, "MAPPING_INVALID")
    }

    @Test
    fun `rejects missing blueprint and malformed relation definitions`() = fixture { f ->
        for (field in listOf("target", "many")) {
            f.edit = { _, root -> root.at("/data/blueprints/items").firstOrNull { it.path("identifier").asText() == "service" }?.let {
                (it.at("/relations/provides_apis") as ObjectNode).remove(field)
            } }
            assertFailure(f, "MAPPING_INVALID")
        }
        f.edit = { _, root -> root.at("/data/blueprints/items").firstOrNull { it.path("identifier").asText() == "_team" }?.let {
            (it as ObjectNode).put("identifier", "other")
        } }
        assertFailure(f, "MAPPING_INVALID")
    }

    @Test
    fun `rejects changing totals repeated ids and short middle pages`() = fixture { f ->
        f.edit = { request, root -> if (request.path("variables").path("page").asInt() == 2) {
            (root.at("/data/blueprints") as? ObjectNode)?.put("total", 5)
        } }
        assertFailure(f, "SOURCE_CHANGED", HttpToadieGraphqlClient(pageSize = 1))
        f.edit = { request, root -> if (request.path("variables").path("page").asInt() == 2) {
            (root.at("/data/blueprints/items/0") as? ObjectNode)?.put("id", "1")
        } }
        assertFailure(f, "SOURCE_CHANGED", HttpToadieGraphqlClient(pageSize = 1))
        f.edit = { _, root -> (root.at("/data/blueprints") as? ObjectNode)?.putArray("items") }
        assertFailure(f, "INVALID_RESPONSE")
    }

    @Test
    fun `rejects malformed selected relations teams metadata and dangling references`() = fixture { f ->
        val edits: List<(ObjectNode) -> Unit> = listOf(
            { it.put("team", 42) },
            { it.putArray("team").add("retail").add("retail") },
            { it.put("team", "missing-team") },
            { it.put("updatedAt", -1) },
            { it.put("id", "03") },
            { it.put("title", "") },
            { (it.path("relations") as ObjectNode).put("consumes_apis", false) },
            { (it.path("relations") as ObjectNode).put("system", "missing-system") },
        )
        edits.forEach { edit ->
            f.edit = { _, root -> root.at("/data/entities/items").firstOrNull { it.path("identifier").asText() == "checkout" }?.let {
                edit(it as ObjectNode)
            } }
            assertFailure(f, "INVALID_RESPONSE")
        }
    }

    @Test
    fun `bounds response size and total rows before accepting a snapshot`() = fixture { f ->
        assertFailure(f, "LIMIT_EXCEEDED", HttpToadieGraphqlClient(maxResponseBytes = 20))
        assertFailure(f, "LIMIT_EXCEEDED", HttpToadieGraphqlClient(maxEntities = 2))
        f.edit = { _, root -> (root.at("/data/blueprints") as? ObjectNode)?.put("total", Long.MAX_VALUE) }
        assertFailure(f, "LIMIT_EXCEEDED")
    }

    @Test
    fun `whole refresh deadline bounds a slow response and parent cancellation propagates`() = fixture { f ->
        f.delayMs = 200
        HttpToadieGraphqlClient(timeoutMs = 50).use { client ->
            assertFailsWith<ToadieFetchException> { runBlocking { client.fetch(config(f)) } }
        }
        HttpToadieGraphqlClient(timeoutMs = 5_000).use { client ->
            assertFailsWith<TimeoutCancellationException> { runBlocking { withTimeout(30) { client.fetch(config(f)) } } }
        }
    }
}
