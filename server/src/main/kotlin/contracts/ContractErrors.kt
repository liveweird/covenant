package ch.nokillswit.contracts

import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.infra.db.jsonArrayHasElementWhere
import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op

/**
 * The Errors report (Toadie's `/errors`, ported onto Covenant's stored findings): a READ over
 * `contract_versions.findings` — every version carrying at least one finding matching the
 * caller's severity/source/lifecycle filter, one row per version, grouped visually under its
 * contract by the SPA's sort order. See `.claude/docs/list-endpoints.md` "Facets" for the
 * counting convention [foldErrorFacets] reuses.
 */
@Serializable
data class ErrorContractRef(
    val id: UInt,
    val name: String,
    val type: ContractType,
    val system: RefSummary,
    val domain: RefSummary,
    val owner: OwnerRef,
)

@Serializable
data class ErrorVersionRef(
    val id: UInt,
    val version: String,
    val lifecycle: Lifecycle,
    val checkErrors: Int,
    val checkWarnings: Int,
    val checkInfos: Int,
    val checkComplete: Boolean,
    val checkedAt: Long,
)

/** One row: a version carrying ≥ 1 finding matching the filter — [findings] TRIMMED to those, in stored order. */
@Serializable
data class ErrorRow(val contract: ErrorContractRef, val version: ErrorVersionRef, val findings: List<Finding>)

typealias ErrorPageResponse = PageResponse<ErrorRow>

/**
 * Facet totals for the Errors report. [severity]/[source] count FINDINGS (across the
 * lifecycle-matching versions in scope), [lifecycle] counts VERSIONS with ≥ 1 matching finding —
 * each dimension lifted like the contracts list's facets (`ContractListFilter.lifting`).
 */
@Serializable
data class ErrorFacetsResponse(
    val contracts: Long,
    val versions: Long,
    val findings: Long,
    val severity: List<FacetCount>,
    val source: List<FacetCount>,
    val lifecycle: List<FacetCount>,
)

// The sources a version's `findings` snapshot ever actually stores — SYNTAX is HARD (never past
// the 400) and CONFORMANCE is a live try-it-only observation, never stored on a version.
internal val STORED_SOURCES =
    listOf(FindingSource.SCHEMA, FindingSource.SEMANTIC, FindingSource.LINT, FindingSource.BREAKING, FindingSource.SYSTEM)

/**
 * The errors list's filter. [contracts] carries the contract-scoped params ONLY — its own
 * `lifecycles`/`hasErrors` are always left empty, since [lifecycles] below replaces the contract
 * list's "latest version" reading with the ROW's own version state.
 */
data class ErrorListFilter(
    val contracts: ContractListFilter,
    val lifecycles: List<Lifecycle> = emptyList(),
    val severities: List<Severity> = emptyList(),
    val sources: List<FindingSource> = emptyList(),
)

data class ErrorListResult(val items: List<ErrorRow>, val total: Long)

/** Whether this finding matches the severity/source filter (empty = any) — the Kotlin twin of [findingMatches]; the two MUST agree. */
internal fun Finding.matches(severities: List<Severity>, sources: List<FindingSource>): Boolean =
    (severities.isEmpty() || severity in severities) && (sources.isEmpty() || source in sources)

/** The SQL twin of [Finding.matches] — renders through [jsonArrayHasElementWhere] over the stored `findings` JSON. */
internal fun findingMatches(severities: List<Severity>, sources: List<FindingSource>): Op<Boolean> =
    ContractVersionService.ContractVersions.findings.jsonArrayHasElementWhere(
        "severity" to severities.map { it.name },
        "source" to sources.map { it.name },
    )

/** One version's decoded findings — the facets fold's unit of work. */
internal data class VersionFindings(val contractId: UInt, val versionId: UInt, val lifecycle: Lifecycle, val findings: List<Finding>)

/**
 * Folds every version-with-a-finding in the contract-scoped filter into the facet response, in
 * memory: a LATERAL `jsonb_array_elements` GROUP BY is inexpressible in Exposed's DSL and raw SQL
 * would be the repo's first (see `ContractErrorService.facets`), so this keeps [Finding.matches]
 * as the SINGLE source of truth for both the page and the facets instead of risking a second
 * predicate that drifts. Scale: hundreds of contracts × ≤ 500 findings each — tens of ms.
 */
internal fun foldErrorFacets(rows: List<VersionFindings>, filter: ErrorListFilter): ErrorFacetsResponse {
    fun scoped(
        lifecycles: List<Lifecycle>,
        severities: List<Severity>,
        sources: List<FindingSource>,
    ): List<Pair<VersionFindings, List<Finding>>> =
        rows.filter { lifecycles.isEmpty() || it.lifecycle in lifecycles }
            .map { it to it.findings.filter { finding -> finding.matches(severities, sources) } }
            .filter { (_, findings) -> findings.isNotEmpty() }

    fun facetOf(values: List<String>): List<FacetCount> =
        values.groupingBy { it }.eachCount().map { (value, count) -> FacetCount(value, count.toLong()) }.sortedBy { it.value }

    val totals = scoped(filter.lifecycles, filter.severities, filter.sources)
    val severityFacet = facetOf(scoped(filter.lifecycles, emptyList(), filter.sources).flatMap { (_, f) -> f.map { it.severity.name } })
    val sourceFacet = facetOf(scoped(filter.lifecycles, filter.severities, emptyList()).flatMap { (_, f) -> f.map { it.source.name } })
    val lifecycleFacet = facetOf(scoped(emptyList(), filter.severities, filter.sources).map { (v, _) -> v.lifecycle.name })

    return ErrorFacetsResponse(
        contracts = totals.map { (v, _) -> v.contractId }.distinct().size.toLong(),
        versions = totals.size.toLong(),
        findings = totals.sumOf { (_, f) -> f.size.toLong() },
        severity = severityFacet,
        source = sourceFacet,
        lifecycle = lifecycleFacet,
    )
}
