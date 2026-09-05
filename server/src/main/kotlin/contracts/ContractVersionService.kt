package ch.nokillswit.contracts

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.contracts.checks.Baseline
import ch.nokillswit.contracts.checks.CheckReport
import ch.nokillswit.contracts.checks.ChecksService
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.security.MessageDigest

val ContractVersionServiceKey = AttributeKey<ContractVersionService>("ContractVersionService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ContractVersionService.ContractVersions.id,
    "version" to ContractVersionService.ContractVersions.semverMajor, // see [applySemverSort]
    "lifecycle" to ContractVersionService.ContractVersions.lifecycle,
    "createdAt" to ContractVersionService.ContractVersions.createdAt,
    "updatedAt" to ContractVersionService.ContractVersions.updatedAt,
)

val VERSION_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** Highest SemVer first — the versions list's default (`-version`). */
val VERSION_DEFAULT_SORT: List<SortField> = listOf(SortField("version", descending = true))

/** What a store path produced: the row's fresh response plus what it waived (for the audit). */
data class VersionSaveResult(val response: VersionResponse, val waived: List<Finding>)

class ContractVersionService(private val database: R2dbcDatabase, private val checks: ChecksService) {
    object ContractVersions : UIntIdTable("contract_versions") {
        val contractId = reference("contract_id", ContractService.Contracts)
        val version = varchar("version", length = SemVer.MAX_LENGTH)
        val semverMajor = integer("semver_major")
        val semverMinor = integer("semver_minor")
        val semverPatch = integer("semver_patch")
        val semverPrerelease = varchar("semver_prerelease", length = 100).nullable()
        val lifecycle = varchar("lifecycle", length = 10).default(Lifecycle.DRAFT.name)
        val format = varchar("format", length = 4)
        val content = text("content")
        val contentSha256 = char("content_sha256", length = 64)
        val docTitle = varchar("doc_title", length = 200).nullable()
        val docDescription = varchar("doc_description", length = 2000).nullable()
        val specVersion = varchar("spec_version", length = 20).nullable()
        val findings = text("findings").default("[]")
        val checkErrors = integer("check_errors").default(0)
        val checkWarnings = integer("check_warnings").default(0)
        val checkInfos = integer("check_infos").default(0)
        val checkComplete = bool("check_complete").default(true)
        val checkedAt = long("checked_at").default(0)
        val createdBy = reference("created_by", ch.nokillswit.users.UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        // V12 — the repo reference and the sync baseline (row state beside the byte-exact text).
        val sourceUrl = varchar("source_url", length = MAX_FETCH_URL_LENGTH).nullable()
        val lastSyncedAt = long("last_synced_at").default(0)
        val syncedContent = text("synced_content").nullable()
    }

    private val json = Json
    private val findingsSerializer = ListSerializer(Finding.serializer())

    private fun active(): Op<Boolean> = ContractVersions.markedAsDeleted eq false

    private fun now() = System.currentTimeMillis()

    suspend fun list(contractId: UInt, filter: VersionListFilter, paging: PageRequest): VersionListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = (ContractVersions.contractId eq contractId) and active()
        if (filter.lifecycles.isNotEmpty()) predicate = predicate and (ContractVersions.lifecycle inList filter.lifecycles.map { it.name })
        val total = ContractVersions.selectAll().where { predicate }.count()
        val rows = ContractVersions.selectAll().where { predicate }.applySemverSort(paging).toList()
        VersionListResult(items = rows.map { it.toListItem() }, total = total)
    }

    suspend fun read(contractId: UInt, versionId: UInt): VersionResponse? = suspendTransaction(database) {
        rowOf(contractId, versionId)?.toResponse()
    }

