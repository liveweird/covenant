package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.DEFAULT_MAX_DOCUMENT_BYTES
import ch.nokillswit.contracts.checks.ChecksService
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Metadata
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.infer.DocumentWriter
import ch.nokillswit.contracts.infer.HttpExchangeSample
import ch.nokillswit.contracts.infer.Inference
import ch.nokillswit.contracts.infer.InferRequest
import ch.nokillswit.contracts.infer.MessageBatchSample
import ch.nokillswit.contracts.infer.RelationColumn
import ch.nokillswit.contracts.infer.RelationSample
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-type builder rules, the writer round trip and the document cap — the inference engine's core. */
class InferenceDocumentsTest {

    private fun parses(type: ContractType, content: String) {
        val outcome = DocumentParser.parse(content)
        assertTrue(outcome is ParseOutcome.Parsed, "expected the document to parse: $content")
        assertNull(DocumentParser.typeGate(type, outcome.root), "the type gate must accept the inferred document")
    }

    private fun rootOf(content: String) = (DocumentParser.parse(content) as ParseOutcome.Parsed).root

    private fun zeroSchemaAndSyntax(type: ContractType, content: String) = runBlocking {
        val report = ChecksService(TestChecker.silent).check(type, content)
        val bad = report.findings.filter { it.source == FindingSource.SCHEMA || it.source == FindingSource.SYNTAX }
        assertTrue(bad.isEmpty(), "expected zero SCHEMA/SYNTAX findings, got: $bad\n$content")
    }

    // ---- OpenAPI --------------------------------------------------------------------------

    private val orderExchange = HttpExchangeSample(
        method = "GET",
        url = "https://api.example.test/orders/42",
        requestHeaders = listOf("Authorization"),
        authorizationScheme = "bearer",
        status = 200,
        responseContentType = "application/json",
        responseBody = """{"id":42,"total":19.5,"createdAt":"2026-09-07T10:00:00Z"}""",
        responseHeaders = listOf("X-Api-Key", "Date", "Content-Type"),
    )

    @Test
    fun `an inferred OpenAPI document parses, gates and checks clean`() {
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        parses(ContractType.OPENAPI, response.content)
        zeroSchemaAndSyntax(ContractType.OPENAPI, response.content)
        val metadata = Metadata.extract(ContractType.OPENAPI, rootOf(response.content))
        assertEquals("3.1.0", metadata.specVersion)
    }

    @Test
    fun `OpenAPI - name and version prefill info, else the first host and 1_0_0 fallback`() {
        val named = Inference.build(
            InferRequest(ContractType.OPENAPI, name = "Orders API", version = "2.0.0", http = listOf(orderExchange)),
            DEFAULT_MAX_DOCUMENT_BYTES,
        )
        val namedRoot = rootOf(named.content)
        assertEquals("Orders API", namedRoot.path("info").path("title").asText())
        assertEquals("2.0.0", namedRoot.path("info").path("version").asText())
        assertEquals("", namedRoot.path("info").path("description").asText())

        val unnamed = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val unnamedRoot = rootOf(unnamed.content)
        assertEquals("api.example.test", unnamedRoot.path("info").path("title").asText())
        assertEquals("1.0.0", unnamedRoot.path("info").path("version").asText())
        assertEquals(listOf("https://api.example.test"), unnamedRoot.path("servers").map { it.path("url").asText() })
    }

