package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.SemVer
import ch.nokillswit.contracts.checks.CheckerClientKey
import ch.nokillswit.contracts.checks.Compatibility
import ch.nokillswit.contracts.checks.CompatibilityReport
import ch.nokillswit.contracts.checks.CompatibilityVerdict
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.OdcsBreaking
import ch.nokillswit.contracts.checks.OpenApiBreaking
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.checks.VersionBump
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `replace` that refuses a missing target — a fixture edit that silently no-ops would test nothing (`OpenApiBreakingTest`'s idiom). */
private fun String.edit(target: String, replacement: String): String {
    check(contains(target)) { "fixture edit target missing: $target" }
    return replace(target, replacement)
}

/** The two-way compatibility report (`GET …/{vid}/compatibility`): the pure bump/verdict matrix, then the route. */
class CompatibilityTest {

    // ---- pure: Compatibility.bump / Compatibility.verdict --------------------------------

    @Test
    fun `bump - the highest differing SemVer column wins, downgrade whenever to is older`() {
        val v100 = SemVer.parse("1.0.0")
        assertEquals(VersionBump.NONE, Compatibility.bump(v100, SemVer.parse("1.0.0")))
        assertEquals(VersionBump.PATCH, Compatibility.bump(v100, SemVer.parse("1.0.1")))
        assertEquals(VersionBump.MINOR, Compatibility.bump(v100, SemVer.parse("1.1.0")))
        assertEquals(VersionBump.MAJOR, Compatibility.bump(v100, SemVer.parse("2.0.0")))
        assertEquals(VersionBump.DOWNGRADE, Compatibility.bump(v100, SemVer.parse("0.9.0")))
        // A release outranks its own prerelease — the release is the "newer" side, the prerelease the downgrade.
        assertEquals(VersionBump.PRERELEASE, Compatibility.bump(SemVer.parse("1.0.0-rc.1"), v100))
        assertEquals(VersionBump.DOWNGRADE, Compatibility.bump(v100, SemVer.parse("1.0.0-rc.1")))
    }

    @Test
    fun `verdict - both compatible is FULL, either side null is UNKNOWN, else the naming holds`() {
        assertEquals(CompatibilityVerdict.FULL, Compatibility.verdict(true, true))
        assertEquals(CompatibilityVerdict.BACKWARD, Compatibility.verdict(true, false))
        assertEquals(CompatibilityVerdict.FORWARD, Compatibility.verdict(false, true))
        assertEquals(CompatibilityVerdict.NONE, Compatibility.verdict(false, false))
        assertEquals(CompatibilityVerdict.UNKNOWN, Compatibility.verdict(null, true))
        assertEquals(CompatibilityVerdict.UNKNOWN, Compatibility.verdict(true, null))
        assertEquals(CompatibilityVerdict.UNKNOWN, Compatibility.verdict(null, null))
    }

    // ---- route: OPENAPI --------------------------------------------------------------------

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.contract(prefix: String, type: ContractType = ContractType.OPENAPI): ContractResponse {
        val systemId = TestContracts.seedSystem(prefix)
        val teamId = TestTeams.seed(name("t"))
        return postJson("/api/v1/contracts", ContractCreateRequest(systemId, type, name(prefix), null, ownerTeamId = teamId)).body()
    }

    private fun path(c: ContractResponse) = "/api/v1/contracts/${c.id}/versions"

    private fun compatPath(c: ContractResponse, vid: UInt, against: UInt? = null): String {
        val base = "/api/v1/contracts/${c.id}/versions/$vid/compatibility"
        return if (against != null) "$base?against=$against" else base
    }

    /** Creates `version` and walks it DRAFT → PROPOSED → ACTIVE (`ContractVersionTest`'s idiom). */
    private suspend fun HttpClient.activate(c: ContractResponse, version: String, content: String): VersionResponse {
        val v = postJson(path(c), VersionCreateRequest(version, content)).body<VersionResponse>()
        postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
        return postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.ACTIVE)).body()
    }

    /** The fixture with an extra additive `/widgets` GET operation — present in one side only per test. */
    private val withExtraOperation = ContractFixtures.openApi.edit(
        "components:\n  schemas:",
        "  /widgets:\n    get:\n      operationId: listWidgets\n      responses:\n" +
            "        \"200\": { description: ok }\ncomponents:\n  schemas:",
    )

    /** The fixture with a newly required query parameter on `GET /pets`. */
    private val withNewRequiredParameter = ContractFixtures.openApi.edit(
        "    get:\n      operationId: listPets\n      summary: List pets\n      responses:",
        "    get:\n      operationId: listPets\n      summary: List pets\n      parameters:\n" +
            "        - { name: limit, in: query, required: true, schema: { type: integer } }\n      responses:",
    )

    @Test
    fun `identical documents are FULL without an engine verdict flip, and the from ref names the ACTIVE baseline`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cfull", UserRole.ADMIN)
        val c = admin.contract("cfull")
        val baseline = admin.activate(c, "1.0.0", ContractFixtures.openApi)
        val candidate = admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApi)).body<VersionResponse>()

        val report = admin.get(compatPath(c, candidate.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.FULL, report.verdict)
        assertEquals(VersionBump.MINOR, report.bump)
        assertEquals(true, report.backward.compatible)
        assertEquals(true, report.forward.compatible)
        assertTrue(report.backward.findings.isEmpty())
        assertTrue(report.checkerAvailable)
        assertEquals(baseline.id, report.from?.id)
        assertEquals("1.0.0", report.from?.version)
        assertEquals(Lifecycle.ACTIVE, report.from?.lifecycle)
        assertEquals(candidate.id, report.to.id)
    }

    @Test
    fun `an added operation is BACKWARD - the forward direction reports it REMOVED`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cadd", UserRole.ADMIN)
        val c = admin.contract("cadd")
        val from = admin.activate(c, "1.0.0", ContractFixtures.openApi)
        val to = admin.postJson(path(c), VersionCreateRequest("1.1.0", withExtraOperation)).body<VersionResponse>()

        val report = admin.get(compatPath(c, to.id, from.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.BACKWARD, report.verdict)
        assertEquals(true, report.backward.compatible)
        assertEquals(false, report.forward.compatible)
        assertTrue(report.forward.findings.any { it.code == OpenApiBreaking.CODE_REMOVED_OPERATION })
    }

    @Test
    fun `a removed operation is FORWARD - the backward direction reports it REMOVED`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("crem", UserRole.ADMIN)
        val c = admin.contract("crem")
        val from = admin.activate(c, "1.0.0", withExtraOperation)
        // The candidate itself is a breaking MINOR bump against the ACTIVE baseline (the removed operation) — waive it to store it.
        val to = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.1.0", ContractFixtures.openApi))
            .body<VersionResponse>()

        val report = admin.get(compatPath(c, to.id, from.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.FORWARD, report.verdict)
        assertEquals(false, report.backward.compatible)
        assertEquals(true, report.forward.compatible)
        assertTrue(report.backward.findings.any { it.code == OpenApiBreaking.CODE_REMOVED_OPERATION })
    }

    @Test
    fun `a newly required parameter breaks both directions - NONE`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cparam", UserRole.ADMIN)
        val c = admin.contract("cparam")
        val from = admin.activate(c, "1.0.0", ContractFixtures.openApi)
        val to =
            admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.1.0", withNewRequiredParameter)).body<VersionResponse>()

        val report = admin.get(compatPath(c, to.id, from.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.NONE, report.verdict)
        assertEquals(false, report.backward.compatible)
        assertEquals(false, report.forward.compatible)
    }

    // ---- route: ODCS -------------------------------------------------------------------------

    private val odcsWithoutProperty = ContractFixtures.odcs.edit(
        "    properties:\n      - name: customer_id\n        logicalType: string\n        physicalType: uuid\n" +
            "        required: true\n        primaryKey: true\n        primaryKeyPosition: 1",
        "    properties: []",
    )

    @Test
    fun `ODCS - a removed property is FORWARD, the backward direction names it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("codcs", UserRole.ADMIN)
        val c = admin.contract("codcs", ContractType.ODCS)
        val from = admin.activate(c, "1.0.0", ContractFixtures.odcs)
        val to = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.1.0", odcsWithoutProperty)).body<VersionResponse>()

        val report = admin.get(compatPath(c, to.id, from.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.FORWARD, report.verdict)
        assertEquals(false, report.backward.compatible)
        assertEquals(true, report.forward.compatible)
        assertTrue(report.backward.findings.any { it.code == OdcsBreaking.CODE_REMOVED_PROPERTY })
    }

    // ---- route: ASYNCAPI (through the checker) -----------------------------------------------

    @Test
    fun `ASYNCAPI - a direction-aware checker stub drives BACKWARD and FORWARD by which side is the older text`() = testApplication {
        // A fact appears only when the OLDER side of the pair (the diff's "previousContent") is `original` —
        // so whichever direction compares old=original is the one that breaks.
        val stub = TestChecker.byPrevious { previous ->
            if (previous == ContractFixtures.asyncApi3) {
                listOf(Finding(Severity.WARN, FindingSource.BREAKING, "breaking-edit", "Changed /servers/production/protocol", "/servers"))
            } else {
                emptyList()
            }
        }
        configureApp()
        application { attributes.put(CheckerClientKey, stub) }
        startApplication()
        val admin = seededClient("casync", UserRole.ADMIN)
        val c = admin.contract("casync", ContractType.ASYNCAPI)
        val mqtt = ContractFixtures.asyncApi3.replace("protocol: kafka", "protocol: mqtt")
        val original = admin.activate(c, "1.0.0", ContractFixtures.asyncApi3)
        // The candidate's own CREATE also runs the baseline check against the ACTIVE version — the stub's fact blocks a strict save too.
        val changed = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.1.0", mqtt)).body<VersionResponse>()

        // to = changed, against = original: backward compares old=original → breaks; forward compares old=changed → fine.
        val forward = admin.get(compatPath(c, changed.id, original.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.FORWARD, forward.verdict)
        assertEquals(false, forward.backward.compatible)
        assertEquals(true, forward.forward.compatible)

        // to = original, against = changed: backward compares old=changed → fine; forward compares old=original → breaks.
        val backward = admin.get(compatPath(c, original.id, changed.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.BACKWARD, backward.verdict)
        assertEquals(true, backward.backward.compatible)
        assertEquals(false, backward.forward.compatible)
    }

    @Test
    fun `ASYNCAPI - a checker outage answers UNKNOWN with checkerAvailable false, never a failed request`() = testApplication {
        configureApp()
        application { attributes.put(CheckerClientKey, TestChecker.down()) }
        startApplication()
        val admin = seededClient("cdown", UserRole.ADMIN)
        val c = admin.contract("cdown", ContractType.ASYNCAPI)
        val mqtt = ContractFixtures.asyncApi3.replace("protocol: kafka", "protocol: mqtt")
        val from = admin.activate(c, "1.0.0", ContractFixtures.asyncApi3)
        val to = admin.postJson(path(c), VersionCreateRequest("1.1.0", mqtt)).body<VersionResponse>()

        val report = admin.get(compatPath(c, to.id, from.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.UNKNOWN, report.verdict)
        assertNull(report.backward.compatible)
        assertNull(report.forward.compatible)
        assertFalse(report.checkerAvailable)
    }

    // ---- edge cases: no baseline, foreign/unknown ids, downgrade -----------------------------

    @Test
    fun `no ACTIVE predecessor and no against - 200 with from null and verdict UNKNOWN`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cnone", UserRole.ADMIN)
        val c = admin.contract("cnone")
        val only = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()

        val response = admin.get(compatPath(c, only.id))
        assertEquals(HttpStatusCode.OK, response.status)
        val report = response.body<CompatibilityReport>()
        assertNull(report.from)
        assertNull(report.bump)
        assertEquals(CompatibilityVerdict.UNKNOWN, report.verdict)
        assertTrue(report.checkerAvailable)
    }

    @Test
    fun `against from another contract is 404, as is an unknown vid`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cforeign", UserRole.ADMIN)
        val a = admin.contract("cforeign-a")
        val b = admin.contract("cforeign-b")
        val va = admin.postJson(path(a), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val vb = admin.postJson(path(b), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()

        assertEquals(HttpStatusCode.NotFound, admin.get(compatPath(a, va.id, vb.id)).status, "against belongs to a different contract")
        assertEquals(HttpStatusCode.NotFound, admin.get(compatPath(a, 999_999_999u)).status, "unknown vid")
    }

    @Test
    fun `against == vid is legal and FULL, and DOWNGRADE names an against that is the newer side`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cself", UserRole.ADMIN)
        val c = admin.contract("cself")
        val v1 = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val v2 = admin.postJson(path(c), VersionCreateRequest("2.0.0", ContractFixtures.openApi)).body<VersionResponse>()

        val self = admin.get(compatPath(c, v1.id, v1.id)).body<CompatibilityReport>()
        assertEquals(CompatibilityVerdict.FULL, self.verdict)
        assertEquals(VersionBump.NONE, self.bump)

        // vid=v1 (1.0.0), against=v2 (2.0.0): `to` is the OLDER side here — a downgrade.
        val downgraded = admin.get(compatPath(c, v1.id, v2.id)).body<CompatibilityReport>()
        assertEquals(VersionBump.DOWNGRADE, downgraded.bump)
        assertEquals(CompatibilityVerdict.FULL, downgraded.verdict, "same content on both sides, regardless of order")
    }
}
