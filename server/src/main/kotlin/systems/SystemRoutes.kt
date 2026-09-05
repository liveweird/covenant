package ch.nokillswit.systems

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/systems")
class SystemsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: SystemsRoute = SystemsRoute(), val id: UInt)
}

fun Application.configureSystemRoutes() {
    val systemService = attributes[SystemServiceKey]

    routing {
        authenticate {
            get<SystemsRoute> {
                call.caller()
                val paging = call.parsePaging(sortable = SYSTEM_SORT_FIELDS)
                val params = call.request.queryParameters
                val filter = SystemListFilter(name = params.optionalString("name"), domainId = params.optionalUInt("domainId"))
                val result = systemService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<SystemsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedSystemRequest(call.receive())
                validateSystemRequest(request)
                val id = systemService.create(request)
                audit(
                    "system.created",
                    "byUserId" to caller.userId.toLong(),
                    "systemId" to id.toLong(),
                    "domainId" to request.domainId.toLong(),
                    "name" to request.name,
                )
                call.response.header(HttpHeaders.Location, call.application.href(SystemsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, systemService.read(id).orVanished("System", id))
            }
            get<SystemsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, systemService.read(route.id).orNotFound("System"))
            }
            put<SystemsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedSystemRequest(call.receive())
                validateSystemRequest(request)
                systemService.update(route.id, request).orNotFound("System")
                audit(
                    "system.updated",
                    "byUserId" to caller.userId.toLong(),
                    "systemId" to route.id.toLong(),
                    "domainId" to request.domainId.toLong(),
                    "name" to request.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<SystemsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                systemService.delete(route.id).orNotFound("System")
                audit("system.deleted", "byUserId" to caller.userId.toLong(), "systemId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
