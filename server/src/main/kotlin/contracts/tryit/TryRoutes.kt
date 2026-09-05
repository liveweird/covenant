package ch.nokillswit.contracts.tryit

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractServiceKey
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ContractVersionService
import ch.nokillswit.contracts.ContractVersionServiceKey
import ch.nokillswit.contracts.ContractsRoute
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.environments.EnvironmentServiceKey
import ch.nokillswit.environments.EnvironmentService
import ch.nokillswit.environments.EnvironmentTargets
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import java.net.URI

/** The host of a `jdbc:postgresql://host:port/db` URL — the one thing about the target the audit records. */
private fun jdbcHost(jdbcUrl: String): String? = runCatching { URI.create(jdbcUrl.removePrefix("jdbc:")).host }.getOrNull()

/** The per-IP bucket the try POSTs share — registered with the others in `configureAuthRoutes`. */
const val TRY_RATE_LIMIT = "tryIt"
const val DEFAULT_TRY_LIMIT_PER_MINUTE = 60

/** Everything a try leg needs, resolved in one preamble — the version, its contract, the decrypted targets, the parsed tree. */
class TryContext(
    val caller: CallerPrincipal,
    val contract: ContractResponse,
    val version: VersionResponse,
    val targets: EnvironmentTargets,
    val root: JsonNode,
)

/**
 * The shared preamble of every try: version 404 → contract 404 → environment 404 → the environment
 * must belong to the contract's system (400) → the contract must be of the leg's type (400) → the
 * stored document must parse (400). The environment's protocol target is the leg's own check.
 */
class TryPreamble(
    private val contracts: ContractService,
    private val versions: ContractVersionService,
    private val environments: EnvironmentService,
) {
    suspend fun resolve(caller: CallerPrincipal, contractId: UInt, vid: UInt, environmentId: UInt, type: ContractType): TryContext {
        val version = versions.read(contractId, vid).orNotFound("Version")
        val contract = contracts.read(contractId, contracts.viewer(caller)).orNotFound("Contract")
        val targets = environments.resolveTarget(environmentId).orNotFound("Environment")
        if (targets.systemId != contract.system.id) throw BadRequestException("The environment belongs to another system")
        if (contract.type != type) throw BadRequestException("This contract is not ${type.name} — the $type leg does not apply")
        val root = (DocumentParser.parse(version.content) as? ParseOutcome.Parsed)?.root
            ?: throw BadRequestException("The stored document does not parse")
        return TryContext(caller, contract, version, targets, root)
    }
}

/**
 * The try-it family under a version (milestone 3c). The catalog is a pure read of the stored
 * document; the try POSTs (HTTP, Kafka, SQL) land leg by leg behind the shared preamble and the
 * `tryIt` rate-limit bucket. Tries reach the SECURITY audit only — never `contract_events`.
 */
