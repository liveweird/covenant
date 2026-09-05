package ch.nokillswit

import ch.nokillswit.environments.EnvironmentPageResponse
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.EnvironmentResponse
import ch.nokillswit.environments.KafkaSaslMechanism
import ch.nokillswit.environments.KafkaSecurityProtocol
import ch.nokillswit.environments.KafkaTargetRequest
import ch.nokillswit.environments.PostgresTargetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The environments registry (V15): CRUD, the ADMIN gate, the target shape rules, write-only secrets, the system cascade. */
class EnvironmentTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val kafka = KafkaTargetRequest(
        bootstrapServers = "broker-1.internal:9092, broker-2.internal:9092",
        securityProtocol = KafkaSecurityProtocol.SASL_SSL,
        saslMechanism = KafkaSaslMechanism.SCRAM_SHA_256,
        username = "svc",
        password = "kafka-secret-value",
    )
    private val postgres = PostgresTargetRequest("jdbc:postgresql://db.internal:5432/app?sslmode=require", "reader", "pg-secret-value")

    private fun request(
        systemId: UInt,
        n: String,
        http: String? = "http://gateway.internal:8080/",
        k: KafkaTargetRequest? = kafka,
        p: PostgresTargetRequest? = postgres,
    ) = EnvironmentRequest(systemId, n, "The staging cluster", http, k, p)

    private suspend fun HttpClient.create(systemId: UInt, n: String = name("env")): EnvironmentResponse =
        postJson("/api/v1/environments", request(systemId, n))
            .also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
            .body()

    @Test
    fun `create, read, list, update and delete - no response ever carries a password, an absent password keeps the stored one`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("env-admin", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("env")
            val created = admin.create(systemId, name("env"))
            assertEquals("http://gateway.internal:8080", created.httpBaseUrl, "the trailing slash is dropped")
            assertEquals("broker-1.internal:9092,broker-2.internal:9092", created.kafka?.bootstrapServers)
            assertEquals(KafkaSaslMechanism.SCRAM_SHA_256, created.kafka?.saslMechanism)
            assertTrue(created.kafka!!.hasPassword && created.postgres!!.hasPassword)
            val rawJson = admin.get("/api/v1/environments/${created.id}").bodyAsText()
            assertFalse(rawJson.contains("secret-value") || rawJson.contains("\"password\""), rawJson)
            val listed = admin.get("/api/v1/environments?systemId=$systemId").body<EnvironmentPageResponse>()
            assertEquals(1, listed.total)
            assertEquals(created.id, listed.items.single().id)
            // Encrypted at rest: the raw columns hold envelopes, not the plaintext.
            val raw = TestEnvironments.rawRow(created.id)
            assertTrue(raw.kafkaPassword!!.startsWith("enc:v1:") && raw.pgPassword!!.startsWith("enc:v1:"))
            // A PUT without passwords keeps them; one without the Kafka target drops it entirely.
            val renamed = request(systemId, created.name + "-2", k = kafka.copy(password = null), p = postgres.copy(password = null))
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/environments/${created.id}", renamed).status)
            val after = admin.get("/api/v1/environments/${created.id}").body<EnvironmentResponse>()
            assertEquals(created.name + "-2", after.name)
            assertTrue(after.kafka!!.hasPassword && after.postgres!!.hasPassword, "absent passwords keep the stored ones")
            assertEquals(raw.pgPassword, TestEnvironments.rawRow(created.id).pgPassword, "the stored ciphertext is untouched")
            val withoutKafka = request(systemId, after.name, k = null)
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/environments/${created.id}", withoutKafka).status)
            assertNull(admin.get("/api/v1/environments/${created.id}").body<EnvironmentResponse>().kafka)
            assertNull(TestEnvironments.rawRow(created.id).kafkaPassword)
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/environments/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/environments/${created.id}").status)
            assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/environments/${created.id}").status)
        }

    @Test
    fun `authz - reads for every authenticated user, writes ADMIN before the body decodes`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("env-authz-admin", UserRole.ADMIN)
        val user = seededClient("env-authz-user", UserRole.USER)
        val systemId = TestContracts.seedSystem("env-authz")
        val created = admin.create(systemId)
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/environments/${created.id}").status)
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/environments").status)
        assertEquals(HttpStatusCode.Forbidden, user.postJson("/api/v1/environments", mapOf("garbage" to true)).status, "403 wins over 400")
        assertEquals(HttpStatusCode.Forbidden, user.putJson("/api/v1/environments/${created.id}", request(systemId, "x")).status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/environments/${created.id}").status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/environments/999999").status, "guard before read")
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/environments").status)
    }

    @Test
    fun `the shape rules - each rejection is a 400 naming the field, and the per-system name clash is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("env-rules", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("env-rules")
        suspend fun rejected(req: EnvironmentRequest, fragment: String) {
            val r = admin.postJson("/api/v1/environments", req)
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
            assertTrue(r.body<ProblemDetail>().detail!!.contains(fragment), r.bodyAsText())
        }
        rejected(request(systemId, "no-target", http = null, k = null, p = null), "at least one target")
        rejected(request(systemId, "userinfo", http = "https://user:pw@gateway.internal/"), "httpBaseUrl")
        rejected(request(systemId, "ftp", http = "ftp://gateway.internal/"), "httpBaseUrl")
        rejected(request(systemId, "query", http = "http://gateway.internal/?x=1"), "httpBaseUrl")
        rejected(request(systemId, "metadata", http = "http://169.254.169.254/latest"), "link-local")
        rejected(request(systemId, "bootstrap", k = kafka.copy(bootstrapServers = "broker;9092")), "bootstrapServers")
        rejected(request(systemId, "no-mech", k = kafka.copy(saslMechanism = null)), "saslMechanism")
        rejected(request(systemId, "no-pw", k = kafka.copy(password = null)), "kafka.password")
        val plainWithUser = KafkaTargetRequest("b:9092", KafkaSecurityProtocol.PLAINTEXT, username = "x")
        rejected(request(systemId, "plain-user", k = plainWithUser), "apply only")
        rejected(request(systemId, "mysql", p = postgres.copy(jdbcUrl = "jdbc:mysql://db/app")), "jdbcUrl")
        val evilParam = postgres.copy(jdbcUrl = "jdbc:postgresql://db:5432/app?sslfactory=x.Evil")
        rejected(request(systemId, "sslfactory", p = evilParam), "'sslfactory' is not allowed")
        rejected(request(systemId, "pg-no-pw", p = postgres.copy(password = null)), "postgres.password")
        rejected(request(999_999u, "ghost"), "Unknown or deleted system")
        val first = admin.create(systemId, "Staging")
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/environments", request(systemId, "staging")).status)
        val elsewhere = TestContracts.seedSystem("env-rules-2")
        val elsewhereStaging = admin.postJson("/api/v1/environments", request(elsewhere, "Staging"))
        assertEquals(HttpStatusCode.Created, elsewhereStaging.status, "unique per system")
        assertNotNull(first)
    }

    @Test
    fun `deleting a system takes its environments along - the audit trail names targets, never secrets`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("env-cascade", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("env-cascade")
        val created = withAuditCapture { capture ->
            val c = admin.create(systemId)
            val event = assertNotNull(
                capture.awaitEvent { it.message == "environment.created" && it.hasKeyValue("environmentId", c.id.toLong()) },
            )
            assertTrue(event.hasKeyValue("targets", "http,kafka,postgres"))
            assertFalse(event.keyValuePairs.orEmpty().any { it.value.toString().contains("secret-value") })
            c
        }
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/systems/$systemId").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/environments/${created.id}").status)
        assertTrue(TestEnvironments.rawRow(created.id).markedAsDeleted)
    }
}
