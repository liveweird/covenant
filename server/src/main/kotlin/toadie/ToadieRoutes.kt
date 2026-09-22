package ch.nokillswit.toadie

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.contracts.ContractActivityKey
import ch.nokillswit.contracts.ContractActivity
import ch.nokillswit.contracts.ContractEventType
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractServiceKey
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val CONNECTION_SORT_FIELDS = setOf("id", "name", "createdAt", "updatedAt")
private val API_SORT_FIELDS = setOf("id", "title")
private val USAGE_SORT_FIELDS = setOf("id", "title")

@Serializable
@Resource("/api/v1/toadie-connections")
class ToadieConnectionsRoute {
    @Serializable @Resource("{id}")
    class Id(val parent: ToadieConnectionsRoute = ToadieConnectionsRoute(), val id: UInt) {
        @Serializable @Resource("refresh") class Refresh(val parent: Id)
        @Serializable @Resource("apis") class Apis(val parent: Id)
        @Serializable @Resource("registry-candidates") class RegistryCandidates(val parent: Id)
        @Serializable @Resource("registry-sync") class RegistrySync(val parent: Id) {
            @Serializable @Resource("preview") class Preview(val parent: RegistrySync)
        }
    }
}

@Serializable
@Resource("/api/v1/contracts/{contractId}/toadie-links")
class ContractToadieLinksRoute(val contractId: UInt)

@Serializable
@Resource("/api/v1/contracts/{contractId}/toadie-usage")
class ContractToadieUsageRoute(val contractId: UInt) {
    @Serializable @Resource("refresh") class Refresh(val parent: ContractToadieUsageRoute)
}

@Serializable
@Resource("/api/v1/contracts/{contractId}/toadie-adoptions")
class ContractToadieAdoptionsRoute(val contractId: UInt)

fun Application.configureToadieRoutes() {
    val service = attributes[ToadieServiceKey]
    val refresh = attributes[ToadieRefreshCoordinatorKey]
    val contracts = attributes[ContractServiceKey]
    val activity = attributes[ContractActivityKey]

    routing {
        authenticate {
            connectionRoutes(service, refresh, developmentMode)
            registryRoutes(service)
            contractRoutes(service, refresh, contracts, activity)
        }
    }
}

private fun Route.connectionRoutes(
    service: ToadieService,
    refresh: ToadieRefreshCoordinator,
    allowHttp: Boolean,
) {
            get<ToadieConnectionsRoute> {
                call.caller()
                val paging = call.parsePaging(CONNECTION_SORT_FIELDS)
                val result = service.list(paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<ToadieConnectionsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizeToadieRequest(call.receive())
                validateToadieRequest(request, apiKeyRequired = true, allowHttp = allowHttp)
                val id = service.create(request)
                audit(
                    "toadie_connection.created", "byUserId" to caller.userId.toLong(),
                    "connectionId" to id.toLong(), "baseHost" to java.net.URI(request.baseUrl).host,
                )
                if (request.enabled) refresh.request(id, force = false)
                call.response.header(HttpHeaders.Location, call.application.href(ToadieConnectionsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, service.read(id).orVanished("Toadie connection", id))
            }
            get<ToadieConnectionsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, service.read(route.id).orNotFound("Toadie connection"))
            }
            put<ToadieConnectionsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizeToadieRequest(call.receive())
                validateToadieRequest(request, apiKeyRequired = false, allowHttp = allowHttp)
                service.update(route.id, request).orNotFound("Toadie connection")
                audit(
                    "toadie_connection.updated", "byUserId" to caller.userId.toLong(),
                    "connectionId" to route.id.toLong(), "baseHost" to java.net.URI(request.baseUrl).host,
                )
                if (request.enabled) refresh.request(route.id, force = false)
                call.respond(HttpStatusCode.NoContent)
            }
            delete<ToadieConnectionsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                service.delete(route.id).orNotFound("Toadie connection")
                audit("toadie_connection.deleted", "byUserId" to caller.userId.toLong(), "connectionId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
            post<ToadieConnectionsRoute.Id.Refresh> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                respondRefresh(call, refresh.request(route.parent.id, force = true), route.parent.id, caller.userId)
            }
            get<ToadieConnectionsRoute.Id.Apis> { route ->
                call.caller()
                val paging = call.parsePaging(API_SORT_FIELDS)
                val result = service.listApis(route.parent.id, call.request.queryParameters.optionalString("q"), paging)
                    .orNotFound("Toadie connection")
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
}

