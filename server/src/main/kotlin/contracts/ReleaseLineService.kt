package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.LocalDate
import java.time.format.DateTimeParseException

val ReleaseLineServiceKey = AttributeKey<ReleaseLineService>("ReleaseLineService")

private val RELEASE_LINE_SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ReleaseLineService.ReleaseLines.id,
    "major" to ReleaseLineService.ReleaseLines.major,
    "updatedAt" to ReleaseLineService.ReleaseLines.updatedAt,
)
val RELEASE_LINE_SORT_FIELDS = setOf("major", "updatedAt")

class ReleaseLineService(
    private val database: R2dbcDatabase,
    private val contracts: ContractService,
) {
    object ReleaseLines : UIntIdTable("contract_release_lines"), SoftDeletable {
        val contractId = reference("contract_id", ContractService.Contracts)
        val major = integer("major")
        val supportStatus = varchar("support_status", 20).default(SupportStatus.UNSPECIFIED.name)
        val supportEndsOn = varchar("support_ends_on", 10).nullable()
        val supportPolicy = varchar("support_policy", 2000).nullable()
        val recommendedVersionId = reference("recommended_version_id", ContractVersionService.ContractVersions).nullable()
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    suspend fun list(contractId: UInt, paging: PageRequest): ReleaseLineListResult = suspendTransaction(database) {
        requireActiveContract(contractId)
        val predicate = (ReleaseLines.contractId eq contractId) and ReleaseLines.active()
        val total = ReleaseLines.selectAll().where { predicate }.count()
        val rows = ReleaseLines.selectAll().where { predicate }
            .applyPaging(paging, RELEASE_LINE_SORTABLE_COLUMNS).toList()
        ReleaseLineListResult(rows.map { response(it) }, total)
    }

    suspend fun read(contractId: UInt, major: Int): ReleaseLineResponse? = suspendTransaction(database) {
        requireActiveContract(contractId)
        lineRow(contractId, major)?.let { response(it) }
    }

    suspend fun update(
        contractId: UInt,
        major: Int,
        request: ReleaseLineUpdateRequest,
        caller: CallerPrincipal,
    ): ReleaseLineChange = suspendTransaction(database) {
        contracts.requireCurrentWriter(caller, contractId)
        validate(request)
        val row = lineRow(contractId, major) ?: throw NotFoundException("Release line not found")
        val pin = request.recommendedVersionId?.let { requireRecommended(contractId, major, it) }
        if (request.supportStatus == SupportStatus.END_OF_LIFE && pin != null) {
            throw BadRequestException("An end-of-life release line cannot pin a recommended version")
        }
        val policy = request.supportPolicy?.trim()?.ifEmpty { null }
        val changed = row[ReleaseLines.supportStatus] != request.supportStatus.name ||
            row[ReleaseLines.supportEndsOn] != request.supportEndsOn ||
            row[ReleaseLines.supportPolicy] != policy ||
            row[ReleaseLines.recommendedVersionId]?.value != pin
        if (changed) {
            ReleaseLines.update({ ReleaseLines.id eq row[ReleaseLines.id] }) {
                it[supportStatus] = request.supportStatus.name
                it[supportEndsOn] = request.supportEndsOn
                it[supportPolicy] = policy
                it[recommendedVersionId] = pin
                it[updatedAt] = nowMillis()
            }
        }
        ReleaseLineChange(changed, response(lineRow(contractId, major)!!))
    }

    /** Called inside the version insert transaction, after the parent contract has been locked. */
    internal suspend fun ensure(contractId: UInt, major: Int, stamp: Long) {
        ReleaseLines.insertIgnore {
            it[ReleaseLines.contractId] = contractId
            it[ReleaseLines.major] = major
            it[updatedAt] = stamp
        }
    }

    /** A pinned version stops being effective as soon as it leaves ACTIVE. */
    internal suspend fun clearRecommendation(versionId: UInt) {
        ReleaseLines.update({ ReleaseLines.recommendedVersionId eq versionId }) {
            it[recommendedVersionId] = null
            it[updatedAt] = nowMillis()
        }
    }

    private fun validate(request: ReleaseLineUpdateRequest) {
        request.supportEndsOn?.let {
            try {
                if (!DATE_PATTERN.matches(it)) throw DateTimeParseException("non-canonical", it, 0)
                if (LocalDate.parse(it).toString() != it) throw DateTimeParseException("non-canonical", it, 0)
            } catch (_: DateTimeParseException) {
                throw BadRequestException("supportEndsOn must be a valid YYYY-MM-DD date")
            }
        }
        if ((request.supportPolicy?.length ?: 0) > 2000) throw BadRequestException("supportPolicy must be at most 2000 characters")
    }

    private suspend fun requireRecommended(contractId: UInt, major: Int, id: UInt): UInt {
        val v = ContractVersionService.ContractVersions
        val candidate = v.selectAll().where {
            (v.id eq id) and (v.contractId eq contractId) and (v.semverMajor eq major) and
                (v.lifecycle eq Lifecycle.ACTIVE.name) and v.semverPrerelease.isNull() and v.active()
        }.toList().singleOrNull()
        if (candidate == null) {
            throw BadRequestException("recommendedVersionId must identify an active stable version in this release line")
        }
        return id
    }

    private suspend fun response(row: ResultRow): ReleaseLineResponse {
        val contractId = row[ReleaseLines.contractId].value
        val major = row[ReleaseLines.major]
        val versions = versions(contractId, major)
        val latest = versions.maxByOrNull { SemVer.parse(it.version) }
        val status = SupportStatus.valueOf(row[ReleaseLines.supportStatus])
        val pinnedId = row[ReleaseLines.recommendedVersionId]?.value
        val automatic = versions.filter { it.lifecycle == Lifecycle.ACTIVE && SemVer.parse(it.version).prerelease == null }
            .maxByOrNull { SemVer.parse(it.version) }
        val pinned = pinnedId?.let { id ->
            versions.singleOrNull {
                it.id == id && it.lifecycle == Lifecycle.ACTIVE && SemVer.parse(it.version).prerelease == null
            }
        }
        val recommended = if (status == SupportStatus.END_OF_LIFE) null else pinned ?: automatic
        return ReleaseLineResponse(
            id = row[ReleaseLines.id].value,
            contractId = contractId,
            major = major,
            supportStatus = status,
            supportEndsOn = row[ReleaseLines.supportEndsOn],
            supportPolicy = row[ReleaseLines.supportPolicy],
            recommendedVersionId = pinned?.id,
            latestVersion = latest,
            recommendedVersion = recommended,
            versionCount = versions.size.toLong(),
            updatedAt = row[ReleaseLines.updatedAt],
        )
    }

    private suspend fun versions(contractId: UInt, major: Int): List<ReleaseLineVersionSummary> {
        val v = ContractVersionService.ContractVersions
        return v.select(v.id, v.version, v.lifecycle).where {
            (v.contractId eq contractId) and (v.semverMajor eq major) and v.active()
        }.map { ReleaseLineVersionSummary(it[v.id].value, it[v.version], Lifecycle.valueOf(it[v.lifecycle])) }.toList()
    }

    private suspend fun lineRow(contractId: UInt, major: Int): ResultRow? =
        ReleaseLines.selectAll().where {
            (ReleaseLines.contractId eq contractId) and (ReleaseLines.major eq major) and ReleaseLines.active()
        }.toList().singleOrNull()

    private suspend fun requireActiveContract(contractId: UInt) {
        if (ContractService.Contracts.selectAll().where {
                (ContractService.Contracts.id eq contractId) and ContractService.Contracts.active()
            }.count() == 0L
        ) throw NotFoundException("Contract not found")
    }

    private companion object {
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
