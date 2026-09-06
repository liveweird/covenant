package ch.nokillswit.contracts

import ch.nokillswit.contracts.ContractJoins.ownerRef
import ch.nokillswit.contracts.ContractVersionService.ContractVersions
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.domains.DomainService
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.systems.SystemService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ContractErrorServiceKey = AttributeKey<ContractErrorService>("ContractErrorService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ContractVersions.id, // the row identity IS the version
    "name" to ContractService.Contracts.name,
    "version" to ContractVersions.semverMajor, // see applySemverPaging — "version" expands to the four SemVer columns
    "lifecycle" to ContractVersions.lifecycle,
    "checkedAt" to ContractVersions.checkedAt,
    "errors" to ContractVersions.checkErrors,
)

val ERROR_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

// Contract name ascending, then SemVer descending — a contract's rows group visually; a contract
// split across a page boundary is accepted.
val ERROR_DEFAULT_SORT: List<SortField> = listOf(SortField("name", descending = false), SortField("version", descending = true))

/**
 * The Errors report's read side (Toadie's `/errors`, ported onto Covenant's stored findings):
 * every ACTIVE version — not the contract's "latest" alias `ContractService` reads — carrying at
 * least one finding matching the caller's filter. Reads only; findings are never mutated here.
 */
class ContractErrorService(private val database: R2dbcDatabase) {
    private fun joined() = ContractJoins.spine().innerJoin(ContractVersions)

    suspend fun list(filter: ErrorListFilter, paging: PageRequest): ErrorListResult = suspendTransaction(database) {
        val predicate = predicate(filter)
        val total = joined().selectAll().where { predicate }.count()
        val rows = joined().selectAll().where { predicate }.applySemverPaging(paging, SORTABLE_COLUMNS).toList()
        ErrorListResult(items = rows.map { it.toRow(filter) }, total = total)
    }

    /**
     * In-memory faceting on purpose (see [foldErrorFacets]): one select of every version with ANY
     * finding in the CONTRACT-scoped filter (lifecycle/severity/source narrowed afterwards, in
     * Kotlin, through the same [Finding.matches] the list uses), decoded once.
     */
    suspend fun facets(filter: ErrorListFilter): ErrorFacetsResponse = suspendTransaction(database) {
        val predicate = ContractJoins.contractScope(filter.contracts) and ContractService.Contracts.active() and
            ContractVersions.active() and anyFindings()
        val rows = joined()
            .select(ContractService.Contracts.id, ContractVersions.id, ContractVersions.lifecycle, ContractVersions.findings)
            .where { predicate }
            .map {
                VersionFindings(
                    contractId = it[ContractService.Contracts.id].value,
                    versionId = it[ContractVersions.id].value,
                    lifecycle = Lifecycle.valueOf(it[ContractVersions.lifecycle]),
                    findings = decodeFindings(it[ContractVersions.findings]),
                )
            }.toList()
        foldErrorFacets(rows, filter)
    }

    private fun predicate(filter: ErrorListFilter): Op<Boolean> {
        var op = ContractJoins.contractScope(filter.contracts) and ContractService.Contracts.active() and ContractVersions.active()
        if (filter.lifecycles.isNotEmpty()) op = op and (ContractVersions.lifecycle inList filter.lifecycles.map { it.name })
        op = op and countsPreGuard(filter.severities)
        op = op and findingMatches(filter.severities, filter.sources)
        return op
    }

    // `checkErrors/Warnings/Infos > 0` for the selected severities (every severity, when none
    // selected) — skips the jsonb parse on rows that plainly cannot match.
    private fun countsPreGuard(severities: List<Severity>): Op<Boolean> {
        // Explicitly typed: `greater` returns the narrower GreaterOp per branch, and reduce needs
        // one common accumulator type to widen `a or b` (an Op<Boolean>) into.
        val perSeverity: List<Op<Boolean>> = severities.ifEmpty { Severity.entries }.map {
            when (it) {
                Severity.ERROR -> ContractVersions.checkErrors greater 0
                Severity.WARN -> ContractVersions.checkWarnings greater 0
                Severity.INFO -> ContractVersions.checkInfos greater 0
            }
        }
        return perSeverity.reduce { a, b -> a or b }
    }

    private fun anyFindings(): Op<Boolean> =
        (ContractVersions.checkErrors greater 0) or (ContractVersions.checkWarnings greater 0) or (ContractVersions.checkInfos greater 0)

    private fun decodeFindings(raw: String): List<Finding> = Json.decodeFromString(findingsSerializer, raw)

    private fun ResultRow.toRow(filter: ErrorListFilter): ErrorRow {
        val findings = decodeFindings(this[ContractVersions.findings]).filter { it.matches(filter.severities, filter.sources) }
        return ErrorRow(
            contract = ErrorContractRef(
                id = this[ContractService.Contracts.id].value,
                name = this[ContractService.Contracts.name],
                type = ContractType.valueOf(this[ContractService.Contracts.type]),
                system = RefSummary(this[SystemService.Systems.id].value, this[SystemService.Systems.name]),
                domain = RefSummary(this[DomainService.Domains.id].value, this[DomainService.Domains.name]),
                owner = ownerRef(),
            ),
            version = ErrorVersionRef(
                id = this[ContractVersions.id].value,
                version = this[ContractVersions.version],
                lifecycle = Lifecycle.valueOf(this[ContractVersions.lifecycle]),
                checkErrors = this[ContractVersions.checkErrors],
                checkWarnings = this[ContractVersions.checkWarnings],
                checkInfos = this[ContractVersions.checkInfos],
                checkComplete = this[ContractVersions.checkComplete],
                checkedAt = this[ContractVersions.checkedAt],
            ),
            findings = findings,
        )
    }
}
