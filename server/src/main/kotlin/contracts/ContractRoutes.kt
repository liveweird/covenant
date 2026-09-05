package ch.nokillswit.contracts

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
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

/** The contract route family. Literal segments (`tree`, `import`, `fetch`, `versions/check`) win over `{id}` in Ktor's resolution. */
@Serializable
@Resource("/api/v1/contracts")
class ContractsRoute {
    @Serializable
    @Resource("tree")
    class Tree(val parent: ContractsRoute = ContractsRoute())

    @Serializable
    @Resource("import")
    class Import(val parent: ContractsRoute = ContractsRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Import = Import())
    }

    @Serializable
    @Resource("fetch")
    class Fetch(val parent: ContractsRoute = ContractsRoute())

    @Serializable
    @Resource("versions")
    class Versions(val parent: ContractsRoute = ContractsRoute()) {
        @Serializable
        @Resource("check")
        class Check(val parent: Versions = Versions())
    }

    @Serializable
    @Resource("{id}")
    class Id(val parent: ContractsRoute = ContractsRoute(), val id: UInt) {
        @Serializable
        @Resource("owner")
        class Owner(val parent: Id)

        @Serializable
        @Resource("export")
        class Export(val parent: Id)

        @Serializable
        @Resource("events")
        class Events(val parent: Id)

        @Serializable
        @Resource("versions")
        class Versions(val parent: Id) {
            @Serializable
            @Resource("{vid}")
            class Vid(val parent: Versions, val vid: UInt) {
                @Serializable
                @Resource("content")
                class Content(val parent: Vid)

                @Serializable
                @Resource("transition")
                class Transition(val parent: Vid)

                @Serializable
                @Resource("recheck")
                class Recheck(val parent: Vid)
            }
        }
    }
}

/** The list/tree filter parser — one function, so the two endpoints declare the same set. */
internal fun ApplicationCall.contractFilter(): ContractListFilter {
    val params = request.queryParameters
    fun <T : Enum<T>> parseAll(name: String, values: Array<T>): List<T> =
        params.repeatedValues(name).map { raw ->
            values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw BadRequestException("Unknown $name: $raw (allowed: ${values.joinToString { it.name }})")
        }.distinct()
    return ContractListFilter(
        domainId = params.optionalUInt("domainId"),
        systemId = params.optionalUInt("systemId"),
        types = parseAll("type", ContractType.entries.toTypedArray()),
        ownerTeamId = params.optionalUInt("ownerTeamId"),
        ownerUserId = params.optionalUInt("ownerUserId"),
        lifecycles = parseAll("lifecycle", Lifecycle.entries.toTypedArray()),
        q = params.optionalString("q"),
        hasErrors = params.optionalBoolean("hasErrors"),
    )
}

fun Application.configureContractRoutes() {
    val contractService = attributes[ContractServiceKey]
    val eventService = attributes[ContractEventServiceKey]

    routing {
        authenticate {
            // Reads: any authenticated user. Writes: the writer rule (contracts/ContractAccess.kt),
            // checked through contractService.authorizeWrite BEFORE the body decodes (403 wins
            // over 400); ownership transfer is ADMIN only.
            get<ContractsRoute> {
                val viewer = contractService.viewer(call.caller())
                val paging = call.parsePaging(sortable = CONTRACT_SORT_FIELDS)
                val result = contractService.list(call.contractFilter(), paging, viewer)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<ContractsRoute.Tree> {
                val viewer = contractService.viewer(call.caller())
                call.respond(HttpStatusCode.OK, contractService.tree(call.contractFilter(), viewer))
            }
            post<ContractsRoute> {
                val caller = call.caller()
                val request = sanitizedContractCreate(call.receive())
                validateContractCreate(request)
                val id = contractService.create(request, caller)
                audit(
                    "contract.created",
                    "byUserId" to caller.userId.toLong(),
                    "contractId" to id.toLong(),
                    "systemId" to request.systemId.toLong(),
                    "type" to request.type.name,
                    "owner" to ownershipOf(request.ownerTeamId, request.ownerUserId).asParam(),
                )
                eventService.record(
                    id,
                    caller.userId,
                    ContractEventType.CREATED,
                    mapOf("name" to request.name, "type" to request.type.name),
                )
                call.response.header(HttpHeaders.Location, call.application.href(ContractsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, contractService.read(id, contractService.viewer(caller)).orVanished("Contract", id))
            }
            get<ContractsRoute.Id> { route ->
                val viewer = contractService.viewer(call.caller())
                call.respond(HttpStatusCode.OK, contractService.read(route.id, viewer).orNotFound("Contract"))
            }
            put<ContractsRoute.Id> { route ->
                val caller = call.caller()
                contractService.authorizeWrite(caller, route.id)
                val request = sanitizedContractUpdate(call.receive())
                validateContractUpdate(request)
                contractService.update(route.id, request).orNotFound("Contract")
                audit("contract.updated", "byUserId" to caller.userId.toLong(), "contractId" to route.id.toLong())
                eventService.record(route.id, caller.userId, ContractEventType.UPDATED, mapOf("name" to request.name))
                call.respond(HttpStatusCode.NoContent)
            }
            put<ContractsRoute.Id.Owner> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<OwnerUpdateRequest>()
                val ownership = ownershipOf(request.ownerTeamId, request.ownerUserId)
                val previous = contractService.transferOwner(route.parent.id, ownership).orNotFound("Contract")
                audit(
                    "contract.owner_changed",
                    "byUserId" to caller.userId.toLong(),
                    "contractId" to route.parent.id.toLong(),
                    "from" to previous.asParam(),
                    "to" to ownership.asParam(),
                )
                eventService.record(
                    route.parent.id, caller.userId, ContractEventType.OWNER_CHANGED,
                    mapOf("owner.from" to previous.asParam(), "owner.to" to ownership.asParam()),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<ContractsRoute.Id> { route ->
                val caller = call.caller()
                contractService.authorizeWrite(caller, route.id)
                contractService.delete(route.id).orNotFound("Contract")
                audit("contract.deleted", "byUserId" to caller.userId.toLong(), "contractId" to route.id.toLong())
                // The deletion event lands in a history the API can no longer reach — kept for the record.
                eventService.record(route.id, caller.userId, ContractEventType.DELETED)
                call.respond(HttpStatusCode.NoContent)
            }
            get<ContractsRoute.Id.Export> { route ->
                val viewer = contractService.viewer(call.caller())
                call.respond(HttpStatusCode.OK, contractService.export(route.parent.id, viewer).orNotFound("Contract"))
            }
            get<ContractsRoute.Id.Events> { route ->
                val viewer = contractService.viewer(call.caller())
                contractService.read(route.parent.id, viewer).orNotFound("Contract")
                val paging = call.parsePaging(sortable = EVENT_LOG_SORT_FIELDS, defaultSort = EVENT_LOG_DEFAULT_SORT)
                val result = eventService.listFor(route.parent.id, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
        }
    }
}
