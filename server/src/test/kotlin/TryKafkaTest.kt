package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.tryit.Conformance
import ch.nokillswit.contracts.tryit.TryKafkaPublishRequest
import ch.nokillswit.contracts.tryit.TryKafkaPublishResponse
import ch.nokillswit.contracts.tryit.TryKafkaReadRequest
import ch.nokillswit.contracts.tryit.TryKafkaReadResponse
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.KafkaSecurityProtocol
import ch.nokillswit.environments.KafkaTargetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import org.junit.BeforeClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kafka try legs against a single-node KRaft container registered as an environment's cluster. */
class TryKafkaTest {
    companion object {
        /**
         * Starts (and on a cold runner PULLS) the Kafka container BEFORE any test body: `testApplication`
         * runs the body under a one-minute coroutine budget, and the first Kafka test used to pay the
         * image pull inside it — an `UncompletedCoroutinesError` on CI that never reproduced locally.
         */
        @JvmStatic
        @BeforeClass
        fun startKafka() {
            KafkaTestSupport.bootstrapServers
        }
    }


    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /** A per-test topic: the fixture's channel address is unique per run so the tail read sees only this test's records. */
    private fun document(topic: String) = """
        asyncapi: 3.0.0
        info: { title: Lights, version: 1.0.0 }
        channels:
          lightMeasured:
            address: $topic
            messages:
              lightMeasured: { ${'$'}ref: "#/components/messages/lightMeasured" }
          ghost:
            address: $topic.ghost
            messages:
              lightMeasured: { ${'$'}ref: "#/components/messages/lightMeasured" }
        operations:
          receive: { action: receive, channel: { ${'$'}ref: "#/channels/lightMeasured" } }
        components:
          messages:
            lightMeasured:
              name: lightMeasured
              contentType: application/json
              payload:
                type: object
                required: [lumens]
                properties:
                  lumens: { type: integer, minimum: 0 }
    """.trimIndent()

    private suspend fun HttpClient.asyncApi(
        prefix: String,
        systemId: UInt,
        teamId: UInt,
        topic: String,
    ): Pair<ContractResponse, VersionResponse> {
        val c: ContractResponse = postJson(
            "/api/v1/contracts", ContractCreateRequest(systemId, ContractType.ASYNCAPI, name(prefix), null, ownerTeamId = teamId),
        ).body()
        val v = postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", document(topic)))
        assertEquals(HttpStatusCode.Created, v.status, v.bodyAsText())
        return c to v.body()
    }

    private suspend fun environment(systemId: UInt, bootstrap: String = KafkaTestSupport.bootstrapServers): UInt =
        TestEnvironments.service.create(
            EnvironmentRequest(systemId, name("kafka"), null, null, KafkaTargetRequest(bootstrap, KafkaSecurityProtocol.PLAINTEXT), null),
        )

    private fun publishPath(c: ContractResponse, v: VersionResponse) = "/api/v1/contracts/${c.id}/versions/${v.id}/try/kafka/publish"
    private fun readPath(c: ContractResponse, v: VersionResponse) = "/api/v1/contracts/${c.id}/versions/${v.id}/try/kafka/read"

