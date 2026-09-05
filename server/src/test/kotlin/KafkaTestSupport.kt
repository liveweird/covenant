package ch.nokillswit

import org.testcontainers.kafka.KafkaContainer

/** A KRaft single-node Kafka, started lazily on first use and shared across the suite (the PostgresTestSupport shape). */
object KafkaTestSupport {
    private val container: KafkaContainer by lazy {
        KafkaContainer("apache/kafka-native:4.3.1").apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    /** `host:port` — without the `PLAINTEXT://` listener prefix the environments registry refuses. */
    val bootstrapServers: String get() = container.bootstrapServers.substringAfter("://")
}
