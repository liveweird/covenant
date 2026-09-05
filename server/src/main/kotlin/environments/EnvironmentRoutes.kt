package ch.nokillswit.environments

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/environments")
class EnvironmentsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: EnvironmentsRoute = EnvironmentsRoute(), val id: UInt)
}

/** The registry routes — the `systems/` shape: reads any authenticated user, writes ADMIN before the body decodes. */
fun Application.configureEnvironmentRoutes() {
    val environmentService = attributes[EnvironmentServiceKey]

    fun EnvironmentRequest.targets() = listOfNotNull(
        httpBaseUrl?.let { "http" },
        kafka?.let { "kafka" },
        postgres?.let { "postgres" },
    ).joinToString(",")

    routing {
        authenticate {
            get<EnvironmentsRoute> {
                call.caller()
                val paging = call.parsePaging(sortable = ENVIRONMENT_SORT_FIELDS)
                val params = call.request.queryParameters
                val filter = EnvironmentListFilter(name = params.optionalString("name"), systemId = params.optionalUInt("systemId"))
                val result = environmentService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<EnvironmentsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedEnvironmentRequest(call.receive())
                validateEnvironmentRequest(request, passwordRequired = true)
                val id = environmentService.create(request)
                audit(
                    "environment.created",
                    "byUserId" to caller.userId.toLong(),
                    "environmentId" to id.toLong(),
                    "systemId" to request.systemId.toLong(),
                    "name" to request.name,
                    "targets" to request.targets(),
                )
                call.response.header(HttpHeaders.Location, call.application.href(EnvironmentsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, environmentService.read(id).orVanished("Environment", id))
            }
            get<EnvironmentsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, environmentService.read(route.id).orNotFound("Environment"))
            }
            put<EnvironmentsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedEnvironmentRequest(call.receive())
                // The route knows nothing about stored passwords: it validates shapes; the service decides
                // whether a credential's password may stay absent (one is stored) or is required.
                validateEnvironmentRequest(request, passwordRequired = false)
                environmentService.update(route.id, request).orNotFound("Environment")
                audit(
                    "environment.updated",
                    "byUserId" to caller.userId.toLong(),
                    "environmentId" to route.id.toLong(),
                    "systemId" to request.systemId.toLong(),
                    "name" to request.name,
                    "targets" to request.targets(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<EnvironmentsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                environmentService.delete(route.id).orNotFound("Environment")
                audit("environment.deleted", "byUserId" to caller.userId.toLong(), "environmentId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
