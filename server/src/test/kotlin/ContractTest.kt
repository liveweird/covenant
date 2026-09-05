package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractPageResponse
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.FacetsResponse
import ch.nokillswit.contracts.FacetCount
import ch.nokillswit.contracts.ErrorFacets
import ch.nokillswit.contracts.ContractUpdateRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.OwnerKind
import ch.nokillswit.contracts.OwnerUpdateRequest
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.TreeResponse
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The contract surface: ownership/authz matrix, CRUD, the owner transfer, the delete rules, the list filters, the tree. */
class ContractTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.createContract(
        systemId: UInt,
        ownerTeamId: UInt? = null,
        ownerUserId: UInt? = null,
        type: ContractType = ContractType.OPENAPI,
        n: String = name("c"),
    ): ContractResponse {
        val r = postJson("/api/v1/contracts", ContractCreateRequest(systemId, type, n, "desc", ownerTeamId, ownerUserId))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.body()
    }

    @Test
    fun `unauthenticated requests are 401 across the family`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val reads = listOf(
            "/api/v1/contracts", "/api/v1/contracts/tree", "/api/v1/contracts/1", "/api/v1/contracts/1/versions",
            "/api/v1/contracts/1/versions/1", "/api/v1/contracts/1/versions/1/content", "/api/v1/contracts/1/export",
            "/api/v1/contracts/1/events",
        )
        for (path in reads) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, path)
        }
        val writes = listOf(
            "/api/v1/contracts", "/api/v1/contracts/versions/check", "/api/v1/contracts/import", "/api/v1/contracts/import/check",
            "/api/v1/contracts/fetch", "/api/v1/contracts/1/versions", "/api/v1/contracts/1/versions/1/transition",
            "/api/v1/contracts/1/versions/1/recheck",
        )
        for (path in writes) {
            assertEquals(HttpStatusCode.Unauthorized, client.post(path).status, path)
        }
    }

    @Test
    fun `the writer rule - owning user, team member, stranger, admin - 403 before 400`() = testApplication {
        usePostgresTestcontainer()
        val systemId = TestContracts.seedSystem("authz")
        val ownerEmail = uniqueEmail("owner"); val ownerId = TestUsers.seed(ownerEmail, "pw", role = UserRole.USER)
        val memberEmail = uniqueEmail("member"); val memberId = TestUsers.seed(memberEmail, "pw", role = UserRole.USER)
        val strangerEmail = uniqueEmail("stranger"); TestUsers.seed(strangerEmail, "pw", role = UserRole.USER)
        val teamId = TestTeams.seed(name("team"), listOf(memberId))
        val owner = authedClient(
            ownerEmail,
            "pw",
        ); val member = authedClient(memberEmail, "pw"); val stranger = authedClient(strangerEmail, "pw")
        val admin = seededClient("adm", UserRole.ADMIN)

        // A regular user may own personally or through a team they belong to — never a foreign team or another user.
        val mine = owner.createContract(systemId, ownerUserId = ownerId)
        assertTrue(mine.canWrite); assertEquals(OwnerKind.USER, mine.owner.kind); assertEquals(ownerId, mine.owner.id)
        assertEquals(
            HttpStatusCode.Forbidden,
            owner.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.ODCS, name("x"), null, ownerTeamId = teamId),
            ).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            owner.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.ODCS, name("x"), null, ownerUserId = memberId),
            ).status,
        )
        val teams = member.createContract(systemId, ownerTeamId = teamId, type = ContractType.ASYNCAPI)
        assertEquals(OwnerKind.TEAM, teams.owner.kind)
        val byAdmin = admin.createContract(systemId, ownerUserId = memberId)
        assertTrue(byAdmin.canWrite, "ADMIN writes everything")

        // Reads: everyone; canWrite is per caller.
        assertFalse(stranger.get("/api/v1/contracts/${mine.id}").body<ContractResponse>().canWrite)
        assertTrue(member.get("/api/v1/contracts/${teams.id}").body<ContractResponse>().canWrite)
        assertFalse(owner.get("/api/v1/contracts/${teams.id}").body<ContractResponse>().canWrite)

        // Writes: the stranger is refused — uniformly, before the body decodes.
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson("/api/v1/contracts/${mine.id}", ContractUpdateRequest(name("z"))).status)
        val malformed = stranger.put("/api/v1/contracts/${mine.id}") { contentType(ContentType.Application.Json); setBody("{ not json") }
        assertEquals(HttpStatusCode.Forbidden, malformed.status, "403 wins over 400")
        assertEquals(HttpStatusCode.Forbidden, stranger.delete("/api/v1/contracts/${mine.id}").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            stranger.postJson("/api/v1/contracts/${mine.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi)).status,
        )
        assertEquals(HttpStatusCode.NotFound, owner.putJson("/api/v1/contracts/999999", ContractUpdateRequest(name("z"))).status)
        // The member writes the team's contract; the owner writes theirs; admin writes both; ownership transfer is admin-only.
        assertEquals(
            HttpStatusCode.NoContent,
            member.putJson("/api/v1/contracts/${teams.id}", ContractUpdateRequest(name("renamed"), "d")).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            owner.putJson("/api/v1/contracts/${mine.id}", ContractUpdateRequest(name("renamed2"))).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            owner.putJson("/api/v1/contracts/${mine.id}/owner", OwnerUpdateRequest(ownerTeamId = teamId)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/contracts/${mine.id}/owner", OwnerUpdateRequest(ownerTeamId = teamId)).status,
        )
        val transferred = owner.get("/api/v1/contracts/${mine.id}").body<ContractResponse>()
        assertEquals(OwnerKind.TEAM, transferred.owner.kind)
        assertFalse(transferred.canWrite, "the former owner lost write access with the transfer")
        assertTrue(member.get("/api/v1/contracts/${mine.id}").body<ContractResponse>().canWrite)
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/contracts/${mine.id}/owner", OwnerUpdateRequest(ownerTeamId = teamId, ownerUserId = ownerId)).status,
            "exactly one owner side",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/contracts/${mine.id}/owner", OwnerUpdateRequest(ownerUserId = 999999u)).status,
            "an unknown owner is a 400",
        )
    }

    @Test
    fun `create rules - unknown system 400, owner XOR 400, name unique per system 409, a delete frees the name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("crules", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("crules")
        val other = TestContracts.seedSystem("crules2")
        val teamId = TestTeams.seed(name("t"))
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(999999u, ContractType.OPENAPI, name("c"), null, ownerTeamId = teamId),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson("/api/v1/contracts", ContractCreateRequest(systemId, ContractType.OPENAPI, name("c"))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson("/api/v1/contracts", ContractCreateRequest(systemId, ContractType.OPENAPI, name("c"), null, teamId, 1u)).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.OPENAPI, "", null, ownerTeamId = teamId),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.OPENAPI, name("c"), null, ownerTeamId = 999999u),
            ).status,
        )
        val n = name("Same")
        val first = admin.createContract(systemId, ownerTeamId = teamId, n = n)
        assertEquals(
            HttpStatusCode.Conflict,
            admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.ODCS, n.uppercase(), null, ownerTeamId = teamId),
            ).status,
        )
        assertEquals(
            HttpStatusCode.Created,
            admin.postJson("/api/v1/contracts", ContractCreateRequest(other, ContractType.ODCS, n, null, ownerTeamId = teamId)).status,
            "the same name in another system is fine",
        )
        assertNotNull(first.system.name); assertNotNull(first.domain.name)
        assertEquals(0, first.versionCount); assertNull(first.latestVersion)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${first.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/contracts/${first.id}").status)
        assertEquals(
            HttpStatusCode.Created,
            admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.OPENAPI, n, null, ownerTeamId = teamId),
            ).status,
            "a deleted contract frees its name",
        )
    }

    @Test
    fun `the list filters by system, domain, type, owner, lifecycle, q, hasErrors - the tree nests the same rows`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("clist", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("clist")
        val teamId = TestTeams.seed(name("t"))
        val userId = TestUsers.seed(uniqueEmail("u"), "pw", role = UserRole.USER)
        val a = admin.createContract(systemId, ownerTeamId = teamId, type = ContractType.OPENAPI, n = name("alpha-payments"))
        val b = admin.createContract(systemId, ownerUserId = userId, type = ContractType.ODCS, n = name("beta"))
        // A version on `a` makes its latest lifecycle DRAFT and gives it a check summary.
        val v = admin.postJson(
            "/api/v1/contracts/${a.id}/versions",
            VersionCreateRequest("1.0.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        admin.postJson("/api/v1/contracts/${a.id}/versions/${v.id}/transition", TransitionRequest(Lifecycle.PROPOSED))

        suspend fun ids(
            query: String,
        ) = admin.get("/api/v1/contracts?systemId=$systemId&pageSize=100&$query").body<ContractPageResponse>().items.map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), ids("sort=name"))
        assertEquals(setOf(a.id), ids("type=OPENAPI"))
        assertEquals(setOf(a.id, b.id), ids("type=OPENAPI&type=ODCS"))
        assertEquals(setOf(a.id), ids("ownerTeamId=$teamId"))
        assertEquals(setOf(b.id), ids("ownerUserId=$userId"))
        assertEquals(setOf(a.id), ids("lifecycle=PROPOSED"))
        assertEquals(emptySet(), ids("lifecycle=ACTIVE"), "a contract without a version never matches a lifecycle")
        assertEquals(setOf(a.id), ids("q=PAYMENTS"))
        assertEquals(setOf(a.id, b.id), ids("q=desc"), "q matches the description too")
        assertEquals(setOf(a.id, b.id), ids("hasErrors=false"))
        assertEquals(emptySet(), ids("hasErrors=true"))
        val domainId = a.domain.id
        assertEquals(
            setOf(a.id, b.id),
            admin.get("/api/v1/contracts?domainId=$domainId&pageSize=100").body<ContractPageResponse>().items.map { it.id }.toSet(),
        )
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts?type=WSDL").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts?sort=owner").status)
        val row = admin.get("/api/v1/contracts?systemId=$systemId&type=OPENAPI").body<ContractPageResponse>().items.single()
        assertEquals(
            "1.0.0",
            row.latestVersion!!.version,
        ); assertEquals(Lifecycle.PROPOSED, row.latestVersion!!.lifecycle); assertEquals(1, row.versionCount)

        val tree = admin.get("/api/v1/contracts/tree?type=OPENAPI").body<TreeResponse>()
        val system = tree.domains.single { it.id == domainId }.systems.single { it.id == systemId }
        assertEquals(listOf(a.id), system.contracts.map { it.id })
        assertEquals("1.0.0", system.contracts.single().latestVersion!!.version)
        val emptyTree = admin.get("/api/v1/contracts/tree?type=ASYNCAPI").body<TreeResponse>()
        assertTrue(
            emptyTree.domains.single { it.id == domainId }.systems.single { it.id == systemId }.contracts.isEmpty(),
            "the spine survives an empty filter",
        )
    }

    @Test
    fun `delete - refused while a version is published, cascades to drafts, frees the identity`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cdel", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("cdel")
        val teamId = TestTeams.seed(name("t"))
        val c = admin.createContract(systemId, ownerTeamId = teamId)
        val v = admin.postJson(
            "/api/v1/contracts/${c.id}/versions",
            VersionCreateRequest("1.0.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        admin.postJson("/api/v1/contracts/${c.id}/versions/${v.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
        admin.postJson("/api/v1/contracts/${c.id}/versions/${v.id}/transition", TransitionRequest(Lifecycle.ACTIVE))
        val blocked = admin.delete("/api/v1/contracts/${c.id}")
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertTrue(blocked.body<ProblemDetail>().detail!!.contains("retire"))
        admin.postJson("/api/v1/contracts/${c.id}/versions/${v.id}/transition", TransitionRequest(Lifecycle.DEPRECATED))
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/v1/contracts/${c.id}").status, "DEPRECATED still counts as published")
        admin.postJson("/api/v1/contracts/${c.id}/versions/${v.id}/transition", TransitionRequest(Lifecycle.RETIRED))
        admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.1.0", ContractFixtures.openApi))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${c.id}").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.get("/api/v1/contracts/${c.id}/versions/${v.id}").status,
            "versions go with the contract",
        )
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/contracts/${c.id}").status)
    }

    @Test
    fun `export and events - the full round-trip payload and the structural history`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cexp", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("cexp")
        val teamId = TestTeams.seed(name("t"))
        val c = admin.createContract(systemId, ownerTeamId = teamId, type = ContractType.ODCS)
        admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.odcs))
        admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.1.0", ContractFixtures.odcs))
        val export = admin.get("/api/v1/contracts/${c.id}/export").body<ch.nokillswit.contracts.ContractExportResponse>()
        assertEquals(c.id, export.contract.id)
        assertEquals(listOf("1.1.0", "1.0.0"), export.versions.map { it.version }, "highest first")
        assertEquals(ContractFixtures.odcs, export.versions.first().content, "byte-exact")
        val events = admin.get("/api/v1/contracts/${c.id}/events").body<ch.nokillswit.contracts.ContractEventPageResponse>()
        assertEquals(listOf("VERSION_CREATED", "VERSION_CREATED", "CREATED"), events.items.map { it.type.name }, "newest first")
        assertEquals("1.1.0", events.items.first().params["version"])
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/contracts/999999/export").status)
    }

    @Test
    fun `mutations audit`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("caudit", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("caudit")
        val teamId = TestTeams.seed(name("t"))
        withAuditCapture { capture ->
            val c = admin.createContract(systemId, ownerTeamId = teamId)
            assertNotNull(capture.awaitEvent { it.message == "contract.created" && it.hasKeyValue("contractId", c.id.toLong()) })
            admin.putJson("/api/v1/contracts/${c.id}/owner", OwnerUpdateRequest(ownerUserId = TestUsers.seed(uniqueEmail("o"), "pw")))
            val transfer = capture.awaitEvent { it.message == "contract.owner_changed" && it.hasKeyValue("contractId", c.id.toLong()) }
            assertNotNull(transfer); assertTrue(transfer.hasKeyValue("from", "TEAM:$teamId"))
            admin.delete("/api/v1/contracts/${c.id}")
            assertNotNull(capture.awaitEvent { it.message == "contract.deleted" && it.hasKeyValue("contractId", c.id.toLong()) })
        }
    }

    @Test
    fun `facets - each dimension counts with its own filter lifted and every other filter applied`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("facets", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("facets")
        val teamId = TestTeams.seed(name("fac-team"))
        val first = ContractCreateRequest(systemId, ContractType.OPENAPI, name("fac"), null, ownerTeamId = teamId)
        val a = admin.postJson("/api/v1/contracts", first).body<ContractResponse>()
        admin.postJson("/api/v1/contracts", ContractCreateRequest(systemId, ContractType.OPENAPI, name("fac"), null, ownerTeamId = teamId))
        admin.postJson("/api/v1/contracts", ContractCreateRequest(systemId, ContractType.ODCS, name("fac"), null, ownerTeamId = teamId))
        val domainId = a.domain.id
        val all = admin.get("/api/v1/contracts/facets?domainId=$domainId").body<FacetsResponse>()
        assertEquals(listOf(FacetCount("ODCS", 1), FacetCount("OPENAPI", 2)), all.type)
        assertEquals(listOf(FacetCount("NONE", 3)), all.lifecycle, "no versions yet — every contract sits in the NONE bucket")
        assertEquals(systemId, all.system.single().id)
        assertEquals(3, all.system.single().count)
        assertEquals(3, all.ownerTeam.single { it.id == teamId }.count)
        assertEquals(ErrorFacets(withErrors = 0, clean = 3), all.hasErrors)
        // With the type narrowed: the type facet itself is unchanged (its own dimension is lifted),
        // every other dimension counts only the ODCS contract.
        val typed = admin.get("/api/v1/contracts/facets?domainId=$domainId&type=ODCS").body<FacetsResponse>()
        assertEquals(all.type, typed.type)
        assertEquals(1, typed.system.single().count)
        assertEquals(1, typed.domain.single { it.id == domainId }.count, "the domain facet lifts the domain filter and applies the type")
        assertEquals(1, typed.ownerTeam.single { it.id == teamId }.count)
        assertEquals(ErrorFacets(withErrors = 0, clean = 1), typed.hasErrors)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/facets?type=NOPE").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/contracts/facets").status)
    }
}
