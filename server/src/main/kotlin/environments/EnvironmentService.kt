package ch.nokillswit.environments

import ch.nokillswit.infra.crypto.EncryptedAtRest
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.crypto.reencryptRows
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val EnvironmentServiceKey = AttributeKey<EnvironmentService>("EnvironmentService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to EnvironmentService.Environments.id,
    "name" to EnvironmentService.Environments.name,
    "systemId" to EnvironmentService.Environments.systemId,
    "createdAt" to EnvironmentService.Environments.createdAt,
    "updatedAt" to EnvironmentService.Environments.updatedAt,
)

val ENVIRONMENT_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The environments registry (V15) — the `systems/` shape plus the two encrypted password columns
 * ([EncryptedAtRest]: the bootstrap backfill wraps legacy rows and re-wraps on rotation). The
 * system's name is joined at read time (a sanctioned cross-feature read); [resolveTarget] is the
 * only path that decrypts, for the try services.
 */
class EnvironmentService(private val database: R2dbcDatabase, private val cipher: FieldCipher) : EncryptedAtRest {
    object Environments : UIntIdTable("environments") {
        val systemId = reference("system_id", SystemService.Systems)
        // uq_environments_system_name_active (V15): (system_id, LOWER(name)) among active rows.
        val name = varchar("name", length = MAX_ENVIRONMENT_NAME_LENGTH)
        val description = varchar("description", length = MAX_ENVIRONMENT_DESCRIPTION_LENGTH).nullable()
        val httpBaseUrl = varchar("http_base_url", length = MAX_TARGET_URL_LENGTH).nullable()
        val kafkaBootstrapServers = varchar("kafka_bootstrap_servers", length = MAX_BOOTSTRAP_SERVERS_LENGTH).nullable()
        val kafkaSecurityProtocol = varchar("kafka_security_protocol", length = 20).nullable()
        val kafkaSaslMechanism = varchar("kafka_sasl_mechanism", length = 20).nullable()
        val kafkaUsername = varchar("kafka_username", length = MAX_TARGET_USERNAME_LENGTH).nullable()
        val kafkaPassword = text("kafka_password").nullable() // encrypted at rest
        val pgJdbcUrl = varchar("pg_jdbc_url", length = MAX_TARGET_URL_LENGTH).nullable()
        val pgUsername = varchar("pg_username", length = MAX_TARGET_USERNAME_LENGTH).nullable()
        val pgPassword = text("pg_password").nullable() // encrypted at rest
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    override val encryptedRowLabel = "environment credential"

    override suspend fun encryptLegacyRows(reencryptAll: Boolean): Int = suspendTransaction(database) {
        cipher.reencryptRows(Environments, listOf(Environments.kafkaPassword, Environments.pgPassword), reencryptAll)
    }

    private fun active(): Op<Boolean> = Environments.markedAsDeleted eq false

    private fun now() = System.currentTimeMillis()

    private fun joined() = Environments.innerJoin(SystemService.Systems)

    suspend fun list(filter: EnvironmentListFilter, paging: PageRequest): EnvironmentListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = active()
        filter.name?.takeIf { it.isNotBlank() }?.let { predicate = predicate and Environments.name.containsNormalized(it) }
        filter.systemId?.let { predicate = predicate and (Environments.systemId eq it) }
        val total = Environments.selectAll().where { predicate }.count()
        val rows = joined().selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        EnvironmentListResult(items = rows.map { it.toResponse() }, total = total)
    }

    suspend fun read(id: UInt): EnvironmentResponse? = suspendTransaction(database) {
        joined().selectAll().where { (Environments.id eq id) and active() }.toList().singleOrNull()?.toResponse()
    }

    /** The decrypted targets for the try services — null for a missing/deleted environment. */
    suspend fun resolveTarget(id: UInt): EnvironmentTargets? = suspendTransaction(database) {
        Environments.selectAll().where { (Environments.id eq id) and active() }.toList().singleOrNull()?.let { row ->
            EnvironmentTargets(
                id = row[Environments.id].value,
                systemId = row[Environments.systemId].value,
                name = row[Environments.name],
                httpBaseUrl = row[Environments.httpBaseUrl],
                kafka = row[Environments.kafkaBootstrapServers]?.let {
                    KafkaTarget(
                        bootstrapServers = it,
                        securityProtocol = KafkaSecurityProtocol.valueOf(row[Environments.kafkaSecurityProtocol]!!),
                        saslMechanism = row[Environments.kafkaSaslMechanism]?.let(KafkaSaslMechanism::valueOf),
                        username = row[Environments.kafkaUsername],
                        password = row[Environments.kafkaPassword]?.let(cipher::decrypt),
                    )
                },
                postgres = row[Environments.pgJdbcUrl]?.let {
                    PostgresTarget(it, row[Environments.pgUsername].orEmpty(), row[Environments.pgPassword]?.let(cipher::decrypt))
                },
            )
        }
    }

