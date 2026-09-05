package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.tryit.TryCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The try catalog read off the parsed documents: operations, channels/messages, datasets. */
class TryCatalogTest {

    private fun root(text: String) = (DocumentParser.parse(text) as ParseOutcome.Parsed).root

    private val petstore = """
        openapi: 3.0.3
        info: { title: Pets, version: 1.0.0 }
        components:
          securitySchemes:
            bearer: { type: http, scheme: bearer }
            key: { type: apiKey, in: header, name: X-Api-Key }
            cookieKey: { type: apiKey, in: cookie, name: session }
          parameters:
            Tenant: { name: X-Tenant, in: header, required: true, schema: { type: string } }
          requestBodies:
            NewPet:
              required: true
              content:
                application/json: { schema: { type: object } }
        paths:
          /pets/{id}:
            summary: One pet
            parameters:
              - { name: id, in: path, schema: { type: integer } }
              - ${'$'}ref: "#/components/parameters/Tenant"
            get:
              operationId: getPet
              parameters:
                - { name: verbose, in: query, schema: { type: boolean } }
              responses:
                "200": { description: ok }
                "404": { description: gone }
            put:
              operationId: replacePet
              requestBody: { ${'$'}ref: "#/components/requestBodies/NewPet" }
              parameters:
                - { name: id, in: path, required: true, schema: { type: string }, description: overrides the shared one }
              responses:
                default: { description: any }
    """.trimIndent()

    @Test
    fun `OpenAPI - every operation with merged parameters, request body media types, response keys and security headers`() {
        val catalog = TryCatalog.build(ContractType.OPENAPI, root(petstore))
        assertEquals(ContractType.OPENAPI, catalog.type)
        assertEquals(listOf("GET", "PUT"), catalog.http.map { it.method })
        val get = catalog.http.single { it.method == "GET" }
        assertEquals("getPet", get.operationId)
        assertEquals("/pets/{id}", get.path)
        assertEquals("One pet", get.summary, "the path item's summary is inherited")
        assertEquals(listOf("verbose" to "query", "id" to "path", "X-Tenant" to "header"), get.parameters.map { it.name to it.location })
        assertTrue(get.parameters.single { it.name == "id" }.required, "path parameters are always required")
        assertEquals("integer", get.parameters.single { it.name == "id" }.type)
        assertEquals(null, get.requestBody)
        assertEquals(listOf("200", "404"), get.responses)
        assertEquals(listOf("Authorization", "X-Api-Key"), get.securityHeaders, "the cookie key is not a header")
        val put = catalog.http.single { it.method == "PUT" }
        assertEquals("string", put.parameters.single { it.name == "id" }.type, "the operation's own parameter wins over the shared one")
        assertEquals(2, put.parameters.size)
        assertNotNull(put.requestBody)
        assertTrue(put.requestBody!!.required)
        assertEquals(listOf("application/json"), put.requestBody!!.mediaTypes)
        assertEquals(listOf("default"), put.responses)
        assertTrue(catalog.kafka.isEmpty() && catalog.sql.isEmpty())
    }

    @Test
    fun `AsyncAPI 3 - channels with address, verbs from the operations and messages with their payload pointers`() {
        val catalog = TryCatalog.build(ContractType.ASYNCAPI, root(ContractFixtures.asyncApi3))
        val channel = catalog.kafka.single()
        assertEquals("lightMeasured", channel.channel)
        assertEquals("smartylighting.streetlights.1.0.event.lighting.measured", channel.address)
        assertEquals(listOf("receive"), channel.actions)
        val message = channel.messages.single()
        assertEquals("lightMeasured", message.name)
        assertEquals("application/json", message.contentType)
        assertEquals("/components/messages/lightMeasured/payload", message.payloadPointer, "the ${'$'}ref'd message's payload")
    }

    @Test
    fun `AsyncAPI 2 - the channel key is the address, publish and subscribe are the verbs`() {
        val catalog = TryCatalog.build(ContractType.ASYNCAPI, root(ContractFixtures.asyncApi2))
        val channel = catalog.kafka.single()
        assertEquals(channel.channel, channel.address)
        assertTrue(channel.actions.all { it == "publish" || it == "subscribe" } && channel.actions.isNotEmpty())
        assertTrue(channel.messages.all { it.payloadPointer != null }, channel.messages.toString())
        val avro = TryCatalog.build(ContractType.ASYNCAPI, root(ContractFixtures.asyncApiAvro)).kafka.flatMap { it.messages }
        assertTrue(avro.any { it.schemaFormat?.contains("avro") == true }, avro.toString())
    }

