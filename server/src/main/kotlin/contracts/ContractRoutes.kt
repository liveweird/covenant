package ch.nokillswit.contracts

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedEnum
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
import io.ktor.server.routing.Route
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
    @Resource("facets")
    class Facets(val parent: ContractsRoute = ContractsRoute())

    /** The Errors report: versions carrying findings, across the whole catalog. */
    @Serializable
    @Resource("errors")
    class Errors(val parent: ContractsRoute = ContractsRoute()) {
        @Serializable
        @Resource("facets")
        class Facets(val parent: Errors = Errors())
    }

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

    /** The inference engine (`contracts/infer/`): samples in, a draft document out — pure, nothing stored. */
    @Serializable
    @Resource("infer")
    class Infer(val parent: ContractsRoute = ContractsRoute())

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
        @Resource("subscription")
        class Subscription(val parent: Id)

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

                @Serializable
                @Resource("source")
                class Source(val parent: Vid)

                @Serializable
                @Resource("sync")
                class Sync(val parent: Vid)

                /** The reader's render model (contracts/render/). */
                @Serializable
                @Resource("model")
                class Model(val parent: Vid)

                /** The two-way compatibility report against `against` (default: the ACTIVE baseline below this version). */
                @Serializable
                @Resource("compatibility")
                class Compatibility(val parent: Vid, val against: UInt? = null)

                /** The try-it family (contracts/tryit/): the catalog GET, then the HTTP / Kafka / SQL legs. */
                @Serializable
                @Resource("try")
                class Try(val parent: Vid) {
                    @Serializable
                    @Resource("http")
                    class Http(val parent: Try)

                    @Serializable
                    @Resource("kafka")
                    class Kafka(val parent: Try) {
                        @Serializable
                        @Resource("publish")
                        class Publish(val parent: Kafka)

                        @Serializable
                        @Resource("read")
                        class Read(val parent: Kafka)
                    }

                    @Serializable
                    @Resource("sql")
                    class Sql(val parent: Try)
                }
            }
        }
    }
}

/** The list/tree filter parser — one function, so the two endpoints declare the same set. */
internal fun ApplicationCall.contractFilter(): ContractListFilter {
    val params = request.queryParameters
    return ContractListFilter(
        domainId = params.optionalUInt("domainId"),
        systemId = params.optionalUInt("systemId"),
        types = params.repeatedEnum<ContractType>("type"),
        ownerTeamId = params.optionalUInt("ownerTeamId"),
        ownerUserId = params.optionalUInt("ownerUserId"),
        lifecycles = params.repeatedEnum<Lifecycle>("lifecycle"),
        q = params.optionalString("q"),
        hasErrors = params.optionalBoolean("hasErrors"),
    )
}

/**
 * Beside [contractFilter]: the same contract-scoped params (domain/system/type/owner/q), minus
 * the contract list's OWN lifecycle/hasErrors (which read the LATEST version — meaningless here,
 * where every row already IS one specific version), plus that version's own `lifecycle` and the
 * finding `severity`/`source` filters.
 */
internal fun ApplicationCall.errorFilter(): ErrorListFilter {
    val base = contractFilter()
    val params = request.queryParameters
    return ErrorListFilter(
        contracts = base.copy(lifecycles = emptyList(), hasErrors = null),
        lifecycles = base.lifecycles,
        severities = params.repeatedEnum<Severity>("severity"),
        sources = params.repeatedEnum<FindingSource>("source"),
    )
}

fun Application.configureContractRoutes() {
    // Read from the APPLICATION's attributes here — inside `routing {}` the name resolves to the Route's own set.
    val contractService = attributes[ContractServiceKey]
    val activity = attributes[ContractActivityKey]
    val eventService = attributes[ContractEventServiceKey]
    val subscriptions = attributes[ContractSubscriptionServiceKey]
    val errorService = attributes[ContractErrorServiceKey]
    routing {
        authenticate {
            contractCollection(contractService, activity)
            contractItem(contractService, activity, eventService, subscriptions)
            contractErrors(errorService)
        }
    }
}

/** The Errors report: reads only — no audit, no `canWrite` (a finding is not a contract's writable state). */
private fun Route.contractErrors(errors: ContractErrorService) {
    get<ContractsRoute.Errors> {
        call.caller()
        val paging = call.parsePaging(sortable = ERROR_SORT_FIELDS, defaultSort = ERROR_DEFAULT_SORT)
        val result = errors.list(call.errorFilter(), paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<ContractsRoute.Errors.Facets> {
        call.caller()
        call.respond(HttpStatusCode.OK, errors.facets(call.errorFilter()))
    }
}

/** The collection: list, tree, facets, create. */
private fun Route.contractCollection(contractService: ContractService, activity: ContractActivity) {
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
    get<ContractsRoute.Facets> {
        call.caller()
        call.respond(HttpStatusCode.OK, contractService.facets(call.contractFilter()))
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
        activity.record(
            id,
            caller.userId,
            ContractEventType.CREATED,
            mapOf("name" to request.name, "type" to request.type.name),
        )
        call.response.header(HttpHeaders.Location, call.application.href(ContractsRoute.Id(id = id)))
        call.respond(HttpStatusCode.Created, contractService.read(id, contractService.viewer(caller)).orVanished("Contract", id))
    }
}

/** One contract: read, follow, update, owner transfer, delete, export, history. */
private fun Route.contractItem(
    contractService: ContractService,
    activity: ContractActivity,
    eventService: ContractEventService,
    subscriptions: ContractSubscriptionService,
) {
    get<ContractsRoute.Id> { route ->
        val viewer = contractService.viewer(call.caller())
        call.respond(HttpStatusCode.OK, contractService.read(route.id, viewer).orNotFound("Contract"))
    }
    // Following: whoever may read a contract may follow it — idempotent, 404 for a missing one.
    put<ContractsRoute.Id.Subscription> { route ->
        val caller = call.caller()
        if (!subscriptions.subscribe(route.parent.id, caller.userId)) throw NotFoundException("Contract not found")
        audit("contract.subscribed", "byUserId" to caller.userId.toLong(), "contractId" to route.parent.id.toLong())
        call.respond(HttpStatusCode.NoContent)
    }
    delete<ContractsRoute.Id.Subscription> { route ->
        val caller = call.caller()
        subscriptions.unsubscribe(route.parent.id, caller.userId).orNotFound("Subscription")
        audit("contract.unsubscribed", "byUserId" to caller.userId.toLong(), "contractId" to route.parent.id.toLong())
        call.respond(HttpStatusCode.NoContent)
    }
    put<ContractsRoute.Id> { route ->
        val caller = call.caller()
        contractService.authorizeWrite(caller, route.id)
        val request = sanitizedContractUpdate(call.receive())
        validateContractUpdate(request)
        contractService.update(route.id, request).orNotFound("Contract")
        audit("contract.updated", "byUserId" to caller.userId.toLong(), "contractId" to route.id.toLong())
        activity.record(route.id, caller.userId, ContractEventType.UPDATED, mapOf("name" to request.name))
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
        activity.record(
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
        activity.record(route.id, caller.userId, ContractEventType.DELETED)
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