    /**
     * Creates a DRAFT: the SemVer must parse and exceed every existing version (400), the
     * document must pass the HARD gate (400), and SOFT errors block unless waived. The check
     * report is stored beside the text in the same transaction; the duplicate version is the
     * V10 partial index's 409.
     */
    suspend fun create(
        contractId: UInt,
        type: ContractType,
        request: VersionCreateRequest,
        createdBy: UInt,
        allowInvalid: Boolean,
    ): VersionSaveResult {
        val semver = SemVer.parse(request.version)
        val source = sanitizedSourceUrl(request.sourceUrl) // re-checked service-side too
        val baseline = baselineFor(contractId, semver)
        val report = checks.check(
            type, request.content, declaredVersion = request.version, lifecycle = Lifecycle.DRAFT, baseline = baseline,
        )
        val waived = requireOrWaive(report, allowInvalid)
        return suspendTransaction(database) {
            requireContract(contractId)
            // The exact duplicate is the 409 (the row exists, ahead of the partial index); anything
            // else at or below the top is the 400 the SemVer rule owns.
            if (versionExists(contractId, request.version)) throw ConflictException("Version ${request.version} already exists")
            highest(contractId)?.let { top ->
                if (semver <= top) throw BadRequestException(
                    "Version ${request.version} must be greater than the highest existing version $top",
                )
            }
            val stamp = now()
            val id = ContractVersions.insert {
                it[ContractVersions.contractId] = contractId
                it[version] = request.version
                it[semverMajor] = semver.major
                it[semverMinor] = semver.minor
                it[semverPatch] = semver.patch
                it[semverPrerelease] = semver.prerelease
                it[lifecycle] = Lifecycle.DRAFT.name
                it[format] = report.format!!.name
                it[content] = request.content
                it[contentSha256] = sha256(request.content)
                it[docTitle] = report.title
                it[docDescription] = report.description
                it[specVersion] = report.specVersion
                it[findings] = json.encodeToString(findingsSerializer, report.findings)
                it[checkErrors] = report.errors
                it[checkWarnings] = report.warnings
                it[checkInfos] = report.infos
                it[checkComplete] = report.checkerAvailable
                it[checkedAt] = stamp
                it[ContractVersions.createdBy] = createdBy
                it[createdAt] = stamp
                it[updatedAt] = stamp
                // A text pulled from its repo copy is, right now, in sync with it.
                it[sourceUrl] = source
                it[lastSyncedAt] = if (source != null) stamp else 0
                it[syncedContent] = if (source != null) request.content else null
            }[ContractVersions.id].value
            recomputeLatest(contractId)
            VersionSaveResult(rowOf(contractId, id)!!.toResponse(), waived)
        }
    }

    /** Replaces the text of a DRAFT/PROPOSED version (409 otherwise); the same HARD/SOFT gate as create. */
    suspend fun updateContent(
        contractId: UInt,
        versionId: UInt,
        type: ContractType,
        content: String,
        allowInvalid: Boolean,
    ): VersionSaveResult = storeContent(contractId, versionId, type, content, allowInvalid, sync = false)

    /**
     * The repo → Covenant sync: the repo copy (fetched client-side through the guarded
     * `POST /contracts/fetch`) replaces the text and stamps the sync state. Soft findings are
     * ALWAYS waived (the import posture — the repo is the source of truth), HARD ones stay a 400,
     * the lifecycle lock stays a 409, and a version without a reference is a 400.
     */
    suspend fun sync(contractId: UInt, versionId: UInt, type: ContractType, content: String): VersionSaveResult =
        storeContent(contractId, versionId, type, content, allowInvalid = true, sync = true)

    /**
     * Sets or clears the version's repo reference (any lifecycle — a reference is metadata, not
     * text). A CHANGED reference resets the sync state (a different source was never synced
     * from) and never bumps `updatedAt` (that signal means "text edited since the sync").
     * Returns the change, or null for a missing version.
     */
    suspend fun updateSource(contractId: UInt, versionId: UInt, sourceUrl: String?): SourceChange? = suspendTransaction(database) {
        val next = sanitizedSourceUrl(sourceUrl) // re-checked service-side too
        val row = rowOf(contractId, versionId) ?: return@suspendTransaction null
        val previous = row[ContractVersions.sourceUrl]
        if (previous != next) {
            ContractVersions.update({ (ContractVersions.id eq versionId) and active() }) {
                it[ContractVersions.sourceUrl] = next
                it[lastSyncedAt] = 0
                it[syncedContent] = null
            }
        }
        SourceChange(row[ContractVersions.version], previous, next)
    }

