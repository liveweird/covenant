package ch.nokillswit.domains

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
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
@Resource("/api/v1/domains")
class DomainsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: DomainsRoute = DomainsRoute(), val id: UInt)
}

fun Application.configureDomainRoutes() {
    val domainService = attributes[DomainServiceKey]

    routing {
        authenticate {
            // Reads any-authenticated (the tree, the pickers); mutations ADMIN-only with the
            // guard BEFORE the body decodes and the id lookup (guard-before-read).
            get<DomainsRoute> {
                call.caller()
                val paging = call.parsePaging(sortable = DOMAIN_SORT_FIELDS)
                val filter = DomainListFilter(name = call.request.queryParameters.optionalString("name"))
                val result = domainService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<DomainsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedDomainRequest(call.receive())
                validateDomainRequest(request)
                val id = domainService.create(request)
                audit("domain.created", "byUserId" to caller.userId.toLong(), "domainId" to id.toLong(), "name" to request.name)
                call.response.header(HttpHeaders.Location, call.application.href(DomainsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, domainService.read(id).orVanished("Domain", id))
            }
            get<DomainsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, domainService.read(route.id).orNotFound("Domain"))
            }
            put<DomainsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedDomainRequest(call.receive())
                validateDomainRequest(request)
                domainService.update(route.id, request).orNotFound("Domain")
                audit("domain.updated", "byUserId" to caller.userId.toLong(), "domainId" to route.id.toLong(), "name" to request.name)
                call.respond(HttpStatusCode.NoContent)
            }
            delete<DomainsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                domainService.delete(route.id).orNotFound("Domain")
                audit("domain.deleted", "byUserId" to caller.userId.toLong(), "domainId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
