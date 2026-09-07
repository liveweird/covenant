package ch.nokillswit

import ch.nokillswit.contracts.infer.ObserveKafkaRequest
import ch.nokillswit.contracts.infer.ObserveKafkaResponse
import ch.nokillswit.contracts.tryit.KafkaClients
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.KafkaSecurityProtocol
import ch.nokillswit.environments.KafkaTarget
import ch.nokillswit.environments.KafkaTargetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.BeforeClass
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kafka observe leg (`contracts/infer/`) — `KafkaTry.read` unchanged, reduced to the JSON-worth payloads. */
class ObserveKafkaTest {
    companion object {
        /** Warms the shared Kafka container BEFORE any test body — the `TryKafkaTest` shape. */
        @JvmStatic
        @BeforeClass
        fun startKafka() {
            KafkaTestSupport.bootstrapServers
        }
    }

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /** A test-side producer, built exactly like `KafkaTry`'s from the environment's enumerated fields. */
    private fun produce(topic: String) {
        val target = KafkaTarget(KafkaTestSupport.bootstrapServers, KafkaSecurityProtocol.PLAINTEXT, null, null, null)
        val producer = KafkaProducer<ByteArray, ByteArray>(KafkaClients.producer(target))
        try {
            producer.send(ProducerRecord<ByteArray, ByteArray>(topic, null, """{"lumens": 5}""".toByteArray())).get()
            producer.send(ProducerRecord<ByteArray, ByteArray>(topic, null, byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01))).get()
            producer.send(ProducerRecord<ByteArray, ByteArray>(topic, null, null)).get() // a tombstone
        } finally {
            producer.close()
        }
    }

    private suspend fun environment(systemId: UInt, bootstrap: String = KafkaTestSupport.bootstrapServers): UInt =
        TestEnvironments.service.create(
            EnvironmentRequest(systemId, name("kafka"), null, null, KafkaTargetRequest(bootstrap, KafkaSecurityProtocol.PLAINTEXT), null),
        )

    private suspend fun HttpClient.observeKafka(request: ObserveKafkaRequest): HttpResponse =
        postJson("/api/v1/contracts/infer/observe/kafka", request)

    @Test
    fun `a JSON, a binary and a tombstone record - only the JSON payload survives, the rest counted in one note`() = testApplication {
        usePostgresTestcontainer()
        val topic = "observe.lights.${UUID.randomUUID().toString().substring(0, 8)}"
        produce(topic)
        val admin = seededClient("observekafka", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("observekafka")
        val env = environment(systemId)

        val response = admin.observeKafka(ObserveKafkaRequest(env, topic, limit = 10))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.body<ObserveKafkaResponse>()
        assertEquals(listOf("""{"lumens": 5}"""), body.sample.payloads)
        assertEquals(topic, body.sample.channel)
        assertTrue(body.reachedEnd)
        assertTrue(body.notes.any { it.code == "INFER_RECORDS_SKIPPED" && it.message.contains("2 of 3") }, body.notes.toString())
    }

    @Test
    fun `the 400, 404 and 502 matrix - invalid topic, out-of-range limit, unknown environment, no Kafka target, unreachable cluster`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("observekafka-bad", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("observekafka-bad")
            val env = environment(systemId)
            suspend fun detail(request: ObserveKafkaRequest, expected: HttpStatusCode = HttpStatusCode.BadRequest): String {
                val r = admin.observeKafka(request)
                assertEquals(expected, r.status, r.bodyAsText())
                return r.body<ProblemDetail>().detail.orEmpty()
            }
            assertTrue(detail(ObserveKafkaRequest(env, "bad topic!")).contains("not valid"))
            assertTrue(detail(ObserveKafkaRequest(env, "ok-topic", limit = 0)).contains("limit"))
            assertTrue(detail(ObserveKafkaRequest(env, "ok-topic", limit = 51)).contains("limit"))
            assertEquals(HttpStatusCode.NotFound, admin.observeKafka(ObserveKafkaRequest(999_999u, "ok-topic")).status)
            val noKafka =
                TestEnvironments.service.create(EnvironmentRequest(systemId, name("http"), null, "http://gateway:8080", null, null))
            assertTrue(detail(ObserveKafkaRequest(noKafka, "ok-topic")).contains("no Kafka"))
            val down = environment(systemId, bootstrap = "127.0.0.1:9")
            assertTrue(detail(ObserveKafkaRequest(down, "ok-topic"), HttpStatusCode.BadGateway).contains("could not be reached"))
        }
}