    suspend fun syncState(contractId: UInt, versionId: UInt): SyncStateResponse? = suspendTransaction(database) {
        rowOf(contractId, versionId)?.let {
            SyncStateResponse(it[ContractVersions.sourceUrl], it[ContractVersions.lastSyncedAt], it[ContractVersions.syncedContent])
        }
    }

    private suspend fun storeContent(
        contractId: UInt,
        versionId: UInt,
        type: ContractType,
        content: String,
        allowInvalid: Boolean,
        sync: Boolean,
    ): VersionSaveResult {
        val current = suspendTransaction(
            database,
        ) { rowOf(contractId, versionId)?.toResponse() } ?: throw NotFoundException("Version not found")
        if (!current.lifecycle.contentEditable) throw ConflictException(
            "A ${current.lifecycle} version's document is read-only — create a new version instead",
        )
        if (sync && current.sourceUrl == null) throw BadRequestException("The version has no source reference to sync from")
        val baseline = baselineFor(contractId, SemVer.parse(current.version))
        val report = checks.check(type, content, declaredVersion = current.version, lifecycle = current.lifecycle, baseline = baseline)
        val waived = requireOrWaive(report, allowInvalid)
        return suspendTransaction(database) {
            val stamp = now()
            val rows = ContractVersions.update(
                {
                    (ContractVersions.id eq versionId) and (ContractVersions.contractId eq contractId) and active() and
                        (ContractVersions.lifecycle inList Lifecycle.entries.filter { it.contentEditable }.map { it.name })
                },
            ) {
                it[format] = report.format!!.name
                it[ContractVersions.content] = content
                it[contentSha256] = sha256(content)
                it[docTitle] = report.title
                it[docDescription] = report.description
                it[specVersion] = report.specVersion
                it[findings] = json.encodeToString(findingsSerializer, report.findings)
                it[checkErrors] = report.errors
                it[checkWarnings] = report.warnings
                it[checkInfos] = report.infos
                it[checkComplete] = report.checkerAvailable
                it[checkedAt] = stamp
                it[updatedAt] = stamp
                if (sync) {
                    // The same stamp on both: updatedAt > lastSyncedAt is exactly "edited since".
                    it[lastSyncedAt] = stamp
                    it[syncedContent] = content
                }
            }
            if (rows == 0) throw ConflictException("The version changed underneath you — reload and retry")
            VersionSaveResult(rowOf(contractId, versionId)!!.toResponse(), waived)
        }
    }

    /**
     * Re-runs the pipeline on the stored text (the recovery after a checker outage); no waiver
     * question — nothing new is stored but the report.
     */
    suspend fun recheck(contractId: UInt, versionId: UInt, type: ContractType): VersionResponse {
        val current = suspendTransaction(
            database,
        ) { rowOf(contractId, versionId)?.toResponse() } ?: throw NotFoundException("Version not found")
        val baseline = baselineFor(contractId, SemVer.parse(current.version))
        val report = checks.check(
            type, current.content, declaredVersion = current.version, lifecycle = current.lifecycle, baseline = baseline,
        )
        return suspendTransaction(database) {
            ContractVersions.update({ (ContractVersions.id eq versionId) and active() }) {
                it[findings] = json.encodeToString(findingsSerializer, report.findings)
                it[checkErrors] = report.errors
                it[checkWarnings] = report.warnings
                it[checkInfos] = report.infos
                it[checkComplete] = report.checkerAvailable
                it[checkedAt] = now()
            }
            rowOf(contractId, versionId)!!.toResponse()
        }
    }

    /** The lifecycle move; returns (from, response). An illegal or stale transition is a 409. */
    suspend fun transition(
        contractId: UInt,
        versionId: UInt,
        to: Lifecycle,
    ): Pair<Lifecycle, VersionResponse> = suspendTransaction(database) {
        val row = rowOf(contractId, versionId) ?: throw NotFoundException("Version not found")
        val from = Lifecycle.valueOf(row[ContractVersions.lifecycle])
        from.requireTransitionTo(to)
        val rows = ContractVersions.update(
            { (ContractVersions.id eq versionId) and active() and (ContractVersions.lifecycle eq from.name) },
        ) {
            it[lifecycle] = to.name
            it[updatedAt] = now()
        }
        if (rows == 0) throw ConflictException("The version changed underneath you — reload and retry")
        from to rowOf(contractId, versionId)!!.toResponse()
    }

