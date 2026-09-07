package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.infer.HttpExchangeSample
import ch.nokillswit.contracts.infer.InferRequest
import ch.nokillswit.contracts.infer.InferResponse
import ch.nokillswit.contracts.infer.MessageBatchSample
import ch.nokillswit.contracts.infer.RelationColumn
import ch.nokillswit.contracts.infer.RelationSample
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /api/v1/contracts/infer` — the pure endpoint: 200 per type, the 400 vocabulary, 413, 401. */
class InferRoutesTest {
    private val http = listOf(
        HttpExchangeSample(method = "GET", url = "https://api.example.test/orders/42", status = 200, responseBody = """{"id":42}"""),
    )
    private val messages = listOf(MessageBatchSample("orders.created", listOf("""{"total":5}""")))
    private val relations = listOf(
        RelationSample("customers", physicalType = "table", columns = listOf(RelationColumn("id", "int4", false))),
    )

    @Test
    fun `200 for a valid sample of each type`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer200")
        val openapi = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = http))
        assertEquals(HttpStatusCode.OK, openapi.status)
        assertTrue(openapi.body<InferResponse>().content.contains("openapi: 3.1.0"))

        val asyncapi = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ASYNCAPI, messages = messages))
        assertEquals(HttpStatusCode.OK, asyncapi.status)
        assertTrue(asyncapi.body<InferResponse>().content.contains("asyncapi: 3.0.0"))

        val odcs = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ODCS, relations = relations))
        assertEquals(HttpStatusCode.OK, odcs.status)
        assertTrue(odcs.body<InferResponse>().content.contains("kind: DataContract"))
    }

    @Test
    fun `400 when no sample matches the declared type`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400none")
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 when a sample list for another type is also populated`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400mixed")
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = http, relations = relations))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 for an invalid method`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400method")
        val bad = http[0].copy(method = "TRACE")
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = listOf(bad)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 for an out-of-range status`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400status")
        val bad = http[0].copy(status = 999)
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = listOf(bad)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 for a JSON-typed body that does not parse`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400body")
        val bad = http[0].copy(responseContentType = "application/json", responseBody = "not json")
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = listOf(bad)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 for a relation with neither columns nor rows`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400relation")
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ODCS, relations = listOf(RelationSample("bare"))))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `413 for a body sample over 1 MiB`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer413")
        val big = http[0].copy(responseContentType = "text/plain", responseBody = "x".repeat(2 * 1024 * 1024))
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = listOf(big)))
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `413 for an oversized AsyncAPI payload`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer413async")
        val big = MessageBatchSample("orders.created", listOf("x".repeat(2 * 1024 * 1024)))
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ASYNCAPI, messages = listOf(big)))
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `413 for an oversized ODCS row`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer413odcs")
        val bigRow = """{"note":"${"x".repeat(2 * 1024 * 1024)}"}"""
        val relation = RelationSample("events", rows = listOf(bigRow))
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ODCS, relations = listOf(relation)))
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `400 for a column name with a space`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400colname")
        val relation = RelationSample("customers", columns = listOf(RelationColumn(" ", "int4", false)))
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ODCS, relations = listOf(relation)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `400 for more than 50 columns`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("infer400columns")
        val columns = (1..51).map { RelationColumn("col$it", "int4", false) }
        val relation = RelationSample("customers", columns = columns)
        val response = user.postJson("/api/v1/contracts/infer", InferRequest(ContractType.ODCS, relations = listOf(relation)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `401 without a token`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/contracts/infer", InferRequest(ContractType.OPENAPI, http = http))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