private fun Route.contractRoutes(
    service: ToadieService,
    refresh: ToadieRefreshCoordinator,
    contracts: ContractService,
    activity: ContractActivity,
) {
            get<ContractToadieLinksRoute> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, service.links(route.contractId).orNotFound("Contract"))
            }
            put<ContractToadieLinksRoute> { route ->
                val caller = call.caller()
                contracts.authorizeWrite(caller, route.contractId)
                val request = call.receive<ToadieLinksRequest>()
                val changed = service.replaceLinks(route.contractId, request, caller)
                if (changed) {
                    audit(
                        "contract.toadie_links_updated", "byUserId" to caller.userId.toLong(),
                        "contractId" to route.contractId.toLong(), "connectionId" to request.connectionId?.toLong(),
                        "linkCount" to request.apiEntityIds.size,
                    )
                    activity.record(
                        route.contractId, caller.userId, ContractEventType.TOADIE_LINKS_UPDATED,
                        mapOf("linkCount" to request.apiEntityIds.size.toString()),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
            get<ContractToadieUsageRoute> { route ->
                call.caller()
                val paging = call.parsePaging(USAGE_SORT_FIELDS)
                val role = call.request.queryParameters.optionalString("role")?.let {
                    runCatching { ToadieUsageRole.valueOf(it.uppercase()) }
                        .getOrElse { throw BadRequestException("role must be PROVIDER or CONSUMER") }
                }
                val result = service.usage(route.contractId, call.request.queryParameters.optionalString("q"), role, paging)
                    .orNotFound("Contract")
                call.respond(HttpStatusCode.OK, result)
            }
            get<ContractToadieAdoptionsRoute> { route ->
                call.caller()
                val paging = call.parsePaging(USAGE_SORT_FIELDS)
                val result = service.adoptions(route.contractId, call.request.queryParameters.optionalString("q"), paging)
                    .orNotFound("Contract")
                call.respond(HttpStatusCode.OK, result)
            }
            post<ContractToadieUsageRoute.Refresh> { route ->
                val caller = call.caller()
                contracts.authorizeWrite(caller, route.parent.contractId)
                val links = service.links(route.parent.contractId).orNotFound("Contract")
                val connectionId = links.connection?.id ?: throw BadRequestException("The contract has no active Toadie connection")
                respondRefresh(call, refresh.request(connectionId, force = true), connectionId, caller.userId)
            }
}

private suspend fun respondRefresh(
    call: ApplicationCall,
    result: RefreshClaimResult,
    connectionId: UInt,
    userId: UInt,
) {
    when (result) {
        RefreshClaimResult.ACCEPTED, RefreshClaimResult.COALESCED -> {
            audit(
                "toadie_connection.refresh_requested", "byUserId" to userId.toLong(),
                "connectionId" to connectionId.toLong(), "coalesced" to (result == RefreshClaimResult.COALESCED),
            )
            call.respond(HttpStatusCode.Accepted)
        }
        RefreshClaimResult.COOLDOWN -> throw TooManyRequestsException("Wait 30 seconds before requesting another refresh")
        RefreshClaimResult.MISSING -> throw ch.nokillswit.authz.NotFoundException("Toadie connection not found")
        RefreshClaimResult.DISABLED -> throw BadRequestException("The Toadie connection is disabled")
    }
}
