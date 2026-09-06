package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ErrorFacetsResponse
import ch.nokillswit.contracts.ErrorPageResponse
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.CheckerClientKey
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Errors report (`GET /contracts/errors` + `/errors/facets`): versions carrying findings,
 * filtered by severity/source/version-lifecycle, and the facet counts over the same rows. Every
 * expected finding count is READ from the stored `VersionResponse.findings` — the checker/JVM
 * validators' exact output is not pinned here (see `ErrorFacetsFoldTest` for the pure fold rules
 * pinned against literal data).
 */
class ContractErrorsTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.contract(systemId: UInt, teamId: UInt, prefix: String, type: ContractType): ContractResponse =
        postJson("/api/v1/contracts", ContractCreateRequest(systemId, type, name(prefix), null, ownerTeamId = teamId)).body()

    private suspend fun HttpClient.version(
        contractId: UInt,
        version: String,
        content: String,
        allowInvalid: Boolean = false,
    ): VersionResponse {
        val suffix = if (allowInvalid) "?allowInvalid=true" else ""
        val response = postJson("/api/v1/contracts/$contractId/versions$suffix", VersionCreateRequest(version, content))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `list and facets - severity, source, lifecycle filters agree with the stored findings`() = testApplication {
        configureApp()
        // Every OPENAPI/ASYNCAPI document gets one LINT WARN from the checker stub — ODCS never calls it.
        application { attributes.put(CheckerClientKey, TestChecker.lint()) }
        startApplication()
        val admin = seededClient("cerr", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("cerr")
        val teamA = TestTeams.seed(name("cerr-team-a"))
        val teamB = TestTeams.seed(name("cerr-team-b"))

        // Contract "a" (OPENAPI) sorts before "b" (ODCS) — the prefix fixes the default name order.
        val a = admin.contract(systemId, teamA, "cerr-a", ContractType.OPENAPI)
        val aClean = admin.version(a.id, "1.0.0", ContractFixtures.openApi)
        val aBroken = admin.version(a.id, "1.1.0", ContractFixtures.openApiBrokenRef, allowInvalid = true)
        // Walk 1.0.0 all the way to RETIRED — the lifecycle filter's own dimension.
        for (to in listOf(Lifecycle.PROPOSED, Lifecycle.ACTIVE, Lifecycle.DEPRECATED, Lifecycle.RETIRED)) {
            admin.postJson("/api/v1/contracts/${a.id}/versions/${aClean.id}/transition", TransitionRequest(to))
        }

        val b = admin.contract(systemId, teamB, "cerr-b", ContractType.ODCS)
        val bBroken = admin.version(b.id, "1.0.0", ContractFixtures.odcsBroken, allowInvalid = true)

        assertTrue(aBroken.findings.any { it.severity == Severity.ERROR }, "the broken ref is a SEMANTIC/SCHEMA error")
        assertTrue(bBroken.findings.any { it.severity == Severity.ERROR }, "the broken ODCS document is a SCHEMA error")
        assertTrue(aClean.findings.none { it.severity == Severity.ERROR }, "a clean save carries no error")

        suspend fun errors(query: String = ""): ErrorPageResponse =
            admin.get("/api/v1/contracts/errors?systemId=$systemId&pageSize=100$query").body()
        suspend fun facets(query: String = ""): ErrorFacetsResponse =
            admin.get("/api/v1/contracts/errors/facets?systemId=$systemId$query").body()

        // Default order: name ascending ("a" before "b"), then version descending within a contract.
        val default = errors()
        assertEquals(3, default.total)
        assertEquals(
            listOf(a.id to aBroken.id, a.id to aClean.id, b.id to bBroken.id),
            default.items.map { it.contract.id to it.version.id },
        )
        assertEquals(aBroken.findings, default.items[0].findings, "no filter narrows the default request")
        assertEquals(aClean.findings, default.items[1].findings)
        assertEquals(bBroken.findings, default.items[2].findings)

        // severity=ERROR: only versions carrying >=1 ERROR, trimmed to those — the clean save drops out entirely.
        val errorOnly = errors("&severity=ERROR")
        assertEquals(setOf(aBroken.id, bBroken.id), errorOnly.items.map { it.version.id }.toSet())
        assertTrue(errorOnly.items.all { row -> row.findings.isNotEmpty() && row.findings.all { it.severity == Severity.ERROR } })

        // source=LINT: only versions carrying a LINT finding (both OPENAPI ones — ODCS never calls the checker).
        val lintOnly = errors("&source=LINT")
        assertEquals(setOf(aClean.id, aBroken.id), lintOnly.items.map { it.version.id }.toSet())
        assertTrue(lintOnly.items.all { row -> row.findings.isNotEmpty() && row.findings.all { it.source == FindingSource.LINT } })

        assertEquals(0, errors("&severity=ERROR&source=LINT").total, "the stub's LINT finding is always WARN")
        assertEquals(1, errors("&lifecycle=RETIRED").total)
        assertEquals(aClean.id, errors("&lifecycle=RETIRED").items.single().version.id)
        assertEquals(3, errors("&lifecycle=DRAFT&lifecycle=RETIRED").total)
        assertEquals(setOf(b.id), errors("&type=ODCS").items.map { it.contract.id }.toSet())
        assertEquals(setOf(a.id), errors("&ownerTeamId=$teamA").items.map { it.contract.id }.toSet())
        assertEquals(setOf(b.id), errors("&q=${b.name}").items.map { it.contract.id }.toSet())

        val paged = admin.get("/api/v1/contracts/errors?systemId=$systemId&pageSize=1&page=2").body<ErrorPageResponse>()
        assertEquals(1, paged.items.size); assertEquals(3, paged.total)
        assertEquals(aClean.id, paged.items.single().version.id)

        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/contracts/errors?systemId=$systemId&sort=-checkedAt").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/errors?systemId=$systemId&sort=content").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/errors?systemId=$systemId&severity=NOPE").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/errors?systemId=$systemId&source=nope").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/errors?systemId=$systemId&lifecycle=nope").status)

        // --- facets: the agreement pin, then the per-dimension lifting ---
        val allFindings = aClean.findings + aBroken.findings + bBroken.findings
        val allFacets = facets()
        assertEquals(default.total, allFacets.versions, "facets.versions must agree with the list's total for every filter")
        assertEquals(2, allFacets.contracts)
        assertEquals(allFindings.size.toLong(), allFacets.findings)
        val bySeverity = allFacets.severity.sumOf { it.count }
        assertEquals(allFindings.size.toLong(), bySeverity, "unfiltered: every finding lands in exactly one severity bucket")
        val bySource = allFacets.source.sumOf { it.count }
        assertEquals(allFindings.size.toLong(), bySource, "unfiltered: every finding lands in exactly one source bucket")
        val byLifecycle = allFacets.lifecycle.sumOf { it.count }
        assertEquals(3L, byLifecycle, "unfiltered: every matching VERSION lands in exactly one lifecycle bucket")
        assertEquals(2, allFacets.lifecycle.single { it.value == "DRAFT" }.count, "a 1.1.0 and b 1.0.0 are both still DRAFT")
        assertEquals(1, allFacets.lifecycle.single { it.value == "RETIRED" }.count)

        val errorFacets = facets("&severity=ERROR")
        assertEquals(errors("&severity=ERROR").total, errorFacets.versions)
        assertEquals(allFacets.severity, errorFacets.severity, "the severity dimension lifts its own filter")
        assertEquals(0L, errorFacets.source.firstOrNull { it.value == "LINT" }?.count ?: 0L, "the stub's LINT finding is never an ERROR")
        assertEquals(2, errorFacets.lifecycle.single { it.value == "DRAFT" }.count, "both DRAFT versions carry an error")
        val errorRetired = errorFacets.lifecycle.firstOrNull { it.value == "RETIRED" }?.count ?: 0L
        assertEquals(0L, errorRetired, "the RETIRED version carries only a WARN")

        val retiredFacets = facets("&lifecycle=RETIRED")
        assertEquals(1L, retiredFacets.versions)
        assertEquals(allFacets.lifecycle, retiredFacets.lifecycle, "the lifecycle dimension lifts its own filter")
        val expectedWarn = aClean.findings.count { it.severity == Severity.WARN }
        assertEquals(expectedWarn.toLong(), retiredFacets.severity.firstOrNull { it.value == "WARN" }?.count ?: 0L)
        assertEquals(0L, retiredFacets.severity.firstOrNull { it.value == "ERROR" }?.count ?: 0L)

        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/contracts/errors").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/contracts/errors/facets").status)

        // Deleting the DRAFT removes its row from both the list and the facets.
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${a.id}/versions/${aBroken.id}").status)
        assertEquals(2, errors().total)
        assertEquals(2L, facets().versions)
    }
}
