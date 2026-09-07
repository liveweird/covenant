package ch.nokillswit.contracts.infer

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.contracts.ContractsRoute
import ch.nokillswit.contracts.DEFAULT_MAX_DOCUMENT_BYTES
import ch.nokillswit.contracts.tryit.HttpTry
import ch.nokillswit.contracts.tryit.KafkaTry
import ch.nokillswit.contracts.tryit.audited
import ch.nokillswit.contracts.tryit.jdbcHost
import ch.nokillswit.environments.EnvironmentService
import ch.nokillswit.environments.EnvironmentServiceKey
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/**
 * The inference engine's routes (release 0.8.0): samples in, a draft document out — computed per
 * call and never stored, exactly like the try-it family. `POST /api/v1/contracts/infer` needs only
 * authentication; no audit (nothing about a contract changed) and no rate-limit bucket (the
 * sample-count/size checks in `validateInferRequest` plus the global request-body ceiling bound
 * the work). The observe legs pull ONE live sample through an Environment — the same trust
 * boundary, the same `HttpTry`/`KafkaTry`/`PostgresRead` code as try-it, behind the shared `tryIt`
 * bucket; any authenticated user (no `canWrite` — the draft is saved through the ordinary
 * version-create path, where the writer guard applies).
 */
fun Application.configureInferRoutes() {
    val maxDocumentBytes = environment.config.propertyOrNull("contracts.maxDocumentBytes")?.getString()?.toLongOrNull()
        ?: DEFAULT_MAX_DOCUMENT_BYTES
    val environments = attributes[EnvironmentServiceKey]
    routing {
        authenticate {
            post<ContractsRoute.Infer> {
                call.caller()
                val request = call.receive<InferRequest>()
                validateInferRequest(request)
                call.respond(HttpStatusCode.OK, Inference.build(request, maxDocumentBytes))
            }
            // The bucket sits INSIDE authenticate, like try-it's: an anonymous probe answers 401
            // without spending a try token.
            rateLimit(RateLimitName(RateLimits.TRY)) {
                observeHttp(environments)
                observeKafka(environments)
                observeSql(environments)
                observeSqlRelations(environments)
            }
        }
    }
}

private fun Route.observeHttp(environments: EnvironmentService) {
    post<ContractsRoute.Infer.Observe.Http> {
        val caller = call.caller()
        val request = call.receive<ObserveHttpRequest>()
        val target = environments.resolveTarget(request.environmentId).orNotFound("Environment")
        val baseUrl = target.httpBaseUrl ?: throw BadRequestException("The environment has no HTTP base URL")
        val prepared = ObserveHttp.prepare(request, baseUrl)
        val trail = arrayOf<Pair<String, Any?>>(
            "byUserId" to caller.userId, "environmentId" to target.id, "host" to prepared.uri.host, "method" to prepared.method,
        )
        val observation = audited(
            "contract.observed_http", trail, failure = "unreachable",
            success = { o -> arrayOf("status" to o.status, "durationMs" to o.durationMs, "outcome" to "answered") },
        ) { HttpTry.send(prepared) }
        val notes = Notes()
        val sample = ObserveHttp.sample(prepared, request.query, observation, notes)
        call.respond(HttpStatusCode.OK, ObserveHttpResponse(sample, notes.all))
    }
}

private fun Route.observeKafka(environments: EnvironmentService) {
    post<ContractsRoute.Infer.Observe.Kafka> {
        val caller = call.caller()
        val request = call.receive<ObserveKafkaRequest>()
        val target = environments.resolveTarget(request.environmentId).orNotFound("Environment")
        val kafka = target.kafka ?: throw BadRequestException("The environment has no Kafka cluster")
        ObserveKafka.validateTopic(request.topic)
        val trail = arrayOf<Pair<String, Any?>>(
            "byUserId" to caller.userId, "environmentId" to target.id, "bootstrap" to kafka.bootstrapServers, "topic" to request.topic,
        )
        val read = audited(
            "contract.observed_kafka", trail, failure = "failed",
            success = { r -> arrayOf("count" to r.records.size, "outcome" to "read") },
        ) { KafkaTry.read(kafka, KafkaTry.Prepared(request.topic, null), request.limit) }
        val notes = Notes()
        val views = read.records.map { KafkaTry.view(it) }
        val sample = ObserveKafka.sample(request.topic, views, notes)
        call.respond(HttpStatusCode.OK, ObserveKafkaResponse(sample, notes.all, read.reachedEnd))
    }
}

private fun Route.observeSql(environments: EnvironmentService) {
    post<ContractsRoute.Infer.Observe.Sql> {
        val caller = call.caller()
        val request = call.receive<ObserveSqlRequest>()
        val target = environments.resolveTarget(request.environmentId).orNotFound("Environment")
        val postgres = target.postgres ?: throw BadRequestException("The environment has no PostgreSQL target")
        val relation = ObserveSql.parseRelation(request.relation)
        val trail = arrayOf<Pair<String, Any?>>(
            "byUserId" to caller.userId, "environmentId" to target.id,
            "host" to jdbcHost(postgres.jdbcUrl), "relation" to relation.qualified,
        )
        val notes = Notes()
        val sample = audited(
            "contract.observed_sql", trail, failure = "failed",
            success = { s -> arrayOf("columnCount" to s.columns.size, "outcome" to "described") },
        ) { ObserveSql.describe(postgres, relation, notes) }
        call.respond(HttpStatusCode.OK, ObserveSqlResponse(sample, notes.all))
    }
}

private fun Route.observeSqlRelations(environments: EnvironmentService) {
    post<ContractsRoute.Infer.Observe.Sql.Relations> {
        val caller = call.caller()
        val request = call.receive<ObserveRelationsRequest>()
        val target = environments.resolveTarget(request.environmentId).orNotFound("Environment")
        val postgres = target.postgres ?: throw BadRequestException("The environment has no PostgreSQL target")
        val trail = arrayOf<Pair<String, Any?>>(
            "byUserId" to caller.userId, "environmentId" to target.id, "host" to jdbcHost(postgres.jdbcUrl), "relation" to null,
        )
        val relations = audited(
            "contract.observed_sql", trail, failure = "failed",
            success = { r -> arrayOf("outcome" to "listed", "count" to r.size) },
        ) { ObserveSql.listRelations(postgres) }
        call.respond(HttpStatusCode.OK, RelationListResponse(relations))
    }
}