    @Test
    fun `a writer publishes - measured before sending, sent regardless - and anyone reads the tail newest first`() = testApplication {
        usePostgresTestcontainer()
        val topic = "try.lights.${UUID.randomUUID().toString().substring(0, 8)}"
        val writerEmail = uniqueEmail("trykafka-w")
        val writerId = TestUsers.seed(writerEmail, "pw", role = UserRole.USER)
        val writer = authedClient(writerEmail, "pw")
        val teamId = TestTeams.seed(name("t"), listOf(writerId))
        val systemId = TestContracts.seedSystem("trykafka")
        val (c, v) = writer.asyncApi("trykafka", systemId, teamId, topic)
        val env = environment(systemId)

        val capture = LogCapture("ch.nokillswit.audit")
        val first = try {
            val request =
                TryKafkaPublishRequest(env, "lightMeasured", key = "lamp-1", headers = mapOf("h" to "1"), payload = """{"lumens": 5}""")
            writer.postJson(publishPath(c, v), request)
        } finally {
            capture.detach()
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val published = first.body<TryKafkaPublishResponse>()
        assertEquals(topic, published.topic)
        assertEquals(0, published.offset)
        assertEquals(emptyList(), published.conformance.findings)
        val event = capture.events.single { it.message == "contract.tried_kafka_publish" }
        assertTrue(event.hasKeyValue("topic", topic) && event.hasKeyValue("outcome", "published"), event.toString())

        val bad = writer.postJson(publishPath(c, v), TryKafkaPublishRequest(env, "lightMeasured", payload = """{"lumens": -1}"""))
            .body<TryKafkaPublishResponse>()
        assertEquals(1, bad.offset, "sent despite the finding")
        assertEquals(listOf(Conformance.PAYLOAD_SCHEMA_MISMATCH), bad.conformance.findings.map { it.code })
        assertEquals("/payload/lumens", bad.conformance.findings.single().path)
        val notJson = writer.postJson(publishPath(c, v), TryKafkaPublishRequest(env, "lightMeasured", payload = "plain text"))
            .body<TryKafkaPublishResponse>()
        assertEquals(listOf(Conformance.PAYLOAD_NOT_JSON), notJson.conformance.findings.map { it.code })

        val reader = seededClient("trykafka-r", UserRole.USER)
        val read = reader.postJson(readPath(c, v), TryKafkaReadRequest(env, "lightMeasured", limit = 2))
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        val tail = read.body<TryKafkaReadResponse>()
        assertEquals(topic, tail.topic)
        assertEquals(listOf(2L, 1L), tail.messages.map { it.offset }, "newest first, bounded by the limit")
        assertTrue(tail.reachedEnd)
        assertEquals("plain text", tail.messages[0].payload)
        assertEquals("utf8", tail.messages[0].encoding)
        assertEquals("""{"lumens": -1}""", tail.messages[1].payload)
        val codes = tail.conformance.findings.map { it.path to it.code }
        assertTrue(("/messages/0/payload" to Conformance.PAYLOAD_NOT_JSON) in codes, codes.toString())
        assertTrue(("/messages/1/payload/lumens" to Conformance.PAYLOAD_SCHEMA_MISMATCH) in codes, codes.toString())
        assertTrue(tail.conformance.findings.all { it.severity == Severity.ERROR })
        val all = reader.postJson(readPath(c, v), TryKafkaReadRequest(env, "lightMeasured", limit = 50)).body<TryKafkaReadResponse>()
        assertEquals(3, all.messages.size)
        assertEquals("lamp-1", all.messages.last().key)
        assertEquals(mapOf("h" to "1"), all.messages.last().headers)

        val stranger = seededClient("trykafka-s", UserRole.USER)
        val denied = stranger.postJson(publishPath(c, v), mapOf("garbage" to true))
        assertEquals(HttpStatusCode.Forbidden, denied.status, "writers only, before the body decodes")
        val strangerRead = stranger.postJson(readPath(c, v), TryKafkaReadRequest(env, "lightMeasured"))
        assertEquals(HttpStatusCode.OK, strangerRead.status, "reading is everyone's")
    }

    @Test
    fun `the 400 and 502 matrix - unknown channel, missing cluster, an absent topic, an unreachable cluster, the read limit`() =
        testApplication {
            usePostgresTestcontainer()
            val topic = "try.lights.${UUID.randomUUID().toString().substring(0, 8)}"
            val admin = seededClient("trykafka-bad", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("trykafka-bad")
            val (c, v) = admin.asyncApi("trykafka-bad", systemId, TestTeams.seed(name("t")), topic)
            val env = environment(systemId)
            suspend fun detail(path: String, body: Any, expected: HttpStatusCode): String {
                val r = admin.postJson(path, body)
                assertEquals(expected, r.status, r.bodyAsText())
                return r.body<ProblemDetail>().detail.orEmpty()
            }
            val bad = HttpStatusCode.BadRequest
            val gateway = HttpStatusCode.BadGateway
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(env, "nope"), bad).contains("no channel"))
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(env, "lightMeasured", limit = 0), bad).contains("limit"))
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(env, "lightMeasured", limit = 51), bad).contains("limit"))
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(env, "ghost"), gateway).contains("does not exist"))
            val noKafka =
                TestEnvironments.service.create(EnvironmentRequest(systemId, name("http"), null, "http://gateway:8080", null, null))
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(noKafka, "lightMeasured"), bad).contains("no Kafka"))
            val down = environment(systemId, bootstrap = "127.0.0.1:9")
            assertTrue(detail(readPath(c, v), TryKafkaReadRequest(down, "lightMeasured"), gateway).contains("could not be reached"))
            assertTrue(
                detail(publishPath(c, v), TryKafkaPublishRequest(down, "lightMeasured", payload = "{}"), gateway)
                    .contains("could not be reached"),
            )
            val oversized = TryKafkaPublishRequest(env, "lightMeasured", payload = "x".repeat(256 * 1024 + 1))
            detail(publishPath(c, v), oversized, HttpStatusCode.PayloadTooLarge)
        }
}
