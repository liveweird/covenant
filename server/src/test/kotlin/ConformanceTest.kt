package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.tryit.Conformance
import ch.nokillswit.contracts.tryit.DeclaredColumn
import ch.nokillswit.contracts.tryit.DocumentSchemas
import ch.nokillswit.contracts.tryit.HttpConformance
import ch.nokillswit.contracts.tryit.OdcsTypes
import ch.nokillswit.contracts.tryit.PayloadConformance
import ch.nokillswit.contracts.tryit.SqlColumnMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live conformance: response/request bodies, message payloads and table columns against the document's own schemas. */
class ConformanceTest {

    private fun root(text: String) = (DocumentParser.parse(text) as ParseOutcome.Parsed).root

    private val petstore30 = """
        openapi: 3.0.3
        info: { title: Pets, version: 1.0.0 }
        paths:
          /pets/{id}:
            get:
              responses:
                "200":
                  description: ok
                  headers:
                    X-Request-Id: { schema: { type: string } }
                  content:
                    application/json:
                      schema: { ${'$'}ref: "#/components/schemas/Pet" }
                "4XX":
                  description: client error
                  content:
                    application/problem+json:
                      schema: { type: object, required: [title], properties: { title: { type: string } } }
            post:
              requestBody:
                required: true
                content:
                  application/json: { schema: { ${'$'}ref: "#/components/schemas/Pet" } }
              responses:
                default: { description: anything }
        components:
          schemas:
            Pet:
              type: object
              required: [id, name]
              properties:
                id: { type: integer }
                name: { type: string, nullable: true }
    """.trimIndent()

    private val headers = mapOf("Content-Type" to "application/json", "x-request-id" to "abc")
    private val json = "application/json"
    private val avroFormat = "application/vnd.apache.avro;version=1.9.0"

    private fun response(
        doc: com.fasterxml.jackson.databind.JsonNode,
        schemas: DocumentSchemas,
        status: Int,
        contentType: String?,
        body: String?,
        responseHeaders: Map<String, String> = headers,
        method: String = "GET",
        path: String = "/pets/{id}",
    ) = HttpConformance.assessResponse(doc, schemas, method, path, status, contentType, body, responseHeaders)

    private fun request(doc: com.fasterxml.jackson.databind.JsonNode, schemas: DocumentSchemas, method: String, body: String?) =
        HttpConformance.assessRequest(doc, schemas, method, "/pets/{id}", json, body)

    @Test
    fun `OpenAPI 3-0 - a conforming body is clean, a schema breach names the property, refs resolve in-document, nullable is honoured`() {
        val doc = root(petstore30)
        val schemas = DocumentSchemas.of(ContractType.OPENAPI, doc)
        val clean = response(doc, schemas, 200, json, """{"id": 1, "name": null}""")
        assertEquals(emptyList(), clean.findings, clean.toString())
        assertEquals("/paths/~1pets~1{id}/get/responses/200/content/application~1json/schema", clean.validatedAgainst)
        val broken = response(doc, schemas, 200, "application/json; charset=utf-8", """{"id": "x"}""")
        val codes = broken.findings.map { it.code }
        assertTrue(codes.all { it == Conformance.RESPONSE_SCHEMA_MISMATCH }, codes.toString())
        assertTrue(broken.findings.any { it.path == "/body/id" }, broken.findings.toString())
        assertTrue(broken.findings.any { it.message.contains("name") }, "the missing required property")
        assertEquals(2, broken.errors)
    }

    @Test
    fun `OpenAPI - undeclared status, status ranges, undeclared media type, non-JSON bodies, missing headers`() {
        val doc = root(petstore30)
        val schemas = DocumentSchemas.of(ContractType.OPENAPI, doc)
        val outage = response(doc, schemas, 503, "text/plain", "later")
        assertEquals(listOf(Conformance.STATUS_UNDECLARED), outage.findings.map { it.code })
        val teapot = response(doc, schemas, 418, "text/plain", "short and stout")
        assertEquals(listOf(Conformance.MEDIA_TYPE_UNDECLARED), teapot.findings.map { it.code }, "4XX covers 418, text/plain is undeclared")
        val notFound = response(doc, schemas, 404, "application/problem+json", """{"title": "gone"}""")
        assertEquals(emptyList(), notFound.findings, "4XX covers 404 and +json matches the declared problem media type")
        val xml = response(doc, schemas, 200, "application/xml", "<pet/>")
        assertEquals(listOf(Conformance.MEDIA_TYPE_UNDECLARED), xml.findings.map { it.code })
        val notJson = response(doc, schemas, 200, json, "{not json")
        assertEquals(listOf(Conformance.RESPONSE_NOT_JSON), notJson.findings.map { it.code })
        val noHeader = response(doc, schemas, 200, json, """{"id": 1, "name": "x"}""", emptyMap())
        assertEquals(listOf(Conformance.RESPONSE_HEADER_MISSING), noHeader.findings.map { it.code })
        assertEquals(Severity.WARN, noHeader.findings.single().severity)
        val anything = response(doc, schemas, 503, "text/plain", "later", emptyMap(), method = "POST")
        assertEquals(emptyList(), anything.findings, "`default` covers every status and declares no content")
    }

