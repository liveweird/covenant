package ch.nokillswit

import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** A KRaft single-node Kafka, started lazily on first use and shared across the suite (the PostgresTestSupport shape). */
object KafkaTestSupport {
    private val container: KafkaContainer by lazy {
        KafkaContainer(
            DockerImageName
                .parse("apache/kafka-native:4.3.1@sha256:2885898ba17065023f1bd605f3a81efcfa986014f062b73b91ef5462485f9060")
                .asCompatibleSubstituteFor("apache/kafka-native"),
        ).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    /** `host:port` — without the `PLAINTEXT://` listener prefix the environments registry refuses. */
    val bootstrapServers: String get() = container.bootstrapServers.substringAfter("://")
}
