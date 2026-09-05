package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.DocumentCheckRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.SyncRequest
import ch.nokillswit.contracts.SyncStateResponse
import ch.nokillswit.contracts.VersionSourceRequest
import ch.nokillswit.contracts.ContractEventPageResponse
import ch.nokillswit.contracts.ContractEventType
import ch.nokillswit.contracts.ImportItem
import ch.nokillswit.contracts.ImportRequest
import ch.nokillswit.contracts.ImportResponse
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionContentRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionPageResponse
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.CheckReport
import ch.nokillswit.contracts.checks.BreakingChanges
import ch.nokillswit.contracts.checks.CheckerClientKey
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.OpenApiBreaking
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.render.RenderModelResponse
import ch.nokillswit.contracts.tryit.TryCatalogResponse
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
import kotlin.test.assertNull
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
    fun `render model - the family matching the contract's type, the spec version, 404 for a foreign version`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vmodel", UserRole.ADMIN)
        val api = admin.contract("vmodel")
        val v = admin.postJson(path(api), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val model = admin.get("${path(api)}/${v.id}/model").body<RenderModelResponse>()
        assertEquals(ContractType.OPENAPI, model.type)
        assertEquals("3.1.0", model.specVersion)
        assertEquals(listOf("GET /pets"), model.openApi?.operations?.map { "${it.method} ${it.path}" })
        assertEquals(listOf("Pet"), model.openApi?.schemas?.map { it.name })
        assertNull(model.asyncApi)
        assertNull(model.odcs)
        val events = admin.contract("vmodel", ContractType.ASYNCAPI)
        val ev = admin.postJson(path(events), VersionCreateRequest("1.0.0", ContractFixtures.asyncApi3)).body<VersionResponse>()
        val asyncModel = admin.get("${path(events)}/${ev.id}/model").body<RenderModelResponse>()
        assertEquals("lightMeasured", asyncModel.asyncApi?.channels?.single()?.name)
        val data = admin.contract("vmodel", ContractType.ODCS)
        val dv = admin.postJson(path(data), VersionCreateRequest("1.0.0", ContractFixtures.odcs)).body<VersionResponse>()
        assertEquals("customer_view", admin.get("${path(data)}/${dv.id}/model").body<RenderModelResponse>().odcs?.datasets?.single()?.name)
        assertEquals(HttpStatusCode.NotFound, admin.get("${path(api)}/${dv.id}/model").status)
    }

    @Test
    fun `try catalog - the version's document read as operations, channels or datasets - 404 for a foreign version`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("vtry", UserRole.ADMIN)
            val api = admin.contract("vtry")
            val v = admin.postJson(path(api), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
            val http = admin.get("${path(api)}/${v.id}/try").body<TryCatalogResponse>()
            assertEquals(ContractType.OPENAPI, http.type)
            assertTrue(http.http.any { it.method == "GET" && it.path == "/pets" }, http.http.toString())
            assertTrue(http.kafka.isEmpty() && http.sql.isEmpty())
            val events = admin.contract("vtry", ContractType.ASYNCAPI)
            val ev = admin.postJson(path(events), VersionCreateRequest("1.0.0", ContractFixtures.asyncApi3)).body<VersionResponse>()
            val kafka = admin.get("${path(events)}/${ev.id}/try").body<TryCatalogResponse>()
            assertEquals(listOf("lightMeasured"), kafka.kafka.map { it.channel })
            val data = admin.contract("vtry", ContractType.ODCS)
            val dv = admin.postJson(path(data), VersionCreateRequest("1.0.0", ContractFixtures.odcs)).body<VersionResponse>()
            val sql = admin.get("${path(data)}/${dv.id}/try").body<TryCatalogResponse>()
            assertEquals(listOf("customer_view"), sql.sql.map { it.name })
            assertEquals(HttpStatusCode.NotFound, admin.get("${path(api)}/${dv.id}/try").status, "a version of another contract")
            assertEquals(HttpStatusCode.NotFound, admin.get("${path(api)}/999999/try").status)
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

    // ---- breaking changes (milestone 2) ------------------------------------------------------

    /** Creates `version` and walks it DRAFT → PROPOSED → ACTIVE — the breaking-change baseline. */
    private suspend fun HttpClient.activate(c: ContractResponse, version: String, content: String): VersionResponse {
        val v = postJson(path(c), VersionCreateRequest(version, content)).body<VersionResponse>()
        postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
        return postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.ACTIVE)).body()
    }

    /** The Petstore with `Pet.name` gone from the response schema — a breaking change. */
    private val narrowedPetstore = ContractFixtures.openApi.replace("\n        name: { type: string }", "").also {
        check(it != ContractFixtures.openApi) { "the fixture edit did not apply" }
    }

    @Test
    fun `breaking changes - blocked without the MAJOR bump, waivable, and a mere INFO with it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vbreak", UserRole.ADMIN)
        val c = admin.contract("vbreak")
        admin.activate(c, "1.0.0", ContractFixtures.openApi)

        val strict = admin.postJson(path(c), VersionCreateRequest("1.1.0", narrowedPetstore))
        assertEquals(HttpStatusCode.BadRequest, strict.status, strict.bodyAsText())
        assertTrue(strict.body<ProblemDetail>().detail!!.contains(BreakingChanges.CODE_WITHOUT_MAJOR_BUMP))

        val waived = admin.postJson("${path(c)}?allowInvalid=true", VersionCreateRequest("1.1.0", narrowedPetstore))
        assertEquals(HttpStatusCode.Created, waived.status, waived.bodyAsText())
        val minor = waived.body<VersionResponse>()
        val fact = minor.findings.single { it.code == OpenApiBreaking.CODE_CHANGED_RESPONSE }
        assertEquals(FindingSource.BREAKING, fact.source); assertEquals(Severity.WARN, fact.severity)
        assertTrue(fact.message.contains("'items.name' was removed"), fact.message)
        val gate = minor.findings.single { it.code == BreakingChanges.CODE_WITHOUT_MAJOR_BUMP }
        assertEquals(Severity.ERROR, gate.severity)
        assertTrue(gate.message.contains("1 breaking change against active version 1.0.0") && gate.message.contains("2.0.0"), gate.message)
        assertEquals(1, minor.checkErrors)

        val major = admin.postJson(path(c), VersionCreateRequest("2.0.0", narrowedPetstore))
        assertEquals(HttpStatusCode.Created, major.status, major.bodyAsText())
        val v2 = major.body<VersionResponse>()
        assertEquals(Severity.INFO, v2.findings.single { it.code == OpenApiBreaking.CODE_CHANGED_RESPONSE }.severity)
        assertFalse(v2.findings.any { it.code == BreakingChanges.CODE_WITHOUT_MAJOR_BUMP })
        assertEquals(0, v2.checkErrors)
    }

    @Test
    fun `breaking changes - the baseline is the highest ACTIVE version BELOW the candidate, drafts never count`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vbase", UserRole.ADMIN)
        val c = admin.contract("vbase")
        // Only a DRAFT exists: nothing to compare against.
        admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
        val noBaseline = admin.postJson(path(c), VersionCreateRequest("1.1.0", narrowedPetstore))
        assertEquals(HttpStatusCode.Created, noBaseline.status, noBaseline.bodyAsText())
        assertFalse(noBaseline.body<VersionResponse>().findings.any { it.source == FindingSource.BREAKING })
        // ACTIVE 2.0.0 (the original), then a 2.1.0 draft with the narrowed schema: compared against 2.0.0.
        admin.activate(c, "2.0.0", ContractFixtures.openApi)
        val blocked = admin.postJson(path(c), VersionCreateRequest("2.1.0", narrowedPetstore))
        assertEquals(HttpStatusCode.BadRequest, blocked.status)
        // A recheck of the ACTIVE 2.0.0 itself compares against nothing below it — no facts appear.
        val active = admin.get(path(c)).body<VersionPageResponse>().items.single { it.version == "2.0.0" }
        val rechecked = admin.post("${path(c)}/${active.id}/recheck").body<VersionResponse>()
        assertFalse(rechecked.findings.any { it.source == FindingSource.BREAKING })
        // Editing a draft below the ACTIVE one compares against nothing either (1.1.0 < 2.0.0).
        val draft = admin.get(path(c)).body<VersionPageResponse>().items.single { it.version == "1.1.0" }
        val edited = admin.putJson("${path(c)}/${draft.id}/content", VersionContentRequest(narrowedPetstore)).body<VersionResponse>()
        assertFalse(edited.findings.any { it.source == FindingSource.BREAKING })
    }

    @Test
    fun `the live check compares only when the contract is named, and gates only when a version is`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vlive", UserRole.ADMIN)
        val c = admin.contract("vlive")
        admin.activate(c, "1.0.0", ContractFixtures.openApi)
        val url = "/api/v1/contracts/versions/check"
        val named = admin.postJson(url, DocumentCheckRequest(ContractType.OPENAPI, narrowedPetstore, "1.1.0", c.id)).body<CheckReport>()
        assertEquals("1.0.0", named.baselineVersion)
        assertTrue(named.findings.any { it.code == BreakingChanges.CODE_WITHOUT_MAJOR_BUMP })
        val anonymous = admin.postJson(url, DocumentCheckRequest(ContractType.OPENAPI, narrowedPetstore, "1.1.0")).body<CheckReport>()
        assertNull(anonymous.baselineVersion)
        assertFalse(anonymous.findings.any { it.source == FindingSource.BREAKING })
        val below = admin.postJson(url, DocumentCheckRequest(ContractType.OPENAPI, narrowedPetstore, "0.9.0", c.id)).body<CheckReport>()
        assertNull(below.baselineVersion, "nothing ACTIVE below 0.9.0")
        val unversioned = admin.postJson(url, DocumentCheckRequest(ContractType.OPENAPI, narrowedPetstore, null, c.id)).body<CheckReport>()
        assertEquals("1.0.0", unversioned.baselineVersion)
        assertTrue(unversioned.findings.any { it.code == OpenApiBreaking.CODE_CHANGED_RESPONSE && it.severity == Severity.WARN })
        assertFalse(unversioned.findings.any { it.code == BreakingChanges.CODE_WITHOUT_MAJOR_BUMP }, "no version, no bump to judge")
        val unknownContract = admin.postJson(url, DocumentCheckRequest(ContractType.OPENAPI, narrowedPetstore, "1.1.0", 999_999_999u))
        assertEquals(HttpStatusCode.OK, unknownContract.status)
        assertNull(unknownContract.body<CheckReport>().baselineVersion)
    }

    @Test
    fun `ASYNCAPI - the baseline text reaches the checker and its BREAKING facts are settled by the JVM`() = testApplication {
        val stub = TestChecker.Stub(
            listOf(Finding(Severity.WARN, FindingSource.BREAKING, "breaking-edit", "Changed /servers/production/protocol", "/servers")),
        )
        configureApp()
        application { attributes.put(CheckerClientKey, stub) }
        startApplication()
        val admin = seededClient("vasync", UserRole.ADMIN)
        val c = admin.contract("vasync", ContractType.ASYNCAPI)
        // The first version has no baseline: the stub's fact is settled WARN, nothing blocks, no previous text.
        val first = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.asyncApi3))
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertNull(stub.lastPreviousContent)
        val v1 = first.body<VersionResponse>()
        admin.postJson("${path(c)}/${v1.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
        admin.postJson("${path(c)}/${v1.id}/transition", TransitionRequest(Lifecycle.ACTIVE))
        val mqtt = ContractFixtures.asyncApi3.replace("protocol: kafka", "protocol: mqtt")
        val strict = admin.postJson(path(c), VersionCreateRequest("1.1.0", mqtt))
        assertEquals(HttpStatusCode.BadRequest, strict.status, strict.bodyAsText())
        assertEquals(ContractFixtures.asyncApi3, stub.lastPreviousContent, "the ACTIVE text rides to the checker for ASYNCAPI")
        val major = admin.postJson(path(c), VersionCreateRequest("2.0.0", mqtt)).body<VersionResponse>()
        assertEquals(Severity.INFO, major.findings.single { it.code == "breaking-edit" }.severity)
    }

    // ---- source references & repo sync (V12) -------------------------------------------------

    private val repoUrl = "https://github.com/acme/contracts/blob/main/petstore.yaml"

    @Test
    fun `source reference - stamped by a fetched create, set and cleared by writers under the static guards, never a text edit`() =
        testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vsrc", UserRole.ADMIN)
        val c = admin.contract("vsrc")
        val fromRepo = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi, repoUrl)).body<VersionResponse>()
        assertEquals(repoUrl, fromRepo.sourceUrl)
        assertTrue(fromRepo.lastSyncedAt > 0 && fromRepo.lastSyncedAt == fromRepo.updatedAt, "a fetched text is in sync with its source")
        val state = admin.get("${path(c)}/${fromRepo.id}/sync").body<SyncStateResponse>()
        assertEquals(ContractFixtures.openApi, state.syncedContent)
        assertEquals(fromRepo.lastSyncedAt, state.lastSyncedAt)
        val plain = admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApi)).body<VersionResponse>()
        assertNull(plain.sourceUrl)
        assertEquals(0, plain.lastSyncedAt)
        // The static guards: https only, no credentials — on create and on the source PUT alike.
        val http = admin.postJson(path(c), VersionCreateRequest("1.2.0", ContractFixtures.openApi, "http://example.com/x.yaml"))
        assertEquals(HttpStatusCode.BadRequest, http.status)
        val credentials = admin.putJson("${path(c)}/${plain.id}/source", VersionSourceRequest("https://u:p@example.com/x"))
        assertEquals(HttpStatusCode.BadRequest, credentials.status)
        // Setting a reference on the plain version: no sync state yet, updatedAt untouched.
        val gitlab = "https://gitlab.com/acme/c/-/raw/main/p.yaml"
        assertEquals(HttpStatusCode.NoContent, admin.putJson("${path(c)}/${plain.id}/source", VersionSourceRequest(gitlab)).status)
        val linked = admin.get("${path(c)}/${plain.id}").body<VersionResponse>()
        assertEquals(gitlab, linked.sourceUrl)
        assertEquals(0, linked.lastSyncedAt)
        assertEquals(plain.updatedAt, linked.updatedAt, "a reference edit is not a text edit")
        // Changing the fetched one's reference resets its sync state; a blank value clears the reference.
        admin.putJson("${path(c)}/${fromRepo.id}/source", VersionSourceRequest("https://github.com/acme/contracts/blob/main/other.yaml"))
        val reset = admin.get("${path(c)}/${fromRepo.id}/sync").body<SyncStateResponse>()
        assertEquals(0, reset.lastSyncedAt)
        assertNull(reset.syncedContent)
        admin.putJson("${path(c)}/${fromRepo.id}/source", VersionSourceRequest("  "))
        assertNull(admin.get("${path(c)}/${fromRepo.id}").body<VersionResponse>().sourceUrl)
        // 404 for a missing version; a stranger is 403 before the body decodes; reads are for everyone.
        assertEquals(HttpStatusCode.NotFound, admin.putJson("${path(c)}/999999/source", VersionSourceRequest("https://x.example/a")).status)
        val stranger = seededClient("vsrc-stranger", UserRole.USER)
        val strangerPut = stranger.putJson("${path(c)}/${plain.id}/source", VersionSourceRequest("http://not-https"))
        assertEquals(HttpStatusCode.Forbidden, strangerPut.status)
        assertEquals(HttpStatusCode.OK, stranger.get("${path(c)}/${plain.id}/sync").status)
        val listed = admin.get(path(c)).body<VersionPageResponse>().items.single { it.id == plain.id }
        assertEquals(gitlab, listed.sourceUrl)
        // The history: one VERSION_SOURCE_CHANGED per actual change, carrying the new reference.
        val events = admin.get("/api/v1/contracts/${c.id}/events").body<ContractEventPageResponse>().items
        val sourceEvents = events.filter { it.type == ContractEventType.VERSION_SOURCE_CHANGED }
        assertEquals(3, sourceEvents.size, sourceEvents.toString())
        assertEquals("", sourceEvents.first().params["sourceUrl"], "newest first — the clear")
        assertTrue(sourceEvents.any { it.params["sourceUrl"] == gitlab && it.params["version"] == "1.1.0" })
    }

    @Test
    fun `sync - the repo copy replaces an editable text waiving soft findings and stamps the state - locked or unlinked refuse`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("vsync", UserRole.ADMIN)
            val c = admin.contract("vsync")
            val v = admin.postJson(path(c), VersionCreateRequest("1.0.0", ContractFixtures.openApi, repoUrl)).body<VersionResponse>()
            val localEdit = VersionContentRequest(ContractFixtures.openApi + "\n# local\n")
            val edited = admin.putJson("${path(c)}/${v.id}/content", localEdit).body<VersionResponse>()
            assertEquals(v.lastSyncedAt, edited.lastSyncedAt, "a local edit never moves the sync stamp")
            assertTrue(edited.updatedAt >= edited.lastSyncedAt)
            // The repo copy carries a soft finding (a broken ${'$'}ref): sync stores it anyway, findings on the row.
            val synced = admin.postJson("${path(c)}/${v.id}/sync", SyncRequest(ContractFixtures.openApiBrokenRef)).body<VersionResponse>()
            assertEquals(ContractFixtures.openApiBrokenRef, synced.content)
            assertTrue(synced.checkErrors > 0)
            assertEquals(synced.updatedAt, synced.lastSyncedAt)
            assertEquals(ContractFixtures.openApiBrokenRef, admin.get("${path(c)}/${v.id}/sync").body<SyncStateResponse>().syncedContent)
            val events = admin.get("/api/v1/contracts/${c.id}/events").body<ContractEventPageResponse>().items
            assertTrue(events.any { it.type == ContractEventType.VERSION_SYNCED })
            // HARD stays a 400; a version without a reference is a 400 too.
            val hard = admin.postJson("${path(c)}/${v.id}/sync", SyncRequest("openapi: 3.1.0\ninfo: [oops\n"))
            assertEquals(HttpStatusCode.BadRequest, hard.status)
            val plain = admin.postJson(path(c), VersionCreateRequest("1.1.0", ContractFixtures.openApi)).body<VersionResponse>()
            val noRef = admin.postJson("${path(c)}/${plain.id}/sync", SyncRequest(ContractFixtures.openApi))
            assertEquals(HttpStatusCode.BadRequest, noRef.status)
            assertTrue(noRef.body<ProblemDetail>().detail!!.contains("no source reference"))
            // The lifecycle lock: 409 from ACTIVE on.
            admin.postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
            admin.postJson("${path(c)}/${v.id}/transition", TransitionRequest(Lifecycle.ACTIVE))
            assertEquals(HttpStatusCode.Conflict, admin.postJson("${path(c)}/${v.id}/sync", SyncRequest(ContractFixtures.openApi)).status)
            val stranger = seededClient("vsync-stranger", UserRole.USER)
            assertEquals(HttpStatusCode.Forbidden, stranger.postJson("${path(c)}/${plain.id}/sync", SyncRequest("oops")).status)
        }

    @Test
    fun `import - a fetched item's sourceUrl lands on the new version, stamped as synced`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("vimpsrc", UserRole.ADMIN)
        val c = admin.contract("vimpsrc")
        val item = ImportItem(
            systemId = c.system.id, type = ContractType.OPENAPI, name = c.name,
            version = "1.0.0", content = ContractFixtures.openApi, sourceUrl = repoUrl,
        )
        val result = admin.postJson("/api/v1/contracts/import", ImportRequest(listOf(item))).body<ImportResponse>().results.single()
        val stored = admin.get("${path(c)}/${result.versionId}").body<VersionResponse>()
        assertEquals(repoUrl, stored.sourceUrl)
        assertTrue(stored.lastSyncedAt > 0)
        // A bad reference skips the row like any other 400 — reported, not thrown.
        val bad = admin.postJson("/api/v1/contracts/import", ImportRequest(listOf(item.copy(version = "1.1.0", sourceUrl = "ftp://nope"))))
        assertEquals(HttpStatusCode.OK, bad.status)
        assertEquals(0, admin.get(path(c)).body<VersionPageResponse>().items.count { it.version == "1.1.0" })
    }
}
