package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.DocumentCheckRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionContentRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionPageResponse
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.CheckReport
import ch.nokillswit.contracts.checks.CheckerClientKey
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The document store paths: SemVer rules, the HARD/SOFT gate and waiver, the lifecycle, the raw content, recheck, the live check. */
class ContractVersionTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.contract(prefix: String, type: ContractType = ContractType.OPENAPI): ContractResponse {
        val systemId = TestContracts.seedSystem(prefix)
        val teamId = TestTeams.seed(name("t"))
        return postJson("/api/v1/contracts", ContractCreateRequest(systemId, type, name(prefix), null, ownerTeamId = teamId)).body()
    }

    private fun path(c: ContractResponse) = "/api/v1/contracts/${c.id}/versions"

    @Test
    fun `create - a clean draft stores the text byte-exact with its metadata and an empty report`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vcreate", UserRole.ADMIN)
        val c = admin.contract("vcreate")
        val create = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi))
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        assertNotNull(create.headers["Location"])
        val v = create.body<VersionResponse>()
        assertEquals(Lifecycle.DRAFT, v.lifecycle)
        assertEquals(DocumentFormat.yaml, v.format)
        assertEquals(ContractFixtures.openApi, v.content)
        assertEquals("Petstore", v.docTitle); assertEquals("3.1.0", v.specVersion)
        assertEquals(emptyList(), v.findings); assertTrue(v.checkComplete); assertEquals(0, v.checkErrors)
        assertEquals(64, v.contentSha256.length)
        val json = admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApiJson)).body<VersionResponse>()
        assertEquals(DocumentFormat.json, json.format)
        // The contract now points at the highest version.
        val contract = admin.get("/api/v1/contracts/${c.id}").body<ContractResponse>()
        assertEquals("1.1.0", contract.latestVersion!!.version); assertEquals(2, contract.versionCount)
    }

    @Test
    fun `semver rules - malformed 400, duplicate 409, not above the highest 400, prereleases order correctly`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vsemver", UserRole.ADMIN)
        val c = admin.contract("vsemver")
        assertEquals(HttpStatusCode.BadRequest, admin.postJson(path(c), VersionCreateRequest("v1", ContractFixtures.openApi)).status)
        assertEquals(HttpStatusCode.Created, admin.postJson(path(c), VersionCreateRequest("1.2.0-rc.1", ContractFixtures.openApi)).status)
        assertEquals(HttpStatusCode.Conflict, admin.postJson(path(c), VersionCreateRequest("1.2.0-rc.1", ContractFixtures.openApi)).status)
        val low = admin.postJson(path(c), VersionCreateRequest("1.1.9", ContractFixtures.openApi))
        assertEquals(HttpStatusCode.BadRequest, low.status)
        assertTrue(low.body<ProblemDetail>().detail!!.contains("greater than"))
        assertEquals(
            HttpStatusCode.Created,
            admin.postJson(path(c), VersionCreateRequest("1.2.0", ContractFixtures.openApi)).status,
            "a release follows its prerelease",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(path(c), VersionCreateRequest("1.2.0-rc.2", ContractFixtures.openApi)).status,
            "a prerelease is below its release",
        )
        val page = admin.get(path(c)).body<VersionPageResponse>()
        assertEquals(listOf("1.2.0", "1.2.0-rc.1"), page.items.map { it.version }, "highest first by default")
        assertEquals(
            listOf("1.2.0-rc.1", "1.2.0"),
            admin.get("${path(c)}?sort=version").body<VersionPageResponse>().items.map { it.version },
        )
        assertEquals(HttpStatusCode.BadRequest, admin.get("${path(c)}?sort=content").status)
    }

    @Test
    fun `the HARD gate - unparseable text and a type mismatch are 400 even with allowInvalid`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vhard", UserRole.ADMIN)
        val c = admin.contract("vhard")
        val broken = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.0.0", "openapi: 3.1.0\ninfo: [oops\n"))
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        val mismatch = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.0.0", ContractFixtures.asyncApi3))
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
        assertTrue(mismatch.body<ProblemDetail>().detail!!.contains("does not match the contract type"))
        val swagger = admin.postJson(path(c), VersionCreateRequest("1.0.0", "swagger: '2.0'\ninfo: {title: T, version: '1'}\npaths: {}\n"))
        assertEquals(HttpStatusCode.BadRequest, swagger.status)
        assertEquals(0, admin.get(path(c)).body<VersionPageResponse>().total, "nothing stored")
    }

    @Test
    fun `the SOFT gate - a schema error blocks a strict save and stores with allowInvalid, findings ride the row`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vsoft", UserRole.ADMIN)
        val c = admin.contract("vsoft", ContractType.ODCS)
        val strict = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.odcsBroken))
        assertEquals(HttpStatusCode.BadRequest, strict.status)
        assertTrue(strict.body<ProblemDetail>().detail!!.contains("blocking finding"))
        val waived = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.0.0", ContractFixtures.odcsBroken))
        assertEquals(HttpStatusCode.Created, waived.status)
        val v = waived.body<VersionResponse>()
        assertTrue(v.checkErrors > 0)
        assertTrue(v.findings.any { it.source == FindingSource.SCHEMA && it.severity == Severity.ERROR })
        val row = admin.get(path(c)).body<VersionPageResponse>().items.single()
        assertEquals(v.checkErrors, row.checkErrors)
        // The list's hasErrors filter sees it through the latest pointer.
        val flagged = admin.get(
            "/api/v1/contracts?systemId=${c.system.id}&hasErrors=true",
        ).body<ch.nokillswit.contracts.ContractPageResponse>()
        assertEquals(listOf(c.id), flagged.items.map { it.id })
    }

    @Test
    fun `the checker's findings merge in, and an outage stores with checkComplete=false until a recheck`() = testApplication {
        configureApp()
        application { attributes.put(CheckerClientKey, TestChecker.lint()) }
        startApplication()
        val admin = seededClient("vlint", UserRole.ADMIN)
        val c = admin.contract("vlint")
        val v = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        assertEquals(listOf("info-contact"), v.findings.map { it.code })
        assertEquals(1, v.checkWarnings); assertTrue(v.checkComplete)
    }

    @Test
    fun `a sidecar outage degrades to a SYSTEM warning, checkComplete=false, and audits`() = testApplication {
        configureApp()
        application { attributes.put(CheckerClientKey, TestChecker.down()) }
        startApplication()
        val admin = seededClient("vdown", UserRole.ADMIN)
        val c = admin.contract("vdown")
        withAuditCapture { capture ->
            val v = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
            assertFalse(v.checkComplete)
            assertEquals("CHECKER_UNAVAILABLE", v.findings.single().code)
            assertNotNull(capture.awaitEvent { it.message == "checker.unavailable" })
            // The strict save still succeeded: a missing checker never blocks.
            assertEquals(HttpStatusCode.Created, admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApi)).status)
            // recheck re-runs the pipeline (the stub still fails — the report stays flagged, but the route works).
            val rechecked = admin.post("${path(c)}/${v.id}/recheck").body<VersionResponse>()
            assertFalse(rechecked.checkComplete)
        }
    }

    @Test
    fun `lifecycle - the matrix, the content lock from ACTIVE, the draft-only delete, the latest pointer`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vlife", UserRole.ADMIN)
        val c = admin.contract("vlife")
        val v = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val vp = "${path(c)}/${v.id}"
        assertEquals(
            HttpStatusCode.Conflict,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.ACTIVE)).status,
            "DRAFT → ACTIVE skips PROPOSED",
        )
        assertEquals(
            Lifecycle.PROPOSED,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.PROPOSED)).body<VersionResponse>().lifecycle,
        )
        assertEquals(
            Lifecycle.DRAFT,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.DRAFT)).body<VersionResponse>().lifecycle,
            "PROPOSED → DRAFT is allowed",
        )
        admin.postJson("$vp/transition", TransitionRequest(Lifecycle.PROPOSED))
        // Content is editable in PROPOSED …
        val edited = admin.putJson("$vp/content", VersionContentRequest(ContractFixtures.openApiJson))
        assertEquals(HttpStatusCode.OK, edited.status)
        assertEquals(DocumentFormat.json, edited.body<VersionResponse>().format)
        assertEquals(
            Lifecycle.ACTIVE,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.ACTIVE)).body<VersionResponse>().lifecycle,
        )
        // … and locked from ACTIVE on; so is delete.
        assertEquals(HttpStatusCode.Conflict, admin.putJson("$vp/content", VersionContentRequest(ContractFixtures.openApi)).status)
        assertEquals(HttpStatusCode.Conflict, admin.delete(vp).status)
        assertEquals(HttpStatusCode.Conflict, admin.postJson("$vp/transition", TransitionRequest(Lifecycle.DRAFT)).status)
        assertEquals(
            Lifecycle.DEPRECATED,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.DEPRECATED)).body<VersionResponse>().lifecycle,
        )
        assertEquals(
            Lifecycle.RETIRED,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.RETIRED)).body<VersionResponse>().lifecycle,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            admin.postJson("$vp/transition", TransitionRequest(Lifecycle.ACTIVE)).status,
            "RETIRED is terminal",
        )
        // A draft deletes, and the latest pointer falls back.
        val draft = admin.postJson(path(c), VersionCreateRequest("2.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        assertEquals("2.0.0", admin.get("/api/v1/contracts/${c.id}").body<ContractResponse>().latestVersion!!.version)
        assertEquals(HttpStatusCode.NoContent, admin.delete("${path(c)}/${draft.id}").status)
        assertEquals("1.0.0", admin.get("/api/v1/contracts/${c.id}").body<ContractResponse>().latestVersion!!.version)
        assertEquals(HttpStatusCode.NotFound, admin.delete("${path(c)}/${draft.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("${path(c)}/999999").status)
        // A version id under the wrong contract is a 404, not a leak.
        val other = admin.contract("vlife2")
        assertEquals(HttpStatusCode.NotFound, admin.get("${path(other)}/${v.id}").status)
    }

    @Test
    fun `raw content - the stored bytes with the format's media type, and a download disposition`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vraw", UserRole.ADMIN)
        val c = admin.contract("vraw")
        val v = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val raw = admin.get("${path(c)}/${v.id}/content")
        assertEquals(HttpStatusCode.OK, raw.status)
        assertTrue(raw.headers["Content-Type"]!!.startsWith("application/yaml"))
        assertEquals(ContractFixtures.openApi, raw.bodyAsText())
        val download = admin.get("${path(c)}/${v.id}/content?download=true")
        val disposition = download.headers["Content-Disposition"]!!
        assertTrue(disposition.startsWith("attachment"), disposition)
        assertTrue(disposition.contains("__1.0.0.yaml"), disposition)
        val json = admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApiJson)).body<VersionResponse>()
        assertTrue(admin.get("${path(c)}/${json.id}/content").headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun `the live check never 400s for document problems and carries the version cross-check`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("vcheck")
        val broken = user.postJson(
            "/api/v1/contracts/versions/check",
            DocumentCheckRequest(ContractType.OPENAPI, "openapi: 3.1.0\ninfo: [oops\n"),
        ).body<CheckReport>()
        assertEquals(1, broken.errors); assertTrue(broken.findings.single().hard)
        val mismatch = user.postJson(
            "/api/v1/contracts/versions/check",
            DocumentCheckRequest(ContractType.OPENAPI, ContractFixtures.openApi, version = "2.0.0"),
        ).body<CheckReport>()
        assertEquals("VERSION_MISMATCH", mismatch.findings.single().code)
        assertEquals("Petstore", mismatch.title)
        val clean = user.postJson(
            "/api/v1/contracts/versions/check",
            DocumentCheckRequest(ContractType.ODCS, ContractFixtures.odcs, version = "1.0.0"),
        ).body<CheckReport>()
        assertEquals(emptyList(), clean.findings)
    }

    @Test
    fun `an oversized document is 413 before parsing`() = testApplication {
        configureApp("contracts.maxDocumentBytes" to "1000")
        startApplication()
        val admin = seededClient("vbig", UserRole.ADMIN)
        val c = admin.contract("vbig")
        val big = ContractFixtures.openApi + "\n# " + "x".repeat(2000)
        assertEquals(HttpStatusCode.PayloadTooLarge, admin.postJson(path(c), VersionCreateRequest("1.0.0", big)).status)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            admin.postJson("/api/v1/contracts/versions/check", DocumentCheckRequest(ContractType.OPENAPI, big)).status,
        )
    }
}
