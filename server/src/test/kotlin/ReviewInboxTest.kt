package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.CreateVersionReviewEntryRequest
import ch.nokillswit.contracts.CreateVersionReviewRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.ReviewInboxPage
import ch.nokillswit.contracts.ReviewInboxSummary
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionContentRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.VersionReviewEntryKind
import ch.nokillswit.contracts.VersionReviewResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewInboxTest {
    private fun marker(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private suspend fun ApplicationTestBuilder.user(prefix: String, role: UserRole = UserRole.USER): Pair<UInt, HttpClient> {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email, "pw", role = role)
        return id to authedClient(email, "pw")
    }

    private suspend fun HttpClient.contract(name: String, ownerUserId: UInt? = null, ownerTeamId: UInt? = null): ContractResponse =
        postJson(
            "/api/v1/contracts",
            ContractCreateRequest(
                TestContracts.seedSystem(name), ContractType.OPENAPI, name,
                ownerTeamId = ownerTeamId,
                ownerUserId = ownerUserId,
            ),
        ).body()

    private suspend fun HttpClient.proposed(contract: ContractResponse, number: String): VersionResponse {
        val base = "/api/v1/contracts/${contract.id}/versions"
        val draft = postJson(base, VersionCreateRequest(number, ContractFixtures.openApi)).body<VersionResponse>()
        return postJson("$base/${draft.id}/transition", TransitionRequest(Lifecycle.PROPOSED)).body()
    }

    private suspend fun HttpClient.requestReview(contract: ContractResponse, version: VersionResponse): VersionReviewResponse =
        postJson(
            "/api/v1/contracts/${contract.id}/versions/${version.id}/reviews",
            CreateVersionReviewRequest(version.contentRevision),
        ).body()

    @Test
    fun `personal scope attention and summary follow the latest effective decision and round`() = testApplication {
        usePostgresTestcontainer()
        val (_, admin) = user("inbox-admin", UserRole.ADMIN)
        val (ownerId, owner) = user("inbox-owner")
        val (_, reviewer) = user("inbox-reviewer")
        val (_, secondReviewer) = user("inbox-second-reviewer")
        val name = marker("inbox-flow")
        val contract = admin.contract(name, ownerUserId = ownerId)
        val version = owner.proposed(contract, "1.0.0")
        reviewer.put("/api/v1/contracts/${contract.id}/subscription")
        val first = owner.requestReview(contract, version)
        val path = "/api/v1/version-reviews/inbox?q=$name"

        val awaiting = reviewer.get(path).body<ReviewInboxPage>().items.single()
        assertEquals(first.id, awaiting.id)
        assertTrue(awaiting.subscribed)
        assertFalse(awaiting.owned)
        assertTrue(awaiting.awaitingMyReview)
        assertFalse(awaiting.contract.canWrite)
        assertEquals(1, reviewer.get("$path&attention=AWAITING_MY_REVIEW").body<ReviewInboxPage>().total)
        assertEquals(0, admin.get(path).body<ReviewInboxPage>().total, "ADMIN write access is not personal relatedness")
        val adminAll = admin.get("$path&scope=ALL").body<ReviewInboxPage>().items.single()
        assertTrue(adminAll.contract.canWrite)
        assertFalse(adminAll.owned)

        val initialSummary = reviewer.get("/api/v1/version-reviews/inbox/summary?q=$name")
            .body<ReviewInboxSummary>()
        assertEquals(1, initialSummary.total)
        assertEquals(1, initialSummary.awaitingMyReview)
        assertEquals(0, initialSummary.changesRequested)
        assertEquals(0, initialSummary.needsNewReview)

        reviewer.postJson(
            "/api/v1/version-reviews/${first.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.CHANGES_REQUESTED, "Please adjust"),
        )
        var row = reviewer.get(path).body<ReviewInboxPage>().items.single()
        assertFalse(row.awaitingMyReview)
        assertTrue(row.changesRequested)
        assertEquals(1, row.review.changesRequestedCount)
        reviewer.postJson(
            "/api/v1/version-reviews/${first.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "Decision still stands"),
        )
        row = reviewer.get(path).body<ReviewInboxPage>().items.single()
        assertFalse(row.awaitingMyReview)
        assertTrue(row.changesRequested)
        assertEquals(1, row.review.changesRequestedCount)
        secondReviewer.postJson(
            "/api/v1/version-reviews/${first.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.CHANGES_REQUESTED, "Another concern"),
        )
        reviewer.postJson(
            "/api/v1/version-reviews/${first.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.APPROVED),
        )
        row = reviewer.get(path).body<ReviewInboxPage>().items.single()
        assertTrue(row.changesRequested, "one reviewer's approval only supersedes their own decision")
        assertEquals(1, row.review.approvalCount)
        assertEquals(1, row.review.changesRequestedCount)
        secondReviewer.postJson(
            "/api/v1/version-reviews/${first.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.APPROVED),
        )
        row = reviewer.get(path).body<ReviewInboxPage>().items.single()
        assertFalse(row.changesRequested)
        assertEquals(2, row.review.approvalCount)
        assertEquals(0, row.review.changesRequestedCount)

        owner.putJson(
            "/api/v1/contracts/${contract.id}/versions/${version.id}/content",
            VersionContentRequest(ContractFixtures.openApi + "\n# inbox change\n"),
        )
        val closed = owner.get(path).body<ReviewInboxPage>().items.single()
        assertTrue(closed.needsNewReview)
        assertTrue(closed.canRequest)
        val lifted = owner.get("$path&attention=AWAITING_MY_REVIEW").body<ReviewInboxPage>()
        assertEquals(0, lifted.total)
        val closedSummary = owner.get(
            "/api/v1/version-reviews/inbox/summary?q=$name&scope=OWNED&attention=AWAITING_MY_REVIEW",
        ).body<ReviewInboxSummary>()
        assertEquals(1, closedSummary.total)
        assertEquals(0, closedSummary.awaitingMyReview)
        assertEquals(0, closedSummary.changesRequested)
        assertEquals(1, closedSummary.needsNewReview)

        val current = owner.get("/api/v1/contracts/${contract.id}/versions/${version.id}").body<VersionResponse>()
        val second = owner.requestReview(contract, current)
        val latest = owner.get(path).body<ReviewInboxPage>()
        assertEquals(1, latest.total)
        assertEquals(second.id, latest.items.single().id)
        assertFalse(latest.items.single().needsNewReview)
    }

    @Test
    fun `owned and followed scopes use real membership and normalized search with strict query validation`() = testApplication {
        usePostgresTestcontainer()
        val (_, admin) = user("inbox-scopes-admin", UserRole.ADMIN)
        val (memberId, member) = user("inbox-scopes-member")
        val teamId = TestTeams.seed(marker("inbox-team"), listOf(memberId))
        val name = marker("Żółw-inbox")
        val contract = admin.contract(name, ownerTeamId = teamId)
        val first = member.proposed(contract, "1.0.0")
        val second = member.proposed(contract, "2.0.0")
        member.proposed(contract, "3.0.0") // never requested, therefore absent from the inbox
        val firstReview = member.requestReview(contract, first)
        val secondReview = member.requestReview(contract, second)
        val path = "/api/v1/version-reviews/inbox?q=zolw-inbox"

        val owned = member.get("$path&scope=OWNED&sort=-contractName,-requestedAt").body<ReviewInboxPage>()
        assertEquals(2, owned.total)
        assertTrue(owned.items.all { it.owned && it.contract.canWrite })
        assertEquals(0, member.get("$path&scope=FOLLOWED").body<ReviewInboxPage>().total)
        member.put("/api/v1/contracts/${contract.id}/subscription")
        assertEquals(2, member.get("$path&scope=FOLLOWED").body<ReviewInboxPage>().total)
        assertEquals(2, member.get(path).body<ReviewInboxPage>().total, "ownership plus following must not duplicate rows")
        assertEquals(1, member.get("/api/v1/version-reviews/inbox?q=2.0.0&scope=OWNED").body<ReviewInboxPage>().total)

        val firstPage = member.get("$path&scope=OWNED&pageSize=1").body<ReviewInboxPage>()
        val secondPage = member.get("$path&scope=OWNED&page=2&pageSize=1").body<ReviewInboxPage>()
        assertEquals(2, firstPage.total)
        assertEquals(2, secondPage.total)
        assertEquals(listOf(firstReview.id, secondReview.id), listOf(firstPage.items.single().id, secondPage.items.single().id))

        member.postJson(
            "/api/v1/contracts/${contract.id}/versions/${second.id}/transition",
            TransitionRequest(Lifecycle.DRAFT),
        )
        assertEquals(1, member.get("$path&scope=OWNED").body<ReviewInboxPage>().total, "non-PROPOSED versions stay out")
        member.postJson(
            "/api/v1/contracts/${contract.id}/versions/${second.id}/transition",
            TransitionRequest(Lifecycle.PROPOSED),
        )
        val reproposed = member.get("$path&scope=OWNED").body<ReviewInboxPage>()
        assertEquals(2, reproposed.total)
        assertTrue(reproposed.items.single { it.version.id == second.id }.needsNewReview)
        val summary = member.get("/api/v1/version-reviews/inbox/summary?q=zolw-inbox&scope=OWNED&attention=CHANGES_REQUESTED")
            .body<ReviewInboxSummary>()
        assertEquals(2, summary.total)
        assertEquals(0, summary.awaitingMyReview)
        assertEquals(0, summary.changesRequested)
        assertEquals(1, summary.needsNewReview)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$teamId/members/$memberId").status)
        assertEquals(0, member.get("$path&scope=OWNED").body<ReviewInboxPage>().total)
        assertEquals(2, member.get("$path&scope=FOLLOWED").body<ReviewInboxPage>().total)
        assertEquals(HttpStatusCode.NoContent, member.delete("/api/v1/contracts/${contract.id}/subscription").status)
        assertEquals(0, member.get(path).body<ReviewInboxPage>().total)

        for (query in listOf("page=0", "pageSize=101", "sort=version", "scope=nope", "attention=nope", "scope=ALL&scope=OWNED")) {
            assertEquals(HttpStatusCode.BadRequest, member.get("/api/v1/version-reviews/inbox?$query").status, query)
        }
        assertEquals(HttpStatusCode.BadRequest, member.get("/api/v1/version-reviews/inbox/summary?scope=bad").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/version-reviews/inbox").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/version-reviews/inbox/summary").status)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${contract.id}").status)
        assertEquals(0, member.get("$path&scope=ALL").body<ReviewInboxPage>().total)
    }
}