    @Test
    fun `OpenAPI - an operation without path or query parameters omits the parameters list`() {
        val plain = HttpExchangeSample(method = "GET", url = "https://api.example.test/orders", status = 200)
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(plain)), DEFAULT_MAX_DOCUMENT_BYTES)
        val op = rootOf(response.content).path("paths").path("/orders").path("get")
        assertFalse(op.has("parameters"), "an empty parameters list is noise, not information")
    }

    @Test
    fun `OpenAPI - a numeric path segment is templated with an INFER_PATH_TEMPLATED note`() {
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val root = rootOf(response.content)
        assertTrue(root.path("paths").has("/orders/{orderId}"))
        val op = root.path("paths").path("/orders/{orderId}").path("get")
        assertEquals("getOrdersByOrderId", op.path("operationId").asText())
        assertEquals("orderId", op.path("parameters").single { it.path("in").asText() == "path" }.path("name").asText())
        assertTrue(response.notes.any { it.code == "INFER_PATH_TEMPLATED" })
    }

    @Test
    fun `OpenAPI - a literal path segment is never templated`() {
        val exchange = orderExchange.copy(
            url = "https://api.example.test/orders/alice",
            requestHeaders = emptyList(),
            authorizationScheme = null,
        )
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(exchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val root = rootOf(response.content)
        assertTrue(root.path("paths").has("/orders/alice"))
        assertFalse(response.notes.any { it.code == "INFER_PATH_TEMPLATED" })
    }

    @Test
    fun `OpenAPI - a templated position that is an integer in one exchange and a UUID in another falls back to string`() {
        val intExchange = orderExchange.copy(url = "https://api.example.test/orders/42")
        val uuidExchange = orderExchange.copy(url = "https://api.example.test/orders/123e4567-e89b-12d3-a456-426614174000")
        val response = Inference.build(
            InferRequest(ContractType.OPENAPI, http = listOf(intExchange, uuidExchange)),
            DEFAULT_MAX_DOCUMENT_BYTES,
        )
        val op = rootOf(response.content).path("paths").path("/orders/{orderId}").path("get")
        val param = op.path("parameters").single { it.path("in").asText() == "path" }
        assertEquals("string", param.path("schema").path("type").asText())
    }

    @Test
    fun `OpenAPI - query parameters are required only when every exchange carries them`() {
        val a = orderExchange.copy(query = mapOf("limit" to "10"))
        val b = orderExchange.copy(query = emptyMap())
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(a, b)), DEFAULT_MAX_DOCUMENT_BYTES)
        val op = rootOf(response.content).path("paths").path("/orders/{orderId}").path("get")
        val limitParam = op.path("parameters").single { it.path("name").asText() == "limit" }
        assertFalse(limitParam.path("required").asBoolean())
        assertEquals("integer", limitParam.path("schema").path("type").asText())
    }

    @Test
    fun `OpenAPI - a request body infers a schema when JSON, else an INFER_BODY_NOT_JSON note`() {
        val json = orderExchange.copy(method = "POST", requestContentType = "application/json", requestBody = """{"total":5}""")
        val jsonResponse = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(json)), DEFAULT_MAX_DOCUMENT_BYTES)
        val jsonOp = rootOf(jsonResponse.content).path("paths").path("/orders/{orderId}").path("post")
        assertTrue(jsonOp.path("requestBody").path("content").has("application/json"))

        val text = orderExchange.copy(method = "POST", requestContentType = "text/plain", requestBody = "hello")
        val textResponse = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(text)), DEFAULT_MAX_DOCUMENT_BYTES)
        assertTrue(textResponse.notes.any { it.code == "INFER_BODY_NOT_JSON" })
        val textOp = rootOf(textResponse.content).path("paths").path("/orders/{orderId}").path("post")
        assertTrue(textOp.path("requestBody").path("content").has("text/plain"))
    }

    @Test
    fun `OpenAPI - responses key by status with the standard description and filter deny-listed headers`() {
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val ok = rootOf(response.content).path("paths").path("/orders/{orderId}").path("get").path("responses").path("200")
        assertEquals("OK", ok.path("description").asText())
        val headers = ok.path("headers")
        assertTrue(headers.has("x-api-key"))
        assertFalse(headers.has("date"))
        assertFalse(headers.has("content-type"))
    }

    @Test
    fun `OpenAPI - an unknown status code falls back to the Response description`() {
        val exchange = orderExchange.copy(status = 799)
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(exchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val responses = rootOf(response.content).path("paths").path("/orders/{orderId}").path("get").path("responses")
        assertEquals("Response", responses.path("799").path("description").asText())
    }

    @Test
    fun `OpenAPI - a bearer Authorization header is detected as a security scheme`() {
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val root = rootOf(response.content)
        val scheme = root.path("components").path("securitySchemes").path("bearerAuth")
        assertEquals("http", scheme.path("type").asText())
        assertEquals("bearer", scheme.path("scheme").asText())
        val op = root.path("paths").path("/orders/{orderId}").path("get")
        assertTrue(op.path("security").single().has("bearerAuth"))
        assertTrue(response.notes.any { it.code == "INFER_SECURITY_DETECTED" })
    }

    @Test
    fun `OpenAPI - an api-key-shaped header name becomes a named apiKey scheme`() {
        val exchange = orderExchange.copy(requestHeaders = listOf("X-Api-Key"), authorizationScheme = null)
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(exchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        val scheme = rootOf(response.content).path("components").path("securitySchemes").path("xApiKey")
        assertEquals("apiKey", scheme.path("type").asText())
        assertEquals("header", scheme.path("in").asText())
        assertEquals("X-Api-Key", scheme.path("name").asText())
    }

    @Test
    fun `OpenAPI - a samples-merged note is always present`() {
        val response = Inference.build(InferRequest(ContractType.OPENAPI, http = listOf(orderExchange)), DEFAULT_MAX_DOCUMENT_BYTES)
        assertTrue(response.notes.any { it.code == "INFER_SAMPLES_MERGED" })
    }

    // ---- AsyncAPI -------------------------------------------------------------------------

    @Test
    fun `an inferred AsyncAPI document parses, gates and checks clean`() {
        val batch = MessageBatchSample("orders.v1.created", listOf("""{"total":19.5}""", """{"total":5}"""))
        val response = Inference.build(InferRequest(ContractType.ASYNCAPI, messages = listOf(batch)), DEFAULT_MAX_DOCUMENT_BYTES)
        parses(ContractType.ASYNCAPI, response.content)
        zeroSchemaAndSyntax(ContractType.ASYNCAPI, response.content)
        val root = rootOf(response.content)
        assertTrue(root.path("channels").has("ordersV1Created"))
        assertEquals("orders.v1.created", root.path("channels").path("ordersV1Created").path("address").asText())
        assertTrue(root.path("operations").has("sendOrdersV1Created"))
        val op = root.path("operations").path("sendOrdersV1Created")
        assertEquals("send", op.path("action").asText())
        assertEquals("#/channels/ordersV1Created", op.path("channel").path("\$ref").asText())
        val messageRef = op.path("messages").single().path("\$ref").asText()
        assertTrue(messageRef.startsWith("#/channels/ordersV1Created/messages/"), messageRef)
    }

    @Test
    fun `AsyncAPI - a CloudEvents-shaped payload lifts data into components schemas`() {
        val payload = """{"specversion":"1.0","id":"1","source":"svc","type":"order.created","data":{"total":5}}"""
        val payloadWithNull = """{"specversion":"1.0","id":"2","source":"svc","type":"order.created","data":{"total":null}}"""
        val batch = MessageBatchSample("orders.v1.created", listOf(payload, payloadWithNull))
        val response = Inference.build(InferRequest(ContractType.ASYNCAPI, messages = listOf(batch)), DEFAULT_MAX_DOCUMENT_BYTES)
        val root = rootOf(response.content)
        val dataRef = root.path("channels").path("ordersV1Created").path("messages").path("OrdersV1CreatedMessage")
            .path("payload").path("properties").path("data").path("\$ref").asText()
        assertEquals("#/components/schemas/OrdersV1CreatedData", dataRef)
        val dataSchema = root.path("components").path("schemas").path("OrdersV1CreatedData")
        assertTrue(dataSchema.path("properties").has("total"))
        assertTrue(response.notes.any { it.code == "INFER_CLOUDEVENTS" })
    }

    @Test
    fun `AsyncAPI - a samples-merged note is always present`() {
        val batch = MessageBatchSample("orders.v1.created", listOf("""{"total":5}"""))
        val response = Inference.build(InferRequest(ContractType.ASYNCAPI, messages = listOf(batch)), DEFAULT_MAX_DOCUMENT_BYTES)
        assertTrue(response.notes.any { it.code == "INFER_SAMPLES_MERGED" })
    }

    @Test
    fun `AsyncAPI - channel ids are camelCased from the topic and deduped`() {
        val a = MessageBatchSample("orders.v1.created", listOf("""{"a":1}"""))
        val b = MessageBatchSample("orders-v1-created", listOf("""{"a":1}"""))
        val response = Inference.build(InferRequest(ContractType.ASYNCAPI, messages = listOf(a, b)), DEFAULT_MAX_DOCUMENT_BYTES)
        val root = rootOf(response.content)
        assertTrue(root.path("channels").has("ordersV1Created"))
        assertTrue(root.path("channels").has("ordersV1Created2"))
    }

    // ---- ODCS -----------------------------------------------------------------------------

    private val customersColumns = listOf(
        RelationColumn("id", "int4", nullable = false, primaryKeyPosition = 1),
        RelationColumn("email", "varchar", nullable = false),
        RelationColumn("bio", "text", nullable = true),
        RelationColumn("tags", "_text", nullable = true),
        RelationColumn("weird", "box", nullable = true),
    )

    @Test
    fun `an inferred ODCS document parses, gates and checks clean`() {
        val relation = RelationSample("customers", physicalType = "table", columns = customersColumns)
        val response = Inference.build(
            InferRequest(ContractType.ODCS, name = "Customers", relations = listOf(relation)),
            DEFAULT_MAX_DOCUMENT_BYTES,
        )
        parses(ContractType.ODCS, response.content)
        zeroSchemaAndSyntax(ContractType.ODCS, response.content)
        val metadata = Metadata.extract(ContractType.ODCS, rootOf(response.content))
        assertEquals("Customers", metadata.title)
        assertEquals("v3.0.2", metadata.specVersion)
    }

    @Test
    fun `ODCS - a samples-merged note is always present`() {
        val relation = RelationSample("customers", physicalType = "table", columns = customersColumns)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        assertTrue(response.notes.any { it.code == "INFER_SAMPLES_MERGED" })
    }

    @Test
    fun `ODCS - columns map to logical types, an unknown type becomes string with a note`() {
        val relation = RelationSample("customers", physicalType = "table", columns = customersColumns)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        val props = rootOf(response.content).path("schema").single().path("properties")
        fun propertyNamed(name: String) = props.single { it.path("name").asText() == name }
        assertEquals("integer", propertyNamed("id").path("logicalType").asText())
        assertTrue(propertyNamed("id").path("primaryKey").asBoolean())
        assertEquals(1, propertyNamed("id").path("primaryKeyPosition").asInt())
        assertEquals("string", propertyNamed("email").path("logicalType").asText())
        assertTrue(propertyNamed("email").path("required").asBoolean())
        assertFalse(propertyNamed("bio").path("required").asBoolean())
        assertEquals("array", propertyNamed("tags").path("logicalType").asText())
        assertEquals("string", propertyNamed("tags").path("items").path("logicalType").asText())
        assertEquals("string", propertyNamed("weird").path("logicalType").asText())
        assertTrue(response.notes.any { it.code == "INFER_TYPE_UNKNOWN" })
    }

    @Test
    fun `ODCS - a view omits required everywhere and carries one INFER_VIEW_NULLABILITY note`() {
        val relation = RelationSample("customer_view", physicalType = "view", columns = customersColumns)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        val props = rootOf(response.content).path("schema").single().path("properties")
        props.forEach { p -> assertTrue(p.path("required").isMissingNode, "a view property must not carry required") }
        assertEquals(1, response.notes.count { it.code == "INFER_VIEW_NULLABILITY" })
    }

    @Test
    fun `ODCS - a schema-qualified relation name keeps only the bare name with a note`() {
        val relation = RelationSample("public.customers", physicalType = "table", columns = customersColumns)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        assertEquals("customers", rootOf(response.content).path("schema").single().path("name").asText())
        assertTrue(response.notes.any { it.code == "INFER_SCHEMA_QUALIFIED" })
    }

    @Test
    fun `ODCS - rows-only relations infer properties, dates included`() {
        val rows = listOf(
            """{"id":1,"createdAt":"2024-01-02","name":"Alice"}""",
            """{"id":2,"createdAt":"2024-01-03","name":"Bob"}""",
        )
        val relation = RelationSample("events", rows = rows)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        val props = rootOf(response.content).path("schema").single().path("properties")
        fun propertyNamed(name: String) = props.single { it.path("name").asText() == name }
        assertEquals("integer", propertyNamed("id").path("logicalType").asText())
        assertEquals("date", propertyNamed("createdAt").path("logicalType").asText())
        assertEquals("string", propertyNamed("name").path("logicalType").asText())
        assertTrue(propertyNamed("id").path("required").asBoolean())
    }

    @Test
    fun `ODCS - columns win for type and required, rows add properties columns did not declare`() {
        val columns = listOf(RelationColumn("id", "int4", nullable = false))
        val rows = listOf("""{"id":1,"extra":"x"}""")
        val relation = RelationSample("mixed", columns = columns, rows = rows)
        val response = Inference.build(InferRequest(ContractType.ODCS, relations = listOf(relation)), DEFAULT_MAX_DOCUMENT_BYTES)
        val props = rootOf(response.content).path("schema").single().path("properties")
        fun propertyNamed(name: String) = props.single { it.path("name").asText() == name }
        assertEquals("integer", propertyNamed("id").path("logicalType").asText())
        assertEquals("int4", propertyNamed("id").path("physicalType").asText(), "the column's own physicalType wins")
        assertEquals("string", propertyNamed("extra").path("logicalType").asText())
    }

    // ---- the writer round trip and the document cap ---------------------------------------

    @Test
    fun `the writer round trip survives every YAML quoting pitfall`() {
        val mapper = ObjectMapper()
        val tree = mapper.readTree(
            """
            {
              "version": "3.1.0",
              "yes": "yes",
              "nullish": "null",
              "octal": "007",
              "exponent": "1e3",
              "tilde": "~",
              "empty": "",
              "colon": "value: with colon",
              "hash": "value #not a comment",
              "star": "*starts-with-star",
              "amp": "&anchor-like",
              "at": "@mention",
              "dash": "-dashed",
              "multiline": "line one\nline two\nline three",
              "bool": true,
              "int": 42,
              "decimal": 3.5,
              "isNull": null,
              "on": "on",
              "off": "off",
              "shortY": "y",
              "shortN": "n",
              "hex": "0x1F",
              "octal2": "0o17",
              "binary": "0b101",
              "underscored": "1_000",
              "leadingDot": ".5",
              "plusSign": "+1",
              "sexagesimal": "1:30",
              "inf": ".inf",
              "nan": ".nan",
              "spacesOnly": "   ",
              "123": "numeric key",
              "yes": "yes key",
              "null": "null key",
              "nested": {"array": [1, "two", [3, 4], {"k": "v"}]}
            }
            """.trimIndent(),
        ) as ObjectNode
        val yaml = DocumentWriter.yaml(tree)
        val parsed = DocumentParser.parse(yaml)
        assertTrue(parsed is ParseOutcome.Parsed, "the round-tripped YAML must parse: $yaml")
        assertEquals(tree, parsed.root, "round trip must reproduce the tree exactly:\n$yaml")
    }

    @Test
    fun `an inferred document over the byte cap is a 400`() {
        val relation = RelationSample("customers", physicalType = "table", columns = customersColumns)
        val request = InferRequest(ContractType.ODCS, relations = listOf(relation))
        val ex = assertFailsWith<BadRequestException> { Inference.build(request, maxDocumentBytes = 10) }
        assertContains(ex.message ?: "", "2 MiB document cap")
    }
}