    /** Only a DRAFT may be deleted (409 otherwise); the latest pointer is recomputed. Returns the deleted version's string. */
    suspend fun delete(contractId: UInt, versionId: UInt): String = suspendTransaction(database) {
        val row = rowOf(contractId, versionId) ?: throw NotFoundException("Version not found")
        val lifecycle = Lifecycle.valueOf(row[ContractVersions.lifecycle])
        if (!lifecycle.deletable) throw ConflictException(
            "Only a DRAFT version may be deleted — a $lifecycle version is part of the contract's history",
        )
        ContractVersions.update({ (ContractVersions.id eq versionId) and active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = now()
        }
        recomputeLatest(contractId)
        row[ContractVersions.version]
    }

    /** The highest active version of a contract, in full SemVer precedence — or null. */
    suspend fun highestOf(contractId: UInt): SemVer? = suspendTransaction(database) { highest(contractId) }

    /**
     * The breaking-change baseline for a candidate: the contract's highest ACTIVE version strictly
     * BELOW it (a recheck of an older version compares against ITS predecessor, never a successor);
     * no `below` = the highest ACTIVE version. Null when nothing is published yet.
     */
    suspend fun baselineFor(contractId: UInt, below: SemVer?): Baseline? = suspendTransaction(database) {
        val v = ContractVersions
        v.select(v.version, v.content)
            .where { (v.contractId eq contractId) and (v.lifecycle eq Lifecycle.ACTIVE.name) and active() }
            .map { SemVer.parse(it[v.version]) to it[v.content] }
            .toList()
            .filter { (semver, _) -> below == null || semver < below }
            .maxByOrNull { it.first }
            ?.let { (semver, content) -> Baseline(semver, content) }
    }

    // ---- internals -----------------------------------------------------------------------

    /** HARD findings are a 400 always; SOFT errors block unless waived; returns what was waived. */
    private fun requireOrWaive(report: CheckReport, allowInvalid: Boolean): List<Finding> {
        if (report.hardFindings.isNotEmpty()) {
            throw BadRequestException(report.hardFindings.joinToString("; ") { it.message })
        }
        val soft = report.softErrors
        if (soft.isNotEmpty() && !allowInvalid) {
            val listed = soft.take(MAX_LISTED).joinToString("; ") { "${it.code}: ${it.message}" }
            throw BadRequestException("The document has ${soft.size} blocking finding(s): $listed")
        }
        return soft
    }

    private suspend fun requireContract(contractId: UInt) {
        val c = ContractService.Contracts
        val ok = c.select(c.id).where { (c.id eq contractId) and (c.markedAsDeleted eq false) }.count() > 0
        if (!ok) throw NotFoundException("Contract not found")
    }

    /** Loads the contract's active versions (a small set) and compares in Kotlin — full SemVer precedence. */
    /** Whether an active row carries exactly this version string (the importer's CONFLICT prediction). */
    suspend fun exists(contractId: UInt, version: String): Boolean = suspendTransaction(database) { versionExists(contractId, version) }

    private suspend fun versionExists(contractId: UInt, version: String): Boolean =
        ContractVersions.select(ContractVersions.id)
            .where { (ContractVersions.contractId eq contractId) and (ContractVersions.version eq version) and active() }
            .count() > 0

    private suspend fun highest(contractId: UInt): SemVer? =
        ContractVersions.select(ContractVersions.version)
            .where { (ContractVersions.contractId eq contractId) and active() }
            .map { SemVer.parse(it[ContractVersions.version]) }
            .toList()
            .maxOrNull()

    /** The denormalized pointer on `contracts` (V10) — the highest active version's id, or null. */
    private suspend fun recomputeLatest(contractId: UInt) {
        val top = ContractVersions.select(ContractVersions.id, ContractVersions.version)
            .where { (ContractVersions.contractId eq contractId) and active() }
            .map { it[ContractVersions.id].value to SemVer.parse(it[ContractVersions.version]) }
            .toList()
            .maxByOrNull { it.second }
        val c = ContractService.Contracts
        c.update({ c.id eq contractId }) { it[latestVersionId] = top?.first }
    }

    private suspend fun rowOf(contractId: UInt, versionId: UInt): ResultRow? =
        ContractVersions.selectAll()
            .where { (ContractVersions.id eq versionId) and (ContractVersions.contractId eq contractId) and active() }
            .toList().singleOrNull()

    /** `version` sorts by the parsed triple (prerelease NULL = release, ranked above); other fields as usual. */
    private fun Query.applySemverSort(paging: PageRequest): Query {
        val v = ContractVersions
        val expanded = paging.sort.flatMap { sf ->
            if (sf.name == "version") {
                val order = if (sf.descending) SortOrder.DESC else SortOrder.ASC
                val nulls = if (sf.descending) SortOrder.DESC_NULLS_FIRST else SortOrder.ASC_NULLS_LAST
                listOf(v.semverMajor to order, v.semverMinor to order, v.semverPatch to order, v.semverPrerelease to nulls)
            } else {
                val column = SORTABLE_COLUMNS[sf.name] ?: error("unsortable field ${sf.name}")
                listOf(column to if (sf.descending) SortOrder.DESC else SortOrder.ASC)
            }
        }
        return orderBy(*expanded.toTypedArray()).limit(paging.pageSize).offset(((paging.page - 1) * paging.pageSize).toLong())
    }

    private fun ResultRow.toResponse() = VersionResponse(
        id = this[ContractVersions.id].value,
        contractId = this[ContractVersions.contractId].value,
        version = this[ContractVersions.version],
        lifecycle = Lifecycle.valueOf(this[ContractVersions.lifecycle]),
        format = DocumentFormat.valueOf(this[ContractVersions.format]),
        content = this[ContractVersions.content],
        contentSha256 = this[ContractVersions.contentSha256],
        docTitle = this[ContractVersions.docTitle],
        docDescription = this[ContractVersions.docDescription],
        specVersion = this[ContractVersions.specVersion],
        findings = json.decodeFromString(findingsSerializer, this[ContractVersions.findings]),
        checkErrors = this[ContractVersions.checkErrors],
        checkWarnings = this[ContractVersions.checkWarnings],
        checkInfos = this[ContractVersions.checkInfos],
        checkComplete = this[ContractVersions.checkComplete],
        checkedAt = this[ContractVersions.checkedAt],
        createdBy = this[ContractVersions.createdBy].value,
        createdAt = this[ContractVersions.createdAt],
        updatedAt = this[ContractVersions.updatedAt],
        sourceUrl = this[ContractVersions.sourceUrl],
        lastSyncedAt = this[ContractVersions.lastSyncedAt],
    )

    private fun ResultRow.toListItem() = VersionListItem(
        id = this[ContractVersions.id].value,
        contractId = this[ContractVersions.contractId].value,
        version = this[ContractVersions.version],
        lifecycle = Lifecycle.valueOf(this[ContractVersions.lifecycle]),
        format = DocumentFormat.valueOf(this[ContractVersions.format]),
        docTitle = this[ContractVersions.docTitle],
        specVersion = this[ContractVersions.specVersion],
        checkErrors = this[ContractVersions.checkErrors],
        checkWarnings = this[ContractVersions.checkWarnings],
        checkInfos = this[ContractVersions.checkInfos],
        checkComplete = this[ContractVersions.checkComplete],
        createdBy = this[ContractVersions.createdBy].value,
        createdAt = this[ContractVersions.createdAt],
        updatedAt = this[ContractVersions.updatedAt],
        sourceUrl = this[ContractVersions.sourceUrl],
        lastSyncedAt = this[ContractVersions.lastSyncedAt],
    )

    private companion object {
        const val MAX_LISTED = 5
    }
}

/** What `updateSource` did: the version's number and the reference before/after (equal = no-op). */
data class SourceChange(val version: String, val previous: String?, val next: String?) {
    val changed: Boolean get() = previous != next
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