    @Test
    fun `OpenAPI - the request body is checked before sending, as warnings`() {
        val doc = root(petstore30)
        val schemas = DocumentSchemas.of(ContractType.OPENAPI, doc)
        val missing = request(doc, schemas, "POST", null)
        assertEquals(listOf(Conformance.REQUEST_BODY_MISSING), missing.map { it.code })
        val wrong = request(doc, schemas, "POST", """{"id": "nope", "name": "x"}""")
        assertTrue(wrong.isNotEmpty(), "a mismatch is reported")
        assertTrue(wrong.all { it.code == Conformance.REQUEST_SCHEMA_MISMATCH && it.severity == Severity.WARN }, wrong.toString())
        assertEquals(emptyList(), request(doc, schemas, "GET", """{"x": 1}"""), "no request body declared")
        assertEquals(emptyList(), request(doc, schemas, "POST", """{"id": 1, "name": "ok"}"""))
    }

    @Test
    fun `OpenAPI 3-1 - type arrays validate through the 3-1 dialect`() {
        val doc = root(
            """
            openapi: 3.1.0
            info: { title: T, version: "1" }
            paths:
              /a:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: { type: object, properties: { n: { type: [integer, "null"] } }, required: [n] }
            """.trimIndent(),
        )
        val schemas = DocumentSchemas.of(ContractType.OPENAPI, doc)
        assertEquals(emptyList(), response(doc, schemas, 200, json, """{"n": null}""", emptyMap(), path = "/a").findings)
        val bad = response(doc, schemas, 200, json, """{"n": "s"}""", emptyMap(), path = "/a")
        assertEquals(listOf(Conformance.RESPONSE_SCHEMA_MISMATCH), bad.findings.map { it.code })
    }

    @Test
    fun `AsyncAPI - JSON payloads validate against the message schema, Avro payloads through Avro, other formats are notes`() {
        val doc = root(ContractFixtures.asyncApi3)
        val schemas = DocumentSchemas.of(ContractType.ASYNCAPI, doc)
        val pointer = "/components/messages/lightMeasured/payload"
        assertEquals(emptyList(), PayloadConformance.assess(doc, schemas, pointer, null, """{"lumens": 3}""", "/messages/0/payload"))
        val negative = PayloadConformance.assess(doc, schemas, pointer, null, """{"lumens": -1}""", "/messages/0/payload")
        assertEquals(listOf(Conformance.PAYLOAD_SCHEMA_MISMATCH), negative.map { it.code })
        assertEquals("/messages/0/payload/lumens", negative.single().path)
        val notJson = PayloadConformance.assess(doc, schemas, pointer, null, "nope", "/p")
        assertEquals(listOf(Conformance.PAYLOAD_NOT_JSON), notJson.map { it.code })
        val noPointer = PayloadConformance.assess(doc, schemas, null, null, "{}", "/p")
        assertEquals(listOf(Conformance.PAYLOAD_NOT_VALIDATED), noPointer.map { it.code })
        val proto = PayloadConformance.assess(doc, schemas, pointer, "application/vnd.google.protobuf", "{}", "/p")
        assertEquals(listOf(Conformance.PAYLOAD_NOT_VALIDATED), proto.map { it.code })
        val avroDoc = root(ContractFixtures.asyncApiAvro)
        val avroSchemas = DocumentSchemas.of(ContractType.ASYNCAPI, avroDoc)
        val v3 = avroDoc.path("asyncapi").asText().startsWith("3.")
        val avroPointer = ch.nokillswit.contracts.checks.AsyncApiValidator.payloads(avroDoc, v3).single().pointer
        val avroSchema = avroDoc.at(avroPointer)
        val field = avroSchema.path("fields").first().path("name").asText()
        val okValue = if (avroSchema.path("fields").first().path("type").asText() == "string") "\"x\"" else "1"
        val avroOk = PayloadConformance.assess(avroDoc, avroSchemas, avroPointer, avroFormat, """{"$field": $okValue}""", "/p")
        assertEquals(emptyList(), avroOk)
        val avroBad = PayloadConformance.assess(avroDoc, avroSchemas, avroPointer, avroFormat, """{"unknown": true}""", "/p")
        assertTrue(avroBad.isNotEmpty() && avroBad.all { it.severity == Severity.ERROR }, avroBad.toString())
    }

