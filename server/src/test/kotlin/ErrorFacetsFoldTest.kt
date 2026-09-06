package ch.nokillswit

import ch.nokillswit.contracts.ErrorListFilter
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.ContractListFilter
import ch.nokillswit.contracts.VersionFindings
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.foldErrorFacets
import ch.nokillswit.contracts.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests over literal data for [foldErrorFacets] and [Finding.matches] — the exact
 * lifting rule per dimension (each dimension counts with every OTHER filter applied and its own
 * lifted) and the "empty list = any" convention. `ContractErrorsTest` exercises the same code
 * against real stored findings, whose exact validator output is never pinned by literal numbers.
 */
class ErrorFacetsFoldTest {

    private fun finding(severity: Severity, source: FindingSource, code: String) = Finding(severity, source, code, "message")

    private val v1 = VersionFindings(
        contractId = 1u, versionId = 10u, lifecycle = Lifecycle.DRAFT,
        findings = listOf(finding(Severity.WARN, FindingSource.LINT, "L1"), finding(Severity.ERROR, FindingSource.SCHEMA, "S1")),
    )
    private val v2 = VersionFindings(
        contractId = 1u, versionId = 11u, lifecycle = Lifecycle.ACTIVE,
        findings = listOf(finding(Severity.WARN, FindingSource.LINT, "L2")),
    )
    private val v3 = VersionFindings(
        contractId = 2u, versionId = 20u, lifecycle = Lifecycle.DRAFT,
        findings = listOf(finding(Severity.ERROR, FindingSource.SEMANTIC, "M1"), finding(Severity.INFO, FindingSource.SEMANTIC, "I1")),
    )
    private val rows = listOf(v1, v2, v3)

    private fun filter(
        lifecycles: List<Lifecycle> = emptyList(),
        severities: List<Severity> = emptyList(),
        sources: List<FindingSource> = emptyList(),
    ) = ErrorListFilter(ContractListFilter(), lifecycles, severities, sources)

    @Test
    fun `Finding matches - empty list means any, non-empty narrows both dimensions`() {
        val f = finding(Severity.ERROR, FindingSource.SCHEMA, "X")
        assertTrue(f.matches(emptyList(), emptyList()))
        assertTrue(f.matches(listOf(Severity.ERROR), emptyList()))
        assertTrue(f.matches(emptyList(), listOf(FindingSource.SCHEMA)))
        assertTrue(f.matches(listOf(Severity.ERROR), listOf(FindingSource.SCHEMA)))
        assertFalse(f.matches(listOf(Severity.WARN), emptyList()))
        assertFalse(f.matches(emptyList(), listOf(FindingSource.LINT)))
        assertFalse(f.matches(listOf(Severity.ERROR), listOf(FindingSource.LINT)))
    }

    @Test
    fun `unfiltered totals count every version, contract and finding once`() {
        val facets = foldErrorFacets(rows, filter())
        assertEquals(2L, facets.contracts, "contracts 1 and 2")
        assertEquals(3L, facets.versions, "every version carries at least one finding")
        assertEquals(5L, facets.findings, "2 + 1 + 2")
        assertEquals(listOf("ERROR" to 2L, "INFO" to 1L, "WARN" to 2L), facets.severity.map { it.value to it.count })
        assertEquals(listOf("LINT" to 2L, "SCHEMA" to 1L, "SEMANTIC" to 2L), facets.source.map { it.value to it.count })
        assertEquals(listOf("ACTIVE" to 1L, "DRAFT" to 2L), facets.lifecycle.map { it.value to it.count })
    }

    @Test
    fun `severity filter narrows totals and every OTHER dimension, but lifts its own`() {
        val facets = foldErrorFacets(rows, filter(severities = listOf(Severity.ERROR)))
        assertEquals(2L, facets.contracts)
        assertEquals(2L, facets.versions, "v1 and v3 each carry an ERROR; v2 does not")
        assertEquals(2L, facets.findings, "S1 and M1")
        val severity = facets.severity.map { it.value to it.count }
        assertEquals(listOf("ERROR" to 2L, "INFO" to 1L, "WARN" to 2L), severity, "unchanged — severity is lifted")
        val source = facets.source.map { it.value to it.count }
        assertEquals(listOf("SCHEMA" to 1L, "SEMANTIC" to 1L), source, "narrowed to the two ERROR findings")
        val lifecycle = facets.lifecycle.map { it.value to it.count }
        assertEquals(listOf("DRAFT" to 2L), lifecycle, "only DRAFT versions carry an ERROR here")
    }

    @Test
    fun `lifecycle filter narrows totals and every OTHER dimension, but lifts its own`() {
        val facets = foldErrorFacets(rows, filter(lifecycles = listOf(Lifecycle.DRAFT)))
        assertEquals(2L, facets.contracts)
        assertEquals(2L, facets.versions, "v1 and v3 are DRAFT; v2 is ACTIVE")
        assertEquals(4L, facets.findings, "v1's two plus v3's two")
        val lifecycle = facets.lifecycle.map { it.value to it.count }
        assertEquals(listOf("ACTIVE" to 1L, "DRAFT" to 2L), lifecycle, "unchanged — lifecycle is lifted")
        val severity = facets.severity.map { it.value to it.count }
        assertEquals(listOf("ERROR" to 2L, "INFO" to 1L, "WARN" to 1L), severity, "only the DRAFT versions' findings")
        val source = facets.source.map { it.value to it.count }
        assertEquals(listOf("LINT" to 1L, "SCHEMA" to 1L, "SEMANTIC" to 2L), source)
    }

    @Test
    fun `no single finding satisfies severity AND source together - totals are zero, but each LIFTED dimension still counts on its own`() {
        val facets = foldErrorFacets(rows, filter(severities = listOf(Severity.ERROR), sources = listOf(FindingSource.LINT)))
        assertEquals(0L, facets.contracts, "no version carries a finding that is both ERROR and LINT")
        assertEquals(0L, facets.versions)
        assertEquals(0L, facets.findings)
        // severity lifts its OWN filter but keeps source=LINT — the two WARN/LINT findings (L1, L2).
        assertEquals(listOf("WARN" to 2L), facets.severity.map { it.value to it.count })
        // source lifts its OWN filter but keeps severity=ERROR — the two ERROR findings (S1, M1).
        assertEquals(listOf("SCHEMA" to 1L, "SEMANTIC" to 1L), facets.source.map { it.value to it.count })
        // lifecycle applies BOTH severity and source (neither is its own dimension) — nothing matches both at once.
        assertEquals(emptyList(), facets.lifecycle)
    }
}
