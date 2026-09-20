package ch.nokillswit.contracts

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

fun Application.configureVersionReviewRoutes() {
    val contracts = attributes[ContractServiceKey]
    val versions = attributes[ContractVersionServiceKey]
    val reviews = attributes[VersionReviewServiceKey]
    val activity = attributes[ContractActivityKey]
    routing {
        authenticate {
            get<ContractsRoute.Id.Versions.Vid.Reviews> { route ->
                val caller = call.caller()
                val contractId = route.parent.parent.parent.id
                val contract = contracts.read(contractId, contracts.viewer(caller))
                    ?: throw ch.nokillswit.authz.NotFoundException("Contract not found")
                val paging = call.parsePaging(
                    sortable = VERSION_REVIEW_SORT_FIELDS,
                    defaultSort = listOf(SortField("requestedAt", descending = true), SortField("id", descending = true)),
                )
                val result = reviews.list(contractId, route.parent.vid, caller, contract.canWrite, paging)
                call.respond(
                    HttpStatusCode.OK,
                    VersionReviewPageResponse(
                        result.items, paging.page, paging.pageSize, result.total,
                        result.canRequest, result.currentContentRevision,
                    ),
                )
            }
            post<ContractsRoute.Id.Versions.Vid.Reviews> { route ->
                val caller = call.caller()
                val contractId = route.parent.parent.parent.id
                reviews.requireActiveParticipant(caller)
                contracts.authorizeWrite(caller, contractId)
                val request = call.receive<CreateVersionReviewRequest>()
                val review = reviews.request(contractId, route.parent.vid, caller, request.expectedContentRevision)
                val version = versions.read(contractId, review.versionId) ?: error("Reviewed version vanished")
                audit(
                    "contract_version.review_requested",
                    "byUserId" to caller.userId.toLong(),
                    "contractId" to contractId.toLong(),
                    "versionId" to review.versionId.toLong(),
                    "reviewId" to review.id.toLong(),
                    "contentRevision" to review.contentRevision,
                )
                activity.record(
                    contractId, caller.userId, ContractEventType.VERSION_REVIEW_REQUESTED,
                    review.params(version.version), versionId = review.versionId,
                )
                call.response.header(HttpHeaders.Location, call.application.href(VersionReviewsRoute(review.id)))
                call.respond(HttpStatusCode.Created, review)
            }
            get<VersionReviewsRoute> { route ->
                call.respond(HttpStatusCode.OK, reviews.read(route.rid, call.caller()))
            }
            get<VersionReviewsRoute.Entries> { route ->
                call.caller()
                val paging = call.parsePaging(
                    sortable = VERSION_REVIEW_ENTRY_SORT_FIELDS,
                    defaultSort = listOf(SortField("createdAt", descending = false), SortField("id", descending = false)),
                )
                val result = reviews.listEntries(route.parent.rid, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<VersionReviewsRoute.Entries> { route ->
                val caller = call.caller()
                reviews.requireEntryPreamble(route.parent.rid, caller)
                val request = call.receive<CreateVersionReviewEntryRequest>()
                val (entry, review) = reviews.addEntry(route.parent.rid, caller, request)
                val version = versions.read(review.contractId, review.versionId) ?: error("Reviewed version vanished")
                val event = when (entry.kind) {
                    VersionReviewEntryKind.COMMENT -> ContractEventType.VERSION_REVIEW_COMMENTED
                    VersionReviewEntryKind.APPROVED -> ContractEventType.VERSION_REVIEW_APPROVED
                    VersionReviewEntryKind.CHANGES_REQUESTED -> ContractEventType.VERSION_REVIEW_CHANGES_REQUESTED
                }
                audit(
                    "contract_version.review_entry_added",
                    "byUserId" to caller.userId.toLong(),
                    "contractId" to review.contractId.toLong(),
                    "versionId" to review.versionId.toLong(),
                    "reviewId" to review.id.toLong(),
                    "kind" to entry.kind.name,
                    "contentRevision" to review.contentRevision,
                )
                activity.record(
                    review.contractId, caller.userId, event,
                    review.params(version.version, entry.kind), versionId = review.versionId,
                )
                call.response.header(
                    HttpHeaders.Location,
                    call.application.href(
                        VersionReviewsRoute.Entries.Id(VersionReviewsRoute.Entries(VersionReviewsRoute(review.id)), entry.id),
                    ),
                )
                call.respond(HttpStatusCode.Created, entry)
            }
            get<VersionReviewsRoute.Entries.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, reviews.readEntry(route.parent.parent.rid, route.entryId))
            }
        }
    }
}

private fun VersionReviewResponse.params(version: String, kind: VersionReviewEntryKind? = null): Map<String, String> = buildMap {
    put("version", version)
    put("reviewId", id.toString())
    put("contentRevision", contentRevision.toString())
    if (kind != null && kind != VersionReviewEntryKind.COMMENT) put("decision", kind.name)
}
