package ch.nokillswit.contracts

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.contracts.checks.Baseline
import ch.nokillswit.contracts.checks.BreakingChanges
import ch.nokillswit.contracts.checks.CompatibilityReport
import ch.nokillswit.contracts.checks.VersionRef
import ch.nokillswit.contracts.render.ContractRenderer
import ch.nokillswit.contracts.checks.ChecksServiceKey
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.auditCheckerUnavailable
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import java.net.URI

/** The document store paths, the live check, the import pair and the URL fetch. */
private val WITH_FINDINGS = setOf(ImportStatus.CREATED_WITH_FINDINGS, ImportStatus.VERSION_ADDED_WITH_FINDINGS)

/** What the version handlers reach for — built once by [configureContractVersionRoutes]. */
private class VersionRouteDeps(
    val contractService: ContractService,
    val versionService: ContractVersionService,
    val activity: ContractActivity,
    val checks: ch.nokillswit.contracts.checks.ChecksService,
    val importer: ContractImporter,
    private val maxDocumentBytes: Long,
) {
    fun requireDocumentSize(content: String) {
        if (content.toByteArray().size > maxDocumentBytes) throw PayloadTooLargeException(maxDocumentBytes)
    }
}

fun Application.configureContractVersionRoutes() {
    val deps = VersionRouteDeps(
        contractService = attributes[ContractServiceKey],
        versionService = attributes[ContractVersionServiceKey],
        activity = attributes[ContractActivityKey],
        checks = attributes[ChecksServiceKey],
        importer = attributes[ContractImporterKey],
        maxDocumentBytes = environment.config.propertyOrNull("contracts.maxDocumentBytes")?.getString()?.toLongOrNull()
            ?: DEFAULT_MAX_DOCUMENT_BYTES,
    )
    routing {
        authenticate {
            versionCollection(deps)
            versionReads(deps)
            versionWrites(deps)
            versionSourceAndSync(deps)
            documentChecks(deps)
        }
    }
}