    private val odd = """
        openapi: 3.0.3
        info: { title: Odd, version: 1.0.0 }
        components:
          securitySchemes:
            oauth: { type: oauth2, flows: {} }
            oidc: { type: openIdConnect, openIdConnectUrl: https://example.com }
            mutual: { type: mutualTLS }
            queryKey: { type: apiKey, in: query, name: k }
        paths:
          /odd:
            get:
              parameters:
                - { in: query, schema: { type: string } }
                - { name: nameless-location }
                - { ${'$'}ref: "https://example.com/external.yaml#/p" }
              requestBody: null
              responses: {}
            trace: { responses: {} }
    """.trimIndent()

    private val asyncOdd = """
        asyncapi: 2.6.0
        info: { title: Odd, version: 1.0.0 }
        channels:
          plain:
            publish:
              message:
                oneOf:
                  - { name: Named, payload: { type: object } }
                  - title: Titled
                    contentType: text/plain
                    payload: { type: string }
                    schemaFormat: application/schema+json;version=draft-07
                  - { ${'$'}ref: "#/components/messages/Referenced" }
                  - { payload: { type: integer } }
            subscribe:
              message: { ${'$'}ref: "#/components/messages/Referenced" }
          empty: {}
        components:
          messages:
            Referenced:
              payload: { type: object }
    """.trimIndent()

    private val async3Odd = """
        asyncapi: 3.0.0
        info: { title: Odd, version: 1.0.0 }
        channels:
          inline:
            messages:
              direct: { payload: { type: object } }
          unaddressed: {}
        operations:
          sendIt: { action: send, channel: { ${'$'}ref: "#/channels/inline" } }
          elsewhere: { action: receive, channel: { ${'$'}ref: "#/channels/unaddressed" } }
          headless: { channel: { ${'$'}ref: "#/channels/inline" } }
    """.trimIndent()

    @Test
    fun `edge shapes - nameless parameters and datasets are skipped, external refs stay opaque, every message naming rule`() {
        val http = TryCatalog.build(ContractType.OPENAPI, root(odd)).http
        val get = http.single { it.method == "GET" }
        assertEquals(emptyList(), get.parameters, "nameless, location-less or externally referenced parameters offer nothing")
        assertEquals(null, get.requestBody)
        assertEquals(emptyList(), get.responses)
        assertEquals(listOf("Authorization"), get.securityHeaders, "oauth2/openIdConnect fold into one header, the rest none")
        assertTrue(http.none { it.method == "TRACE" }, "TRACE is never offered")

        val v2 = TryCatalog.build(ContractType.ASYNCAPI, root(asyncOdd)).kafka
        val plain = v2.single { it.channel == "plain" }
        assertEquals(listOf("publish", "subscribe"), plain.actions)
        assertEquals(listOf("Named", "Titled", "Referenced", "publish-3"), plain.messages.map { it.name })
        assertEquals("/components/messages/Referenced/payload", plain.messages[2].payloadPointer)
        assertEquals("application/schema+json;version=draft-07", plain.messages[1].schemaFormat)
        assertEquals("text/plain", plain.messages[1].contentType)
        val empty = v2.single { it.channel == "empty" }
        assertTrue(empty.actions.isEmpty() && empty.messages.isEmpty())

        val v3 = TryCatalog.build(ContractType.ASYNCAPI, root(async3Odd)).kafka
        val inline = v3.single { it.channel == "inline" }
        assertEquals("inline", inline.address, "no address falls back to the key")
        assertEquals(listOf("send"), inline.actions, "an operation without an action contributes nothing")
        assertEquals("/channels/inline/messages/direct/payload", inline.messages.single().payloadPointer)
        assertEquals(listOf("receive"), v3.single { it.channel == "unaddressed" }.actions)

        val sql = TryCatalog.build(
            ContractType.ODCS,
            root("schema:\n  - { physicalType: table }\n  - { name: t, properties: [ { logicalType: string }, { name: ok } ] }\n"),
        ).sql
        assertEquals(listOf("t"), sql.map { it.name })
        assertEquals(listOf("ok"), sql.single().properties.map { it.name })
        assertEquals(null, sql.single().properties.single().logicalType)
    }

    @Test
    fun `ODCS - datasets with their properties - a broken tree offers nothing rather than failing`() {
        val catalog = TryCatalog.build(ContractType.ODCS, root(ContractFixtures.odcs))
        val dataset = catalog.sql.single()
        assertEquals("customer_view", dataset.name)
        assertEquals("view", dataset.physicalType)
        val id = dataset.properties.single()
        assertEquals("customer_id", id.name)
        assertEquals("string", id.logicalType)
        assertEquals("uuid", id.physicalType)
        assertTrue(id.required)
        assertEquals(emptyList(), TryCatalog.build(ContractType.ODCS, root("schema: nope\n")).sql)
        assertEquals(emptyList(), TryCatalog.build(ContractType.OPENAPI, root("paths: [1, 2]\n")).http)
    }
}
