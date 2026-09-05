package ch.nokillswit.contracts.tryit

import ch.nokillswit.environments.KafkaSaslMechanism
import ch.nokillswit.environments.KafkaSecurityProtocol
import ch.nokillswit.environments.KafkaTarget
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Properties

/**
 * Kafka client configuration built ONLY from the environment's enumerated fields. The JAAS
 * configuration is COMPOSED here from the protocol, mechanism, username and password — never
 * accepted as a string from a request or the registry (a caller-supplied `sasl.jaas.config` can
 * name an arbitrary login module — the CVE-2023-25194 class), and the login module is one of the
 * two Kafka-bundled ones. Values are quoted with backslashes and double quotes escaped.
 */
object KafkaClients {
    private const val REQUEST_TIMEOUT_MS = 5_000
    private const val DELIVERY_TIMEOUT_MS = 10_000
    private const val MAX_BLOCK_MS = 5_000
    const val MAX_PUBLISH_BYTES = 256 * 1024

    fun jaasConfig(mechanism: KafkaSaslMechanism, username: String, password: String): String {
        val module = when (mechanism) {
            KafkaSaslMechanism.PLAIN -> "org.apache.kafka.common.security.plain.PlainLoginModule"
            KafkaSaslMechanism.SCRAM_SHA_256, KafkaSaslMechanism.SCRAM_SHA_512 -> "org.apache.kafka.common.security.scram.ScramLoginModule"
        }
        return "$module required username=\"${quote(username)}\" password=\"${quote(password)}\";"
    }

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun common(target: KafkaTarget): Properties = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, target.bootstrapServers)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, target.securityProtocol.name)
        put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS)
        put(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, MAX_BLOCK_MS)
        put(CommonClientConfigs.CLIENT_ID_CONFIG, "covenant-try")
        val sasl = target.securityProtocol in setOf(KafkaSecurityProtocol.SASL_PLAINTEXT, KafkaSecurityProtocol.SASL_SSL)
        if (sasl) {
            val mechanism = requireNotNull(target.saslMechanism) { "a SASL protocol without a mechanism cannot pass validation" }
            put(SaslConfigs.SASL_MECHANISM, mechanism.kafkaName)
            put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig(mechanism, target.username.orEmpty(), target.password.orEmpty()))
        }
    }

    fun producer(target: KafkaTarget): Properties = common(target).apply {
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, MAX_PUBLISH_BYTES + 4096)
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS)
    }

    fun consumer(target: KafkaTarget, maxRecords: Int): Properties = common(target).apply {
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
        // No group: nothing is ever committed, and the read leaves no consumer-group trace on the cluster.
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxRecords)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    }
}