    /** Creates inside an ACTIVE system (an unknown one is the client's fault → 400); passwords are encrypted. */
    suspend fun create(request: EnvironmentRequest): UInt = suspendTransaction(database) {
        validateEnvironmentRequest(request, passwordRequired = true)
        requireSystem(request.systemId)
        val stamp = now()
        Environments.insert {
            it[systemId] = request.systemId
            it[name] = request.name
            it[description] = request.description
            it[httpBaseUrl] = request.httpBaseUrl
            it[kafkaBootstrapServers] = request.kafka?.bootstrapServers
            it[kafkaSecurityProtocol] = request.kafka?.securityProtocol?.name
            it[kafkaSaslMechanism] = request.kafka?.saslMechanism?.name
            it[kafkaUsername] = request.kafka?.username
            it[kafkaPassword] = request.kafka?.password?.let(cipher::encrypt)
            it[pgJdbcUrl] = request.postgres?.jdbcUrl
            it[pgUsername] = request.postgres?.username
            it[pgPassword] = request.postgres?.password?.let(cipher::encrypt)
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Environments.id].value
    }

    /**
     * Full replace of every non-secret field; a target's ABSENT password keeps the stored one
     * (write-only semantics), a removed target nulls all of its columns. The name clash rides the
     * V15 index → 409. Returns the affected row count (0 = missing/deleted).
     */
    suspend fun update(id: UInt, request: EnvironmentRequest): Int = suspendTransaction(database) {
        val current = Environments.selectAll().where { (Environments.id eq id) and active() }.toList().singleOrNull()
            ?: return@suspendTransaction 0
        val kafkaStored = current[Environments.kafkaPassword] != null
        val pgStored = current[Environments.pgPassword] != null
        validateEnvironmentRequest(request, passwordRequired = false)
        request.kafka?.let { validateEnvironmentRequest(request.copy(postgres = null), passwordRequired = !kafkaStored) }
        request.postgres?.let { validateEnvironmentRequest(request.copy(kafka = null), passwordRequired = !pgStored) }
        requireSystem(request.systemId)
        Environments.update({ (Environments.id eq id) and active() }) {
            it[systemId] = request.systemId
            it[name] = request.name
            it[description] = request.description
            it[httpBaseUrl] = request.httpBaseUrl
            it[kafkaBootstrapServers] = request.kafka?.bootstrapServers
            it[kafkaSecurityProtocol] = request.kafka?.securityProtocol?.name
            it[kafkaSaslMechanism] = request.kafka?.saslMechanism?.name
            it[kafkaUsername] = request.kafka?.username
            it[kafkaPassword] = when {
                request.kafka == null -> null
                request.kafka.password != null -> cipher.encrypt(request.kafka.password)
                else -> current[Environments.kafkaPassword]
            }
            it[pgJdbcUrl] = request.postgres?.jdbcUrl
            it[pgUsername] = request.postgres?.username
            it[pgPassword] = when {
                request.postgres == null -> null
                request.postgres.password != null -> cipher.encrypt(request.postgres.password)
                else -> current[Environments.pgPassword]
            }
            it[updatedAt] = now()
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Environments.update({ (Environments.id eq id) and active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = now()
        }
    }

    private suspend fun requireSystem(systemId: UInt) {
        val s = SystemService.Systems
        val ok = s.select(s.id).where { (s.id eq systemId) and (s.markedAsDeleted eq false) }.count() > 0
        if (!ok) throw BadRequestException("Unknown or deleted system id: $systemId")
    }

    private fun ResultRow.toResponse() = EnvironmentResponse(
        id = this[Environments.id].value,
        systemId = this[Environments.systemId].value,
        systemName = this[SystemService.Systems.name],
        name = this[Environments.name],
        description = this[Environments.description],
        httpBaseUrl = this[Environments.httpBaseUrl],
        kafka = this[Environments.kafkaBootstrapServers]?.let {
            KafkaTargetResponse(
                bootstrapServers = it,
                securityProtocol = KafkaSecurityProtocol.valueOf(this[Environments.kafkaSecurityProtocol]!!),
                saslMechanism = this[Environments.kafkaSaslMechanism]?.let(KafkaSaslMechanism::valueOf),
                username = this[Environments.kafkaUsername],
                hasPassword = this[Environments.kafkaPassword] != null,
            )
        },
        postgres = this[Environments.pgJdbcUrl]?.let {
            PostgresTargetResponse(it, this[Environments.pgUsername].orEmpty(), this[Environments.pgPassword] != null)
        },
        createdAt = this[Environments.createdAt],
        updatedAt = this[Environments.updatedAt],
    )
}
