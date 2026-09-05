package ch.nokillswit

import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.tryit.KafkaClients
import ch.nokillswit.contracts.tryit.KafkaTry
import ch.nokillswit.environments.KafkaSaslMechanism
import ch.nokillswit.environments.KafkaSecurityProtocol
import ch.nokillswit.environments.KafkaTarget
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kafka client configuration is composed from enumerated fields only — and the channel/message resolution's 400s. */
class KafkaTryConfigTest {

    @Test
    fun `the JAAS line names a bundled login module and escapes quotes and backslashes`() {
        val plain = KafkaClients.jaasConfig(KafkaSaslMechanism.PLAIN, "svc", """p"a\ss;""")
        assertEquals(
            """org.apache.kafka.common.security.plain.PlainLoginModule required username="svc" password="p\"a\\ss;";""",
            plain,
        )
        val scram = KafkaClients.jaasConfig(KafkaSaslMechanism.SCRAM_SHA_512, "u", "p")
        assertTrue(scram.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required "), scram)
    }

    @Test
    fun `the client properties carry the protocol and, for SASL, the mechanism and the composed JAAS config only`() {
        val plaintext = KafkaClients.common(KafkaTarget("k1:9092,k2:9092", KafkaSecurityProtocol.PLAINTEXT, null, null, null))
        assertEquals("k1:9092,k2:9092", plaintext["bootstrap.servers"])
        assertEquals("PLAINTEXT", plaintext["security.protocol"])
        assertNull(plaintext["sasl.jaas.config"])
        val sasl = KafkaClients.producer(
            KafkaTarget("k:9093", KafkaSecurityProtocol.SASL_SSL, KafkaSaslMechanism.SCRAM_SHA_256, "svc", "secret"),
        )
        assertEquals("SASL_SSL", sasl["security.protocol"])
        assertEquals("SCRAM-SHA-256", sasl["sasl.mechanism"])
        assertTrue(sasl["sasl.jaas.config"].toString().contains("""username="svc" password="secret""""))
        assertEquals("all", sasl["acks"])
        assertEquals(true, sasl["enable.idempotence"])
        val consumer = KafkaClients.consumer(KafkaTarget("k:9092", KafkaSecurityProtocol.PLAINTEXT, null, null, null), 7)
        assertEquals(false, consumer["enable.auto.commit"])
        assertEquals(false, consumer["allow.auto.create.topics"])
        assertEquals(7, consumer["max.poll.records"])
        assertNull(consumer["group.id"], "no consumer group - nothing is ever committed")
    }

    @Test
    fun `prepare - the channel must exist, its address be literal, the message unambiguous`() {
        val root = (DocumentParser.parse(ContractFixtures.asyncApi3) as ParseOutcome.Parsed).root
        val prepared = KafkaTry.prepare(root, "lightMeasured", null)
        assertEquals("smartylighting.streetlights.1.0.event.lighting.measured", prepared.topic)
        assertEquals("lightMeasured", prepared.message?.name)
        assertEquals("/components/messages/lightMeasured/payload", prepared.message?.payloadPointer)
        assertFailsWith<BadRequestException> { KafkaTry.prepare(root, "nope", null) }
        assertFailsWith<BadRequestException> { KafkaTry.prepare(root, "lightMeasured", "other") }
        val templated = (
            DocumentParser.parse(
                """
                asyncapi: 3.0.0
                info: { title: T, version: "1" }
                channels:
                  perUser: { address: "users.{userId}.events" }
                  many:
                    address: many.events
                    messages:
                      a: { payload: { type: object } }
                      b: { payload: { type: string } }
                  bare: { address: bare.events }
                """.trimIndent(),
            ) as ParseOutcome.Parsed
            ).root
        assertFailsWith<BadRequestException> { KafkaTry.prepare(templated, "perUser", null) }
        assertFailsWith<BadRequestException> { KafkaTry.prepare(templated, "many", null) }
        assertEquals("b", KafkaTry.prepare(templated, "many", "b").message?.name)
        assertNull(KafkaTry.prepare(templated, "bare", null).message, "no message - nothing to validate against")
        assertFailsWith<BadRequestException> { KafkaTry.validateHeaders((1..21).associate { "h$it" to "v" }) }
        assertFailsWith<BadRequestException> { KafkaTry.validateHeaders(mapOf("" to "v")) }
    }
}
