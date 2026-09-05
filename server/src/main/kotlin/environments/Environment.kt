package ch.nokillswit.environments

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Environments (V15): the ADMIN-curated connection targets of a System the try-it feature
 * reaches — an HTTP base URL (OpenAPI), a Kafka connection (AsyncAPI), a read-only PostgreSQL
 * connection (ODCS). Reads are for every authenticated user (target hosts are not secrets — the
 * documents name them too), writes ADMIN only. **No response ever carries a password**: the
 * write-only `password` fields come in, `hasPassword` goes out; the decrypted credentials live
 * in [EnvironmentTargets], handed to the try services and never serialized.
 */
const val MAX_ENVIRONMENT_NAME_LENGTH = 50
const val MAX_ENVIRONMENT_DESCRIPTION_LENGTH = 2000
const val MAX_TARGET_URL_LENGTH = 2048
const val MAX_BOOTSTRAP_SERVERS_LENGTH = 1024
const val MAX_TARGET_USERNAME_LENGTH = 200
const val MAX_TARGET_PASSWORD_LENGTH = 1000

@Serializable
enum class KafkaSecurityProtocol { PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL }

@Serializable
enum class KafkaSaslMechanism(val kafkaName: String) {
    PLAIN("PLAIN"),
    @SerialName("SCRAM-SHA-256")
    SCRAM_SHA_256("SCRAM-SHA-256"),
    @SerialName("SCRAM-SHA-512")
    SCRAM_SHA_512("SCRAM-SHA-512"),
}

@Serializable
data class KafkaTargetRequest(
    val bootstrapServers: String,
    val securityProtocol: KafkaSecurityProtocol,
    val saslMechanism: KafkaSaslMechanism? = null,
    val username: String? = null,
    /** Write-only. Absent/null on a PUT = keep the stored password. */
    val password: String? = null,
)

@Serializable
data class PostgresTargetRequest(
    val jdbcUrl: String,
    val username: String,
    /** Write-only. Absent/null on a PUT = keep the stored password. */
    val password: String? = null,
)

@Serializable
data class EnvironmentRequest(
    val systemId: UInt,
    val name: String,
    val description: String? = null,
    val httpBaseUrl: String? = null,
    val kafka: KafkaTargetRequest? = null,
    val postgres: PostgresTargetRequest? = null,
)

@Serializable
data class KafkaTargetResponse(
    val bootstrapServers: String,
    val securityProtocol: KafkaSecurityProtocol,
    val saslMechanism: KafkaSaslMechanism?,
    val username: String?,
    val hasPassword: Boolean,
)

@Serializable
data class PostgresTargetResponse(val jdbcUrl: String, val username: String, val hasPassword: Boolean)

@Serializable
data class EnvironmentResponse(
    val id: UInt,
    val systemId: UInt,
    /** The system's display name, joined at read time. */
    val systemName: String,
    val name: String,
    val description: String?,
    val httpBaseUrl: String?,
    val kafka: KafkaTargetResponse?,
    val postgres: PostgresTargetResponse?,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias EnvironmentPageResponse = PageResponse<EnvironmentResponse>

data class EnvironmentListFilter(val name: String? = null, val systemId: UInt? = null)

data class EnvironmentListResult(val items: List<EnvironmentResponse>, val total: Long)

/** The DECRYPTED targets — for the try services only; never a route's response. */
data class KafkaTarget(
    val bootstrapServers: String,
    val securityProtocol: KafkaSecurityProtocol,
    val saslMechanism: KafkaSaslMechanism?,
    val username: String?,
    val password: String?,
)

data class PostgresTarget(val jdbcUrl: String, val username: String, val password: String?)

data class EnvironmentTargets(
    val id: UInt,
    val systemId: UInt,
    val name: String,
    val httpBaseUrl: String?,
    val kafka: KafkaTarget?,
    val postgres: PostgresTarget?,
)
