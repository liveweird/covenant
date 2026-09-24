package ch.nokillswit

import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** A KRaft single-node Kafka, started lazily on first use and shared across the suite (the PostgresTestSupport shape). */
object KafkaTestSupport {
    private val container: KafkaContainer by lazy {
        // The 4.3.1 native image segfaults during startup on CI amd64; use the JVM image.
        KafkaContainer(
            DockerImageName
                .parse("apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837")
                .asCompatibleSubstituteFor("apache/kafka"),
        ).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    /** `host:port` — without the `PLAINTEXT://` listener prefix the environments registry refuses. */
    val bootstrapServers: String get() = container.bootstrapServers.substringAfter("://")
}