fun Application.configureTryRoutes() {
    val contractService = attributes[ContractServiceKey]
    val versionService = attributes[ContractVersionServiceKey]
    val preamble = TryPreamble(contractService, versionService, attributes[EnvironmentServiceKey])

    routing {
        authenticate {
            get<ContractsRoute.Id.Versions.Vid.Try> { route ->
                call.caller()
                val contractId = route.parent.parent.parent.id
                val version = versionService.read(contractId, route.parent.vid).orNotFound("Version")
                val type = contractService.typeOf(contractId).orNotFound("Contract")
                // A stored document parsed at store time; a legacy row that no longer does offers nothing to try.
                val root = (DocumentParser.parse(version.content) as? ParseOutcome.Parsed)?.root
                call.respond(HttpStatusCode.OK, if (root == null) TryCatalogResponse(type) else TryCatalog.build(type, root))
            }
        }
        rateLimit(RateLimitName(TRY_RATE_LIMIT)) {
            authenticate {
                post<ContractsRoute.Id.Versions.Vid.Try.Http> { route ->
                    val caller = call.caller()
                    val contractId = route.parent.parent.parent.parent.id
                    val request = call.receive<TryHttpRequest>()
                    val ctx = preamble.resolve(caller, contractId, route.parent.parent.vid, request.environmentId, ContractType.OPENAPI)
                    val baseUrl = ctx.targets.httpBaseUrl ?: throw BadRequestException("The environment has no HTTP base URL")
                    val prepared = HttpTry.prepare(request, ctx.root, baseUrl)
                    val trail = arrayOf(
                        "byUserId" to caller.userId, "contractId" to contractId, "versionId" to ctx.version.id,
                        "environmentId" to ctx.targets.id, "host" to prepared.uri.host, "method" to prepared.method,
                        "pathTemplate" to request.path,
                    )
                    val observation = try {
                        HttpTry.send(prepared)
                    } catch (e: BadGatewayException) {
                        audit("contract.tried_http", *trail, "outcome" to "unreachable")
                        throw e
                    }
                    audit(
                        "contract.tried_http", *trail,
                        "status" to observation.status, "durationMs" to observation.durationMs, "outcome" to "answered",
                    )
                    val schemas = DocumentSchemas.of(ContractType.OPENAPI, ctx.root)
                    call.respond(
                        HttpStatusCode.OK,
                        TryHttpResponse(
                            url = prepared.uri.toString().substringBefore('?'),
                            status = observation.status,
                            headers = observation.headers,
                            body = observation.body,
                            bodyTruncated = observation.truncated,
                            durationMs = observation.durationMs,
                            conformance = HttpTry.assess(request, prepared, observation, ctx.root, schemas),
                        ),
                    )
                }
                post<ContractsRoute.Id.Versions.Vid.Try.Kafka.Publish> { route ->
                    val caller = call.caller()
                    val contractId = route.parent.parent.parent.parent.parent.id
                    // Writers only, BEFORE the body decodes: the one try that mutates a real system.
                    contractService.authorizeWrite(caller, contractId)
                    val request = call.receive<TryKafkaPublishRequest>()
                    val vid = route.parent.parent.parent.vid
                    val ctx = preamble.resolve(caller, contractId, vid, request.environmentId, ContractType.ASYNCAPI)
                    val target = ctx.targets.kafka ?: throw BadRequestException("The environment has no Kafka cluster")
                    val prepared = KafkaTry.prepare(ctx.root, request.channel, request.message)
                    KafkaTry.validateHeaders(request.headers)
                    val schemas = DocumentSchemas.of(ContractType.ASYNCAPI, ctx.root)
                    val conformance = KafkaTry.assessPublish(ctx.root, schemas, prepared, request.payload)
                    val trail = arrayOf(
                        "byUserId" to caller.userId, "contractId" to contractId, "versionId" to ctx.version.id,
                        "environmentId" to ctx.targets.id, "bootstrap" to target.bootstrapServers, "topic" to prepared.topic,
                    )
                    val published = try {
                        KafkaTry.publish(target, prepared, request)
                    } catch (e: BadGatewayException) {
                        audit("contract.tried_kafka_publish", *trail, "outcome" to "failed")
                        throw e
                    }
                    audit(
                        "contract.tried_kafka_publish", *trail,
                        "partition" to published.metadata.partition(), "offset" to published.metadata.offset(), "outcome" to "published",
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        TryKafkaPublishResponse(
                            topic = prepared.topic,
                            partition = published.metadata.partition(),
                            offset = published.metadata.offset(),
                            timestamp = published.metadata.timestamp(),
                            durationMs = published.durationMs,
                            conformance = conformance,
                        ),
                    )
                }
                post<ContractsRoute.Id.Versions.Vid.Try.Kafka.Read> { route ->
                    val caller = call.caller()
                    val contractId = route.parent.parent.parent.parent.parent.id
                    val request = call.receive<TryKafkaReadRequest>()
                    val vid = route.parent.parent.parent.vid
                    val ctx = preamble.resolve(caller, contractId, vid, request.environmentId, ContractType.ASYNCAPI)
                    val target = ctx.targets.kafka ?: throw BadRequestException("The environment has no Kafka cluster")
                    val prepared = KafkaTry.prepare(ctx.root, request.channel, request.message)
                    val trail = arrayOf(
                        "byUserId" to caller.userId, "contractId" to contractId, "versionId" to ctx.version.id,
                        "environmentId" to ctx.targets.id, "bootstrap" to target.bootstrapServers, "topic" to prepared.topic,
                    )
                    val read = try {
                        KafkaTry.read(target, prepared, request.limit)
                    } catch (e: BadGatewayException) {
                        audit("contract.tried_kafka_read", *trail, "outcome" to "failed")
                        throw e
                    }
                    audit("contract.tried_kafka_read", *trail, "count" to read.records.size, "outcome" to "read")
                    val views = read.records.map { KafkaTry.view(it) }
                    val schemas = DocumentSchemas.of(ContractType.ASYNCAPI, ctx.root)
                    call.respond(
                        HttpStatusCode.OK,
                        TryKafkaReadResponse(
                            topic = prepared.topic,
                            messages = views,
                            reachedEnd = read.reachedEnd,
                            durationMs = read.durationMs,
                            conformance = KafkaTry.assessRead(ctx.root, schemas, prepared, views),
                        ),
                    )
                }
                post<ContractsRoute.Id.Versions.Vid.Try.Sql> { route ->
                    val caller = call.caller()
                    val contractId = route.parent.parent.parent.parent.id
                    val request = call.receive<TrySqlRequest>()
                    val ctx = preamble.resolve(caller, contractId, route.parent.parent.vid, request.environmentId, ContractType.ODCS)
                    val target = ctx.targets.postgres ?: throw BadRequestException("The environment has no PostgreSQL target")
                    val prepared = SqlTry.prepare(request, ctx.root)
                    val trail = arrayOf(
                        "byUserId" to caller.userId, "contractId" to contractId, "versionId" to ctx.version.id,
                        "environmentId" to ctx.targets.id, "host" to jdbcHost(target.jdbcUrl), "dataset" to prepared.quoted(),
                    )
                    val sample = try {
                        SqlTry.execute(prepared, target)
                    } catch (e: BadGatewayException) {
                        audit("contract.tried_sql", *trail, "outcome" to "failed")
                        throw e
                    }
                    val outcome = if (sample.datasetMissing) "dataset_missing" else "sampled"
                    audit(
                        "contract.tried_sql", *trail,
                        "rowCount" to sample.rows.size, "durationMs" to sample.durationMs, "outcome" to outcome,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        TrySqlResponse(
                            statement = prepared.statement,
                            columns = SqlTry.columns(prepared, sample),
                            rows = sample.rows,
                            truncated = sample.truncated,
                            durationMs = sample.durationMs,
                            conformance = SqlTry.assess(prepared, sample),
                        ),
                    )
                }
            }
        }
    }
}