    @Test
    fun `media types - JSON detection, wildcard content, non-JSON bodies are a note, external response refs stay opaque`() {
        assertTrue(Conformance.isJsonMediaType("Application/JSON; charset=utf-8"))
        assertTrue(Conformance.isJsonMediaType("application/problem+json"))
        assertTrue(Conformance.isJsonMediaType("text/json"))
        assertEquals(false, Conformance.isJsonMediaType("text/plain"))
        assertEquals(false, Conformance.isJsonMediaType(null))
        val doc = root(
            """
            openapi: 3.0.3
            info: { title: T, version: "1" }
            paths:
              /a:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        "*/*": { schema: { type: object } }
                    "201": { ${'$'}ref: "https://example.com/responses.yaml#/Created" }
                    "202":
                      description: schemaless
                      content:
                        application/json: {}
            """.trimIndent(),
        )
        val schemas = DocumentSchemas.of(ContractType.OPENAPI, doc)
        val plain = response(doc, schemas, 200, "text/plain", "hello", emptyMap(), path = "/a")
        assertEquals(listOf(Conformance.BODY_NOT_VALIDATED), plain.findings.map { it.code }, "wildcard admits it, only JSON validates")
        assertEquals(Severity.INFO, plain.findings.single().severity)
        val none = response(doc, schemas, 200, null, "hello", emptyMap(), path = "/a")
        assertEquals(listOf(Conformance.BODY_NOT_VALIDATED), none.findings.map { it.code })
        val wildcardJson = response(doc, schemas, 200, json, """["not", "an", "object"]""", emptyMap(), path = "/a")
        assertEquals(listOf(Conformance.RESPONSE_SCHEMA_MISMATCH), wildcardJson.findings.map { it.code })
        val external = response(doc, schemas, 201, json, "{}", emptyMap(), path = "/a")
        assertEquals(emptyList(), external.findings, "an external ref is followed nowhere - nothing to compare against")
        val schemaless = response(doc, schemas, 202, json, "{}", emptyMap(), path = "/a")
        assertEquals(emptyList(), schemaless.findings)
        assertNull(schemaless.validatedAgainst)
    }

    @Test
    fun `ODCS - the type families, missing and extra columns, required vs nullable`() {
        assertEquals(true, OdcsTypes.matches("string", "varchar"))
        assertEquals(true, OdcsTypes.matches("integer", "int8"))
        assertEquals(false, OdcsTypes.matches("integer", "text"))
        assertEquals(true, OdcsTypes.matches("date", "timestamptz"))
        assertEquals(true, OdcsTypes.matches("array", "_text"))
        assertEquals(false, OdcsTypes.matches("string", "_text"))
        assertNull(OdcsTypes.matches("string", "geometry"))
        assertEquals(true, OdcsTypes.matches("array", "text[]"))
        assertEquals(false, OdcsTypes.matches("array", "text"))
        assertEquals(false, OdcsTypes.matches("mystery", "text"), "an unknown logical type matches nothing known")
        val declared = listOf(
            DeclaredColumn("customer_id", null, "string", true, "/schema/0/properties/0"),
            DeclaredColumn("total", "order_total", "number", false, "/schema/0/properties/1"),
            DeclaredColumn("created", null, "date", true, "/schema/0/properties/2"),
            DeclaredColumn("shape", null, "string", false, "/schema/0/properties/3"),
            DeclaredColumn("untyped", null, null, true, "/schema/0/properties/4"),
        )
        val columns = listOf(
            SqlColumnMeta("customer_id", "uuid", nullable = true),
            SqlColumnMeta("order_total", "int4", nullable = false),
            SqlColumnMeta("shape", "geometry", nullable = true),
            SqlColumnMeta("extra", "text", nullable = true),
            SqlColumnMeta("untyped", "text", nullable = false),
        )
        val findings = OdcsTypes.assess(declared, columns, nullSeen = setOf("customer_id"))
        val byCode = findings.groupBy { it.code }.mapValues { it.value.size }
        assertEquals(1, byCode[OdcsTypes.COLUMN_MISSING], "created")
        assertEquals(1, byCode[OdcsTypes.COLUMN_TYPE_MISMATCH], "order_total is int4, declared number")
        assertEquals(1, byCode[OdcsTypes.COLUMN_TYPE_UNKNOWN], "geometry")
        assertEquals(1, byCode[OdcsTypes.COLUMN_NULLABLE_MISMATCH], "customer_id required but nullable")
        assertEquals(1, byCode[OdcsTypes.REQUIRED_VALUE_NULL], "customer_id null in the sample")
        assertEquals(1, byCode[OdcsTypes.COLUMN_EXTRA], "extra")
        assertEquals("/schema/0/properties/1/logicalType", findings.single { it.code == OdcsTypes.COLUMN_TYPE_MISMATCH }.path)
    }
}