/** A contract's versions: the paged list and the create. */
private fun Route.versionCollection(deps: VersionRouteDeps) {
    val contractService = deps.contractService
    val versionService = deps.versionService
    val activity = deps.activity
    get<ContractsRoute.Id.Versions> { route ->
        val viewer = contractService.viewer(call.caller())
        val contractId = route.parent.id
        contractService.read(contractId, viewer).orNotFound("Contract")
        val paging = call.parsePaging(sortable = VERSION_SORT_FIELDS, defaultSort = VERSION_DEFAULT_SORT)
        val lifecycles = call.request.queryParameters.repeatedValues("lifecycle").map { raw ->
            Lifecycle.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw BadRequestException("Unknown lifecycle: $raw")
        }
        val result = versionService.list(contractId, VersionListFilter(lifecycles), paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    post<ContractsRoute.Id.Versions> { route ->
        val caller = call.caller()
        val contractId = route.parent.id
        contractService.authorizeWrite(caller, contractId)
        val request = call.receive<VersionCreateRequest>()
        deps.requireDocumentSize(request.content)
        val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val saved = versionService.create(contractId, type, request, caller.userId, allowInvalid)
        audit(
            "contract_version.created",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to saved.response.id.toLong(),
            "version" to saved.response.version,
            "waivedFindings" to saved.waived.size,
        )
        call.auditCheckerUnavailableFor(saved.response)
        activity.record(
            contractId,
            caller.userId,
            ContractEventType.VERSION_CREATED,
            mapOf("version" to saved.response.version),
            versionId = saved.response.id,
            breaking = saved.response.storedBreaking(),
        )
        call.response.header(
            HttpHeaders.Location,
            call.application.href(
                ContractsRoute.Id.Versions.Vid(ContractsRoute.Id.Versions(ContractsRoute.Id(id = contractId)), saved.response.id),
            ),
        )
        call.respond(HttpStatusCode.Created, saved.response)
    }
}

/** One version read three ways — the row, the render model, the raw document (or its download) — plus the compatibility report. */
private fun Route.versionReads(deps: VersionRouteDeps) {
    val contractService = deps.contractService
    val versionService = deps.versionService
    val checks = deps.checks
    get<ContractsRoute.Id.Versions.Vid> { route ->
        call.caller()
        call.respond(HttpStatusCode.OK, versionService.read(route.parent.parent.id, route.vid).orNotFound("Version"))
    }
    get<ContractsRoute.Id.Versions.Vid.Model> { route ->
        call.caller()
        val contractId = route.parent.parent.parent.id
        val version = versionService.read(contractId, route.parent.vid).orNotFound("Version")
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val model = ContractRenderer.render(type, version.content)
        call.respondText(ContractRenderer.json.encodeToString(model), ContentType.Application.Json, HttpStatusCode.OK)
    }
    get<ContractsRoute.Id.Versions.Vid.Compatibility> { route ->
        call.caller()
        val contractId = route.parent.parent.parent.id
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val to = versionService.read(contractId, route.parent.vid).orNotFound("Version")
        val toSemVer = SemVer.parse(to.version)
        val fromSide = resolveCompatibilityFrom(versionService, contractId, route.against, toSemVer)
        val outcome = checks.compatibility(type, fromSide.baseline, toSemVer, to.content)
        call.respond(
            HttpStatusCode.OK,
            CompatibilityReport(
                from = fromSide.ref,
                to = VersionRef(to.id, to.version, to.lifecycle),
                verdict = outcome.verdict,
                bump = outcome.bump,
                backward = outcome.backward,
                forward = outcome.forward,
                checkerAvailable = outcome.checkerAvailable,
            ),
        )
    }
    get<ContractsRoute.Id.Versions.Vid.Content> { route ->
        val viewer = contractService.viewer(call.caller())
        val contractId = route.parent.parent.parent.id
        val version = versionService.read(contractId, route.parent.vid).orNotFound("Version")
        val contentType = if (version.format == DocumentFormat.json) ContentType.Application.Json else ContentType.parse(
            "application/yaml",
        )
        if (call.request.queryParameters.optionalBoolean("download") == true) {
            val contract = contractService.read(contractId, viewer).orNotFound("Contract")
            val fileName = "${slug(contract.system.name)}__${slug(contract.name)}__${slug(version.version)}.${version.format.name}"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
            )
        }
        call.respond(TextContent(version.content, contentType, HttpStatusCode.OK))
    }
}

/** The writer's text and lifecycle moves: content, transition, delete. */
private fun Route.versionWrites(deps: VersionRouteDeps) {
    val contractService = deps.contractService
    val versionService = deps.versionService
    val activity = deps.activity
    put<ContractsRoute.Id.Versions.Vid.Content> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        val request = call.receive<VersionContentRequest>()
        deps.requireDocumentSize(request.content)
        val allowInvalid = call.request.queryParameters.optionalBoolean("allowInvalid") ?: false
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val saved = versionService.updateContent(contractId, route.parent.vid, type, request.content, allowInvalid)
        audit(
            "contract_version.content_updated",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to saved.response.id.toLong(),
            "version" to saved.response.version,
            "waivedFindings" to saved.waived.size,
        )
        call.auditCheckerUnavailableFor(saved.response)
        activity.record(
            contractId,
            caller.userId,
            ContractEventType.VERSION_CONTENT_UPDATED,
            mapOf("version" to saved.response.version),
            versionId = saved.response.id,
            breaking = saved.response.storedBreaking(),
        )
        call.respond(HttpStatusCode.OK, saved.response)
    }
    post<ContractsRoute.Id.Versions.Vid.Transition> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        val request = call.receive<TransitionRequest>()
        val (from, response) = versionService.transition(contractId, route.parent.vid, request.to)
        audit(
            "contract_version.transitioned",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to response.id.toLong(),
            "version" to response.version,
            "from" to from.name,
            "to" to request.to.name,
        )
        activity.record(
            contractId, caller.userId, ContractEventType.VERSION_TRANSITIONED,
            mapOf("version" to response.version, "from" to from.name, "to" to request.to.name),
            versionId = response.id,
        )
        call.respond(HttpStatusCode.OK, response)
    }
    delete<ContractsRoute.Id.Versions.Vid> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        val version = versionService.delete(contractId, route.vid)
        audit(
            "contract_version.deleted",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to route.vid.toLong(),
            "version" to version,
        )
        activity.record(contractId, caller.userId, ContractEventType.VERSION_DELETED, mapOf("version" to version))
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The repo reference (V12): the source URL, the sync state, the sync itself, and the recheck. */
private fun Route.versionSourceAndSync(deps: VersionRouteDeps) {
    val contractService = deps.contractService
    val versionService = deps.versionService
    val activity = deps.activity
    put<ContractsRoute.Id.Versions.Vid.Source> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        val request = call.receive<VersionSourceRequest>()
        val sourceUrl = sanitizedSourceUrl(request.sourceUrl)
        val change = versionService.updateSource(contractId, route.parent.vid, sourceUrl).orNotFound("Version")
        if (change.changed) {
            // Scheme/host only — a source URL may embed query-string tokens (the fetch audit's rule).
            audit(
                "contract_version.source_changed",
                "byUserId" to caller.userId.toLong(),
                "contractId" to contractId.toLong(),
                "versionId" to route.parent.vid.toLong(),
                "version" to change.version,
                "host" to (sourceUrl?.let { URI(it).host } ?: ""),
            )
            activity.record(
                contractId, caller.userId, ContractEventType.VERSION_SOURCE_CHANGED,
                mapOf("version" to change.version, "sourceUrl" to (sourceUrl ?: "")),
                versionId = route.parent.vid,
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
    get<ContractsRoute.Id.Versions.Vid.Sync> { route ->
        call.caller()
        val state = versionService.syncState(route.parent.parent.parent.id, route.parent.vid).orNotFound("Version")
        call.respond(HttpStatusCode.OK, state)
    }
    post<ContractsRoute.Id.Versions.Vid.Sync> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        // The repo → Covenant overwrite: the client fetched the reference (POST /contracts/fetch)
        // and shows the diff; the service always waives soft findings (the import posture).
        val request = call.receive<SyncRequest>()
        deps.requireDocumentSize(request.content)
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val saved = versionService.sync(contractId, route.parent.vid, type, request.content)
        audit(
            "contract_version.synced",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to saved.response.id.toLong(),
            "version" to saved.response.version,
            "waivedFindings" to saved.waived.size,
        )
        call.auditCheckerUnavailableFor(saved.response)
        // Recorded even when the repo copy matched: pulling it IS the act (it stamps the sync state).
        activity.record(
            contractId, caller.userId, ContractEventType.VERSION_SYNCED, mapOf("version" to saved.response.version),
            versionId = saved.response.id, breaking = saved.response.storedBreaking(),
        )
        call.respond(HttpStatusCode.OK, saved.response)
    }
    post<ContractsRoute.Id.Versions.Vid.Recheck> { route ->
        val caller = call.caller()
        val contractId = route.parent.parent.parent.id
        contractService.authorizeWrite(caller, contractId)
        val type = contractService.typeOf(contractId).orNotFound("Contract")
        val response = versionService.recheck(contractId, route.parent.vid, type)
        audit(
            "contract_version.rechecked",
            "byUserId" to caller.userId.toLong(),
            "contractId" to contractId.toLong(),
            "versionId" to response.id.toLong(),
        )
        call.auditCheckerUnavailableFor(response)
        activity.record(contractId, caller.userId, ContractEventType.VERSION_RECHECKED, mapOf("version" to response.version))
        call.respond(HttpStatusCode.OK, response)
    }
}

/** The pure computations and the import pair: the live check, the import dry run, the import, the URL fetch. */
private fun Route.documentChecks(deps: VersionRouteDeps) {
    val versionService = deps.versionService
    val activity = deps.activity
    val checks = deps.checks
    val importer = deps.importer
    post<ContractsRoute.Versions.Check> {
        call.caller()
        // The live check: a pure computation on an in-progress document — findings-so-far,
        // HARD ones included as findings, never a 400 for document problems. No audit.
        val request = call.receive<DocumentCheckRequest>()
        deps.requireDocumentSize(request.content)
        val declared = request.version?.trim()?.takeIf { it.isNotEmpty() }
        // Naming the contract adds the breaking-change step against its highest ACTIVE
        // version below the candidate (an unknown id simply has no baseline — no 404 here).
        val baseline = request.contractId?.let { versionService.baselineFor(it, declared?.let(SemVer::parseOrNull)) }
        val report = checks.check(request.type, request.content, declaredVersion = declared, baseline = baseline)
        call.auditCheckerUnavailable(report)
        call.respond(HttpStatusCode.OK, report)
    }
    post<ContractsRoute.Import.Check> {
        val caller = call.caller()
        val request = call.receive<ImportRequest>()
        if (request.items.size > MAX_IMPORT_ITEMS) throw BadRequestException("items must have at most $MAX_IMPORT_ITEMS entries")
        request.items.forEach { deps.requireDocumentSize(it.content) }
        call.respond(HttpStatusCode.OK, ImportResponse(importer.run(request, caller, store = false)))
    }
    post<ContractsRoute.Import> {
        val caller = call.caller()
        val request = call.receive<ImportRequest>()
        if (request.items.size > MAX_IMPORT_ITEMS) throw BadRequestException("items must have at most $MAX_IMPORT_ITEMS entries")
        request.items.forEach { deps.requireDocumentSize(it.content) }
        val results = importer.run(request, caller, store = true)
        for (row in results.filter { it.versionId != null }) {
            val contractId = checkNotNull(row.contractId)
            audit(
                "contract.imported",
                "byUserId" to caller.userId.toLong(),
                "contractId" to contractId.toLong(),
                "versionId" to row.versionId!!.toLong(),
                "version" to row.version,
                "withFindings" to (row.status in WITH_FINDINGS),
            )
            activity.record(
                contractId, caller.userId, ContractEventType.IMPORTED, mapOf("version" to row.version),
                versionId = row.versionId, breaking = row.breaking,
            )
        }
        call.respond(HttpStatusCode.OK, ImportResponse(results))
    }
    post<ContractsRoute.Fetch> {
        val caller = call.caller()
        val request = call.receive<FetchUrlRequest>()
        val fetched = try {
            call.application.attributes[ContractUrlFetcherKey].fetch(request.url)
        } catch (blocked: BlockedUrlException) {
            // A blocked fetch attempt is a probe signal worth keeping; the response stays uniform.
            audit(
                "contract.fetch_blocked",
                "byUserId" to caller.userId.toLong(),
                "scheme" to blocked.scheme,
                "host" to blocked.host,
            )
            throw BadRequestException(FETCH_URL_INVALID_DETAIL)
        }
        audit("contract.fetched", "byUserId" to caller.userId.toLong(), "scheme" to fetched.uri.scheme, "host" to fetched.uri.host)
        call.respond(HttpStatusCode.OK, FetchUrlResponse(content = fetched.content))
    }
}

private fun ApplicationCall.auditCheckerUnavailableFor(version: VersionResponse) {
    if (!version.checkComplete) audit("checker.unavailable", "byUserId" to caller().userId.toLong(), "path" to request.local.uri)
}

/** A filename-safe slug: letters, digits, dot, dash, underscore; everything else collapses to `-`. */
internal fun slug(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "document" }

/** The compatibility report's `from` side: its [VersionRef] (when resolvable) beside the [Baseline] the engines compare against. */
private data class CompatibilityFrom(val ref: VersionRef?, val baseline: Baseline?)

/**
 * `against` given → the full row (id, version, lifecycle all known — malformed/foreign/missing is
 * a 404); omitted → the contract's ACTIVE baseline below `to`, read WITH its row id in ONE
 * transaction (`ContractVersionService.baselineRowFor` — a second lookup could see a version that
 * transitioned in between and answer `from` null beside a `bump`; lifecycle is ACTIVE by
 * construction). No baseline at all → both sides null, `ChecksService.compatibility` answers UNKNOWN.
 */
private suspend fun resolveCompatibilityFrom(
    versionService: ContractVersionService,
    contractId: UInt,
    against: UInt?,
    toVersion: SemVer,
): CompatibilityFrom {
    if (against != null) {
        val row = versionService.read(contractId, against) ?: throw NotFoundException("Version not found")
        return CompatibilityFrom(VersionRef(row.id, row.version, row.lifecycle), Baseline(SemVer.parse(row.version), row.content))
    }
    val row = versionService.baselineRowFor(contractId, toVersion) ?: return CompatibilityFrom(null, null)
    return CompatibilityFrom(VersionRef(row.id, row.baseline.version.toString(), Lifecycle.ACTIVE), row.baseline)
}

/** Whether the stored report carries the waived breaking gate — the one verdict followers are told about. */
private fun VersionResponse.storedBreaking(): Boolean = findings.any { it.code == BreakingChanges.CODE_WITHOUT_MAJOR_BUMP }
