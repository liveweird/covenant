package ch.nokillswit.contracts

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
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
import io.ktor.server.routing.routing

/** The document store paths, the live check, the import pair and the URL fetch. */
private val WITH_FINDINGS = setOf(ImportStatus.CREATED_WITH_FINDINGS, ImportStatus.VERSION_ADDED_WITH_FINDINGS)

fun Application.configureContractVersionRoutes() {
    val contractService = attributes[ContractServiceKey]
    val versionService = attributes[ContractVersionServiceKey]
    val eventService = attributes[ContractEventServiceKey]
    val checks = attributes[ChecksServiceKey]
    val importer = ContractImporter(contractService, versionService, checks)
    val maxDocumentBytes = environment.config.propertyOrNull(
        "contracts.maxDocumentBytes",
    )?.getString()?.toLongOrNull() ?: DEFAULT_MAX_DOCUMENT_BYTES
    // Stateless, no DB — lazy so the test seam (ContractUrlFetcherKey) can supply a fixture fetcher.
    val urlFetcher by lazy { attributes.getOrNull(ContractUrlFetcherKey) ?: ContractUrlFetcher() }

    fun requireDocumentSize(content: String) {
        if (content.toByteArray().size > maxDocumentBytes) {
            throw PayloadTooLargeException(maxDocumentBytes)
        }
    }

    routing {
        authenticate {
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
                requireDocumentSize(request.content)
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
                eventService.record(
                    contractId,
                    caller.userId,
                    ContractEventType.VERSION_CREATED,
                    mapOf("version" to saved.response.version),
                )
                call.response.header(
                    HttpHeaders.Location,
                    call.application.href(
                        ContractsRoute.Id.Versions.Vid(ContractsRoute.Id.Versions(ContractsRoute.Id(id = contractId)), saved.response.id),
                    ),
                )
                call.respond(HttpStatusCode.Created, saved.response)
            }
            get<ContractsRoute.Id.Versions.Vid> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, versionService.read(route.parent.parent.id, route.vid).orNotFound("Version"))
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
            put<ContractsRoute.Id.Versions.Vid.Content> { route ->
                val caller = call.caller()
                val contractId = route.parent.parent.parent.id
                contractService.authorizeWrite(caller, contractId)
                val request = call.receive<VersionContentRequest>()
                requireDocumentSize(request.content)
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
                eventService.record(
                    contractId,
                    caller.userId,
                    ContractEventType.VERSION_CONTENT_UPDATED,
                    mapOf("version" to saved.response.version),
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
                eventService.record(
                    contractId, caller.userId, ContractEventType.VERSION_TRANSITIONED,
                    mapOf("version" to response.version, "from" to from.name, "to" to request.to.name),
                )
                call.respond(HttpStatusCode.OK, response)
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
                eventService.record(contractId, caller.userId, ContractEventType.VERSION_RECHECKED, mapOf("version" to response.version))
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
                eventService.record(contractId, caller.userId, ContractEventType.VERSION_DELETED, mapOf("version" to version))
                call.respond(HttpStatusCode.NoContent)
            }
            post<ContractsRoute.Versions.Check> {
                call.caller()
                // The live check: a pure computation on an in-progress document — findings-so-far,
                // HARD ones included as findings, never a 400 for document problems. No audit.
                val request = call.receive<DocumentCheckRequest>()
                requireDocumentSize(request.content)
                val report = checks.check(
                    request.type,
                    request.content,
                    declaredVersion = request.version?.trim()?.takeIf { it.isNotEmpty() },
                )
                call.auditCheckerUnavailable(report)
                call.respond(HttpStatusCode.OK, report)
            }
            post<ContractsRoute.Import.Check> {
                val caller = call.caller()
                val request = call.receive<ImportRequest>()
                if (request.items.size > MAX_IMPORT_ITEMS) throw BadRequestException("items must have at most $MAX_IMPORT_ITEMS entries")
                request.items.forEach { requireDocumentSize(it.content) }
                call.respond(HttpStatusCode.OK, ImportResponse(importer.run(request, caller, store = false)))
            }
            post<ContractsRoute.Import> {
                val caller = call.caller()
                val request = call.receive<ImportRequest>()
                if (request.items.size > MAX_IMPORT_ITEMS) throw BadRequestException("items must have at most $MAX_IMPORT_ITEMS entries")
                request.items.forEach { requireDocumentSize(it.content) }
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
                    eventService.record(contractId, caller.userId, ContractEventType.IMPORTED, mapOf("version" to row.version))
                }
                call.respond(HttpStatusCode.OK, ImportResponse(results))
            }
            post<ContractsRoute.Fetch> {
                val caller = call.caller()
                val request = call.receive<FetchUrlRequest>()
                val fetched = try {
                    urlFetcher.fetch(request.url)
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
    }
}

private const val DEFAULT_MAX_DOCUMENT_BYTES = 2L * 1024 * 1024

private fun ApplicationCall.auditCheckerUnavailableFor(version: VersionResponse) {
    if (!version.checkComplete) audit("checker.unavailable", "path" to request.local.uri)
}

/** A filename-safe slug: letters, digits, dot, dash, underscore; everything else collapses to `-`. */
internal fun slug(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "document" }
