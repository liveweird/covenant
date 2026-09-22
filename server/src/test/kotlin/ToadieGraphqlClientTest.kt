package ch.nokillswit

import ch.nokillswit.toadie.HttpToadieGraphqlClient
import ch.nokillswit.toadie.ToadieFetchConfig
import ch.nokillswit.toadie.ToadieFetchException
import ch.nokillswit.toadie.ToadieAdoptionAvailability
import ch.nokillswit.toadie.ToadieAdoptionKind
import ch.nokillswit.toadie.ToadieAdoptionMapping
import ch.nokillswit.toadie.ToadieMapping
import ch.nokillswit.toadie.ToadieRegistryMapping
import ch.nokillswit.toadie.ToadieSnapshot
import ch.nokillswit.toadie.ToadieUnchanged
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
import java.util.concurrent.atomic.AtomicBoolean
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
        var blueprintRows: List<JsonNode>? = null
        var entityRows: List<JsonNode>? = null
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
        val root = response(request, fixture)
        fixture.edit(request, root)
        if (fixture.delayMs > 0) Thread.sleep(fixture.delayMs)
        val bytes = (fixture.rawBody ?: root.toString()).toByteArray()
        if (fixture.status == 302) exchange.responseHeaders.add("Location", "${fixture.url}/redirect-target")
        exchange.sendResponseHeaders(fixture.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun response(request: JsonNode, fixture: Fixture): ObjectNode {
        val variables = request.path("variables")
        val blueprint = variables.path("blueprint").textValue()
        val field = if (blueprint == null) "blueprints" else "entities"
        val all = if (blueprint == null) fixture.blueprintRows ?: mapper.readTree(blueprints).toList()
            else (fixture.entityRows ?: mapper.readTree(entities).toList()).filter { it.path("blueprint").textValue() == blueprint }
        val page = variables.path("page").intValue()
        val pageSize = variables.path("pageSize").intValue()
        val rows = all.drop((page - 1) * pageSize).take(pageSize)
        return mapper.valueToTree(mapOf("data" to mapOf(field to mapOf(
            "items" to rows, "page" to page, "pageSize" to pageSize, "total" to all.size, "revision" to "7",
        ))))
    }

    private fun config(f: Fixture) = ToadieFetchConfig(f.url, "private-fixture-key", ToadieMapping())

    @Test
    fun `optional adoption scan retains only mapped scalar metadata and raw declaration values`() = fixture { f ->
        val adoption = ToadieAdoptionMapping()
        f.blueprintRows = mapper.readTree(blueprints).toList() + mapper.readTree("""[
            {"id":"6","identifier":"environment","relations":{}},
            {"id":"7","identifier":"api_adoption","relations":{
              "consumer":{"target":"service","many":false},"api":{"target":"api","many":false},
              "environment":{"target":"environment","many":false}},
             "schema":{"properties":{"major_line":{"type":"string"},"status":{"type":"string"},
              "declared_by":{"type":"string"},"verified_at":{"type":"string"},"notes":{"type":"string"}}}}
        ]""").toList()
        f.entityRows = mapper.readTree(entities).toList() + mapper.readTree("""[
            {"id":"6","blueprint":"environment","identifier":"production","title":"Production",
             "relations":{},"updatedAt":1},
            {"id":"7","blueprint":"api_adoption","identifier":"checkout-orders","title":"Checkout Orders",
             "relations":{"consumer":"checkout","api":"orders","environment":"production"},"updatedAt":1,
             "properties":{"major_line":"v2","status":"declared","declared_by":"team-a",
              "verified_at":"2026-09-22T10:15:30+02:00","notes":"verbatim note","secret":"not retained"}}
        ]""").toList()
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val snapshot = runBlocking { client.fetch(config(f).copy(adoptionMapping = adoption)) } as ToadieSnapshot
            assertEquals(ToadieAdoptionAvailability.AVAILABLE, snapshot.adoptionAvailability)
            assertEquals("environment", snapshot.adoptionEnvironmentBlueprint)
            val row = snapshot.entities.single { it.blueprint == "api_adoption" }
            assertEquals("v2", row.scalarProperties["major_line"])
            assertEquals("2026-09-22T10:15:30+02:00", row.scalarProperties["verified_at"])
            assertFalse(row.scalarProperties.containsKey("secret"))
            assertEquals(ToadieAdoptionKind.API_MAJOR_LINE, adoption.kind)
        }
        f.requests.clear()
        val environmentReached = AtomicBoolean(false)
        f.edit = { request, root ->
            if (request.path("variables").path("blueprint").asText() == "environment") environmentReached.set(true)
            if (environmentReached.get()) (root.path("data").elements().next() as ObjectNode).put("revision", "8")
        }
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val restarted = runBlocking { client.fetch(config(f).copy(adoptionMapping = adoption)) } as ToadieSnapshot
            assertEquals(8, restarted.revision, "environment pages participate in the bounded revision restart")
            assertTrue(f.requests.size > 14)
        }

        f.edit = { _, _ -> }
        val adoptionEntity = f.entityRows!!.single { it.path("blueprint").asText() == "api_adoption" } as ObjectNode
        val properties = adoptionEntity.path("properties") as ObjectNode
        val relations = adoptionEntity.path("relations") as ObjectNode
        for (invalidTimestamp in listOf("not-a-date", "+999999-01-01T00:00:00Z", "-0001-01-01T00:00:00Z")) {
            properties.put("verified_at", invalidTimestamp)
            HttpToadieGraphqlClient().use { client ->
                val failure = assertFailsWith<ToadieFetchException> {
                    runBlocking { client.fetch(config(f).copy(adoptionMapping = adoption)) }
                }
                assertEquals("INVALID_RESPONSE", failure.code)
            }
        }
        properties.put("verified_at", "2026-09-22T10:15:30+02:00")
        properties.put("status", 42)
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking { client.fetch(config(f).copy(adoptionMapping = adoption)) }
            }
            assertEquals("INVALID_RESPONSE", failure.code)
        }
        properties.put("status", "declared")
        relations.put("api", "missing")
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking { client.fetch(config(f).copy(adoptionMapping = adoption)) }
            }
            assertEquals("INVALID_RESPONSE", failure.code)
        }
    }

    @Test
    fun `missing optional adoption blueprint succeeds while malformed present adoption fails`() = fixture { f ->
        HttpToadieGraphqlClient().use { client ->
            val snapshot = runBlocking {
                client.fetch(config(f).copy(adoptionMapping = ToadieAdoptionMapping()))
            } as ToadieSnapshot
            assertEquals(ToadieAdoptionAvailability.BLUEPRINT_MISSING, snapshot.adoptionAvailability)
            assertTrue(f.requests.none { it.path("variables").path("blueprint").asText() == "api_adoption" })
        }

        f.requests.clear()
        f.blueprintRows = mapper.readTree(blueprints).toList() + mapper.readTree("""[
            {"id":"7","identifier":"api_adoption","relations":{
              "consumer":{"target":"service","many":false},"api":{"target":"api","many":false}},
             "schema":{"properties":{"major_line":{"type":"number"},"status":{"type":"string"},
              "declared_by":{"type":"string"},"verified_at":{"type":"string"},"notes":{"type":"string"}}}}
        ]""").toList()
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking { client.fetch(config(f).copy(adoptionMapping = ToadieAdoptionMapping())) }
            }
            assertEquals("MAPPING_INVALID", failure.code)
        }

        f.blueprintRows = mapper.readTree(blueprints).toList()
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking {
                    client.fetch(config(f).copy(adoptionMapping = ToadieAdoptionMapping(blueprint = "system")))
                }
            }
            assertEquals("MAPPING_INVALID", failure.code)
        }

        f.blueprintRows = mapper.readTree(blueprints).toList() + mapper.readTree("""[
            {"id":"7","identifier":"api_adoption","relations":{
              "consumer":{"target":"service","many":false},"api":{"target":"api","many":false},
              "environment":{"target":"service","many":false}},
             "schema":{"properties":{"major_line":{"type":"string"},"status":{"type":"string"},
              "declared_by":{"type":"string"},"verified_at":{"type":"string"},"notes":{"type":"string"}}}}
        ]""").toList()
        HttpToadieGraphqlClient().use { client ->
            for (mapping in listOf(
                ToadieAdoptionMapping(),
                ToadieAdoptionMapping(environmentRelation = "consumer"),
            )) {
                val failure = assertFailsWith<ToadieFetchException> {
                    runBlocking { client.fetch(config(f).copy(adoptionMapping = mapping)) }
                }
                assertEquals("MAPPING_INVALID", failure.code)
            }
        }

        f.blueprintRows = mapper.readTree(blueprints).toList() + mapper.readTree("""[
            {"id":"6","identifier":"environment","relations":{}},
            {"id":"7","identifier":"ENVIRONMENT","relations":{}},
            {"id":"8","identifier":"api_adoption","relations":{
              "consumer":{"target":"service","many":false},"api":{"target":"api","many":false},
              "environment":{"target":"environment","many":false}},
             "schema":{"properties":{"major_line":{"type":"string"},"status":{"type":"string"},
              "declared_by":{"type":"string"},"verified_at":{"type":"string"},"notes":{"type":"string"}}}}
        ]""").toList()
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking { client.fetch(config(f).copy(adoptionMapping = ToadieAdoptionMapping())) }
            }
            assertEquals("MAPPING_INVALID", failure.code)
        }

        f.blueprintRows = mapper.readTree(blueprints).toList() + mapper.readTree("""[
            {"id":"7","identifier":"api_adoption","relations":{},"schema":{}},
            {"id":"8","identifier":"API_ADOPTION","relations":{},"schema":{}}
        ]""").toList()
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> {
                runBlocking { client.fetch(config(f).copy(adoptionMapping = ToadieAdoptionMapping())) }
            }
            assertEquals("MAPPING_INVALID", failure.code)
        }
    }

    @Test
    fun `dataset mapping reads explicit service relations without scanning APIs or database resources`() = fixture { f ->
        f.blueprintRows = mapper.readTree(blueprints).toList().map { it as ObjectNode }.onEach {
            if (it.path("identifier").asText() == "service") {
                val relations = it.path("relations") as ObjectNode
                relations.set<JsonNode>("produces_datasets", mapper.readTree("""{"target":"dataset","many":true}"""))
                relations.set<JsonNode>("consumes_datasets", mapper.readTree("""{"target":"dataset","many":true}"""))
                relations.set<JsonNode>("depends_on", mapper.readTree("""{"target":"resource","many":true}"""))
            }
        } + mapper.readTree("""[
            {"id":"6","identifier":"dataset","relations":{}},
            {"id":"7","identifier":"resource","relations":{}}
        ]""").toList()
        f.entityRows = mapper.readTree(entities).toList().map { it as ObjectNode }.onEach {
            if (it.path("identifier").asText() == "checkout") {
                val relations = it.path("relations") as ObjectNode
                relations.set<JsonNode>("produces_datasets", mapper.readTree("""["orders","settlements"]"""))
                relations.set<JsonNode>("consumes_datasets", mapper.readTree("""["orders"]"""))
                relations.set<JsonNode>("depends_on", mapper.readTree("""["warehouse"]"""))
            }
        } + mapper.readTree("""[
            {"id":"6","blueprint":"dataset","identifier":"orders","title":"Orders dataset","relations":{},"updatedAt":1},
            {"id":"7","blueprint":"dataset","identifier":"settlements","title":"Settlements","relations":{},"updatedAt":1},
            {"id":"8","blueprint":"resource","identifier":"warehouse","title":"Warehouse","relations":{},"updatedAt":1}
        ]""").toList()
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val mapping = ToadieMapping(apiBlueprint = "dataset",
                providesRelation = "produces_datasets", consumesRelation = "consumes_datasets")
            val snapshot = runBlocking { client.fetch(config(f).copy(mapping = mapping)) } as ToadieSnapshot
            assertEquals(setOf("dataset", "service", "system", "_team"), snapshot.entities.map { it.blueprint }.toSet())
            assertEquals(setOf("6", "7"), snapshot.entities.filter { it.blueprint == "dataset" }.map { it.id }.toSet())
            val service = snapshot.entities.single { it.blueprint == "service" }
            assertEquals(listOf("orders", "settlements"), service.relations["produces_datasets"])
            assertEquals(listOf("orders"), service.relations["consumes_datasets"])
            assertEquals(setOf("produces_datasets", "consumes_datasets", "system"), service.relations.keys)
            assertEquals(setOf("dataset", "service", "system", "_team"),
                f.requests.mapNotNull { it.path("variables").path("blueprint").textValue() }.toSet())
        }
    }

    @Test
    fun `dataset adoption accepts custom mapped names and absent optional scalar values`() = fixture { f ->
        f.blueprintRows = mapper.readTree(blueprints).toList().map { it as ObjectNode }.onEach {
            if (it.path("identifier").asText() == "service") {
                val relations = it.path("relations") as ObjectNode
                relations.set<JsonNode>("produces_datasets", mapper.readTree("""{"target":"dataset","many":true}"""))
                relations.set<JsonNode>("consumes_datasets", mapper.readTree("""{"target":"dataset","many":true}"""))
            }
        } + mapper.readTree("""[
            {"id":"6","identifier":"dataset","relations":{}},
            {"id":"7","identifier":"custom_dataset_adoption","relations":{
              "used_by":{"target":"service","many":false},"dataset_ref":{"target":"dataset","many":false}},
             "schema":{"properties":{"declared_version":{"type":"string"}}}}
        ]""").toList()
        f.entityRows = mapper.readTree(entities).toList().map { it as ObjectNode }.onEach {
            if (it.path("identifier").asText() == "checkout") {
                val relations = it.path("relations") as ObjectNode
                relations.set<JsonNode>("produces_datasets", mapper.readTree("""["orders"]"""))
                relations.set<JsonNode>("consumes_datasets", mapper.readTree("""[]"""))
            }
        } + mapper.readTree("""[
            {"id":"6","blueprint":"dataset","identifier":"orders","title":"Orders dataset","relations":{},"updatedAt":1},
            {"id":"7","blueprint":"custom_dataset_adoption","identifier":"checkout-orders","title":"Checkout Orders",
             "relations":{"used_by":"checkout","dataset_ref":"orders"},"properties":{},"updatedAt":1}
        ]""").toList()
        val usage = ToadieMapping(
            apiBlueprint = "dataset", providesRelation = "produces_datasets", consumesRelation = "consumes_datasets",
        )
        val adoption = ToadieAdoptionMapping(
            blueprint = "custom_dataset_adoption", kind = ToadieAdoptionKind.DATASET_CONTRACT_VERSION,
            consumerRelation = "used_by", targetRelation = "dataset_ref", environmentRelation = null,
            valueProperty = "declared_version", statusProperty = null, declaredByProperty = null,
            verifiedAtProperty = null, notesProperty = null,
        )
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val snapshot = runBlocking {
                client.fetch(config(f).copy(mapping = usage, adoptionMapping = adoption))
            } as ToadieSnapshot
            val row = snapshot.entities.single { it.blueprint == "custom_dataset_adoption" }
            assertEquals(mapOf("declared_version" to null), row.scalarProperties)
            assertEquals(ToadieAdoptionAvailability.AVAILABLE, snapshot.adoptionAvailability)
            assertEquals(null, snapshot.adoptionEnvironmentBlueprint)
        }
    }

    private fun registryFixture(f: Fixture) {
        f.blueprintRows = mapper.readTree(blueprints).toList().map { it as ObjectNode }.onEach {
            it.set<JsonNode>("schema", mapper.readTree("""{"properties":{"description":{"type":"string"}}}"""))
            when (it.path("identifier").asText()) {
                "system" -> it.set<JsonNode>("relations", mapper.readTree("""{"domain":{"target":"domain","many":false}}"""))
                "_team" -> it.set<JsonNode>("relations", mapper.readTree("""{"parent":{"target":"_team","many":false}}"""))
            }
        } + listOf(mapper.readTree("""{"id":"5","identifier":"domain","schema":{"properties":{"description":{"type":"string"}}},
            "relations":{"parent_domain":{"target":"domain","many":false}}}"""))
        f.entityRows = mapper.readTree(entities).toList().map { it as ObjectNode }.onEach {
            it.set<JsonNode>("properties", mapper.readTree("""{"description":"Selected description","secret":"never retained"}"""))
            if (it.path("blueprint").asText() == "system") {
                it.set<JsonNode>("relations", mapper.readTree("""{"domain":"commerce"}"""))
            }
        } + listOf(mapper.readTree("""{"id":"6","blueprint":"domain","identifier":"commerce","title":"Commerce domain",
            "relations":{},"properties":{"description":"Domain description"},"updatedAt":1}"""))
    }

    private fun registryConfig(f: Fixture) = config(f).copy(registryMapping = ToadieRegistryMapping(
        flattenDomains = true, domainDescriptionProperty = "description", teamDescriptionProperty = "description",
    ))

    @Test
    fun `registry projection reads domain pages and only selected description properties`() = fixture { f ->
        registryFixture(f)
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val snapshot = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
            assertEquals(6, snapshot.entities.size)
            val domain = snapshot.entities.single { it.blueprint == "domain" }
            assertEquals("Domain description", domain.registryDescription)
            val system = snapshot.entities.single { it.blueprint == "system" }
            assertEquals(listOf("commerce"), system.relations["domain"])
            assertEquals(null, system.registryDescription)
            assertEquals("Selected description", snapshot.entities.single { it.blueprint == "_team" }.registryDescription)
            assertFalse(snapshot.toString().contains("never retained"))
            val propertyReads = f.requests.filter { it.path("query").asText().contains(" properties") }
            assertEquals(setOf("domain", "_team"), propertyReads.map { it.path("variables").path("blueprint").asText() }.toSet())
        }
    }

    @Test
    fun `invalid selected descriptions become row conflicts without discarding usage`() = fixture { f ->
        registryFixture(f)
        val domain = f.entityRows!!.last() as ObjectNode
        for (description in listOf(mapper.valueToTree<JsonNode>(42), mapper.valueToTree("x".repeat(2001)))) {
            (domain.path("properties") as ObjectNode).set<JsonNode>("description", description)
            HttpToadieGraphqlClient().use { client ->
                val snapshot = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
                assertEquals(6, snapshot.entities.size)
                assertEquals("INVALID_DESCRIPTION", snapshot.entities.last().registryErrorCode)
                assertEquals(null, snapshot.entities.last().registryDescription)
            }
        }
        (domain.path("properties") as ObjectNode).putNull("description")
        HttpToadieGraphqlClient().use { client ->
            val snapshot = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
            assertEquals(null, snapshot.entities.last().registryErrorCode)
        }
    }

    @Test
    fun `registry mappings reject undeclared properties ambiguous roles and invalid relations`() = fixture { f ->
        registryFixture(f)
        val valid = registryConfig(f).registryMapping!!
        val invalid = listOf(
            valid.copy(domainDescriptionProperty = "secret"), valid.copy(domainBlueprint = "api"),
            valid.copy(systemDomainRelation = "unknown"), valid.copy(domainParentRelation = "unknown"),
            valid.copy(flattenDomains = false),
        )
        HttpToadieGraphqlClient().use { client ->
            invalid.forEach { mapping ->
                val failure = assertFailsWith<ToadieFetchException> {
                    runBlocking { client.fetch(config(f).copy(registryMapping = mapping)) }
                }
                assertEquals("MAPPING_INVALID", failure.code)
            }
            val roleCollision = assertFailsWith<ToadieFetchException> {
                runBlocking {
                    client.fetch(registryConfig(f).copy(adoptionMapping = ToadieAdoptionMapping(blueprint = "domain")))
                }
            }
            assertEquals("MAPPING_INVALID", roleCollision.code)
        }
    }

    @Test
    fun `malformed property containers cannot erase a mapped description`() = fixture { f ->
        registryFixture(f)
        val domain = f.entityRows!!.last() as ObjectNode
        HttpToadieGraphqlClient().use { client ->
            for (json in listOf("null", "[]", "42", "\"text\"")) {
                domain.set<JsonNode>("properties", mapper.readTree(json))
                val snapshot = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
                assertEquals("INVALID_DESCRIPTION", snapshot.entities.last().registryErrorCode)
            }
            domain.remove("properties")
            val missing = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
            assertEquals("INVALID_DESCRIPTION", missing.entities.last().registryErrorCode)
            domain.set<JsonNode>("properties", mapper.readTree("{}"))
            val empty = runBlocking { client.fetch(registryConfig(f)) } as ToadieSnapshot
            assertEquals(null, empty.entities.last().registryErrorCode)
        }
    }

    @Test
    fun `registry reference and revision validation covers added domain rows`() = fixture { f ->
        registryFixture(f)
        f.edit = { request, root ->
            if (request.path("variables").path("blueprint").asText() == "domain") {
                (root.path("data").path("entities") as ObjectNode).put("revision", "8")
            }
        }
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> { runBlocking { client.fetch(registryConfig(f)) } }
            assertEquals("SOURCE_CHANGED", failure.code)
        }
        f.edit = { _, _ -> }
        val domain = f.entityRows!!.last() as ObjectNode
        (domain.path("relations") as ObjectNode).put("parent_domain", "missing")
        HttpToadieGraphqlClient().use { client ->
            val failure = assertFailsWith<ToadieFetchException> { runBlocking { client.fetch(registryConfig(f)) } }
            assertEquals("INVALID_RESPONSE", failure.code)
        }
    }

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
            val snapshot = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
            assertEquals(5, snapshot.entities.size)
            assertEquals("system", snapshot.systemBlueprint)
            val service = snapshot.entities.single { it.blueprint == "service" }
            assertEquals(listOf("orders", "events"), service.relations["provides_apis"])
            assertEquals(listOf("events"), service.relations["consumes_apis"])
            assertEquals(listOf("retail"), service.teamIdentifiers)
            assertEquals(listOf("retail"), snapshot.entities.single { it.blueprint == "system" }.teamIdentifiers)
            assertEquals(10, f.requests.size)
            assertTrue(f.auth.all { it == "Bearer private-fixture-key" })
            assertTrue(f.requests.all { it.path("operationName").asText() == "CovenantUsage" })
            assertTrue(f.requests.none { it.path("query").asText().contains("properties") })
        }
    }

    @Test
    fun `saved revision shortcut verifies remotely while forced full scan bypasses it`() = fixture { f ->
        HttpToadieGraphqlClient().use { client ->
            val unchanged = runBlocking { client.fetch(config(f).copy(knownRevision = 7)) }
            assertTrue(unchanged is ToadieUnchanged)
            assertEquals(7, unchanged.revision)
            assertEquals(1, f.requests.size)

            f.requests.clear()
            val full = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
            assertEquals(7, full.revision)
            assertTrue(f.requests.size > 1)
        }
    }

    @Test
    fun `restarts one full scan when revisions change between same-sized pages`() = fixture { f ->
        f.edit = { _, root ->
            if (f.requests.size >= 2) {
                val page = root.path("data").elements().next() as ObjectNode
                page.put("revision", "8")
                if (f.requests.size == 2) page.put("total", 5)
            }
        }
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val full = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
            assertEquals(8, full.revision)
            assertTrue(f.requests.size > 10)
        }
    }

    @Test
    fun `detects a revision change at an entity type boundary including an empty page`() = fixture { f ->
        val changed = AtomicBoolean(false)
        f.edit = { request, root ->
            val page = root.path("data").elements().next() as ObjectNode
            val blueprint = request.path("variables").path("blueprint").textValue()
            if (blueprint == "service") {
                page.path("items").forEach { (it as ObjectNode).putNull("team") }
            }
            if (blueprint == "_team") {
                page.putArray("items")
                page.put("total", 0)
                changed.set(true)
            }
            if (changed.get()) page.put("revision", "8")
        }
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val full = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
            assertEquals(8, full.revision)
            assertTrue(full.entities.none { it.blueprint == "_team" })
        }
    }

    @Test
    fun `a restarted scan shares the original decoded-row budget`() = fixture { f ->
        HttpToadieGraphqlClient(pageSize = 1, maxEntities = 9).use { client ->
            assertTrue(runBlocking { client.fetch(config(f)) } is ToadieSnapshot)
        }
        f.requests.clear()
        f.edit = { _, root ->
            if (f.requests.size >= 2) (root.path("data").elements().next() as ObjectNode).put("revision", "8")
        }
        assertFailure(f, "LIMIT_EXCEEDED", HttpToadieGraphqlClient(pageSize = 1, maxEntities = 9))
    }

    @Test
    fun `final revision probe restarts a scan changed after its last entity page`() = fixture { f ->
        f.edit = { request, root ->
            val revisionOnly = request.path("query").asText().contains("{ revision }")
            if (revisionOnly || f.requests.size > 10) {
                (root.path("data").elements().next() as ObjectNode).put("revision", "8")
            }
        }
        HttpToadieGraphqlClient(pageSize = 1).use { client ->
            val full = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
            assertEquals(8, full.revision)
        }
    }

    @Test
    fun `rejects sustained revision churn and malformed revision values`() = fixture { f ->
        f.edit = { _, root ->
            (root.path("data").elements().next() as ObjectNode).put("revision", if (f.requests.size % 2 == 0) "8" else "7")
        }
        assertFailure(f, "SOURCE_CHANGED", HttpToadieGraphqlClient(pageSize = 1))

        f.requests.clear()
        f.edit = { _, root -> (root.path("data").elements().next() as ObjectNode).remove("revision") }
        assertFailure(f, "INVALID_RESPONSE")
        for (revision in listOf("", "01", "-1", "9223372036854775808", "not-a-number")) {
            f.requests.clear()
            f.edit = { _, root -> (root.path("data").elements().next() as ObjectNode).put("revision", revision) }
            assertFailure(f, "INVALID_RESPONSE")
        }
    }

    @Test
    fun `canonicalizes blueprint configuration while preserving exact relation identities`() = fixture { f ->
        HttpToadieGraphqlClient().use { client ->
            val result = runBlocking {
                client.fetch(config(f).copy(mapping = ToadieMapping(serviceBlueprint = "SERVICE")))
            } as ToadieSnapshot
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
            val snapshot = runBlocking { client.fetch(config(f)) } as ToadieSnapshot
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
