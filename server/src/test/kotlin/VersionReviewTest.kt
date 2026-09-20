package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractEventPageResponse
import ch.nokillswit.contracts.ContractEventType
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.CreateVersionReviewEntryRequest
import ch.nokillswit.contracts.CreateVersionReviewRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.SyncRequest
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionContentRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionReviewCloseReason
import ch.nokillswit.contracts.VersionReviewEntryKind
import ch.nokillswit.contracts.VersionReviewEntryPageResponse
import ch.nokillswit.contracts.VersionReviewEntryResponse
import ch.nokillswit.contracts.VersionReviewPageResponse
import ch.nokillswit.contracts.VersionReviewResponse
import ch.nokillswit.contracts.VersionReviewStatus
import ch.nokillswit.contracts.VersionSourceRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionReviewTest {
    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private suspend fun HttpClient.contract(prefix: String): ContractResponse {
        val systemId = TestContracts.seedSystem(prefix)
        val teamId = TestTeams.seed(name("review-team"))
        return postJson(
            "/api/v1/contracts",
            ContractCreateRequest(systemId, ContractType.OPENAPI, name(prefix), ownerTeamId = teamId),
        ).body()
    }

    private fun versions(contract: ContractResponse) = "/api/v1/contracts/${contract.id}/versions"
    private fun reviews(contract: ContractResponse, version: VersionResponse) = "${versions(contract)}/${version.id}/reviews"

    private suspend fun HttpClient.proposed(contract: ContractResponse, number: String = "1.0.0"): VersionResponse {
        val draft = postJson(versions(contract), VersionCreateRequest(number, ContractFixtures.openApi)).body<VersionResponse>()
        return postJson("${versions(contract)}/${draft.id}/transition", TransitionRequest(Lifecycle.PROPOSED)).body()
    }

    @Test
    fun `content revision is monotonic by bytes and an ABA edit outdates the bound round`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-revision", UserRole.ADMIN)
        val contract = owner.contract("review-revision")
        val proposed = owner.proposed(contract)
        assertEquals(1, proposed.contentRevision)
        val round = owner.postJson(reviews(contract, proposed), CreateVersionReviewRequest(1)).body<VersionReviewResponse>()

        val same = owner.putJson(
            "${versions(contract)}/${proposed.id}/content",
            VersionContentRequest(ContractFixtures.openApi),
        ).body<VersionResponse>()
        assertEquals(1, same.contentRevision)
        assertEquals(VersionReviewStatus.OPEN, owner.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>().status)
        val rechecked = owner.post("${versions(contract)}/${proposed.id}/recheck").body<VersionResponse>()
        assertEquals(1, rechecked.contentRevision)
        assertEquals(VersionReviewStatus.OPEN, owner.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>().status)

        val changedText = ContractFixtures.openApi + "\n# review revision\n"
        val changed = owner.putJson(
            "${versions(contract)}/${proposed.id}/content",
            VersionContentRequest(changedText),
        ).body<VersionResponse>()
        assertEquals(2, changed.contentRevision)
        val restored = owner.putJson(
            "${versions(contract)}/${proposed.id}/content",
            VersionContentRequest(ContractFixtures.openApi),
        ).body<VersionResponse>()
        assertEquals(3, restored.contentRevision)
        assertEquals(proposed.contentSha256, restored.contentSha256, "ABA restores the hash but never the revision")

        val outdated = owner.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>()
        assertEquals(VersionReviewStatus.OUTDATED, outdated.status)
        assertEquals(VersionReviewCloseReason.CONTENT_CHANGED, outdated.closeReason)
        assertFalse(outdated.isCurrentContent)
        assertEquals(HttpStatusCode.Conflict, owner.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "stale"),
        ).status)

        val source = owner.putJson(
            "${versions(contract)}/${proposed.id}/source",
            VersionSourceRequest("https://example.com/review.yaml"),
        )
        assertEquals(HttpStatusCode.NoContent, source.status)
        assertEquals(3, owner.get("${versions(contract)}/${proposed.id}").body<VersionResponse>().contentRevision)
        val synced = owner.postJson(
            "${versions(contract)}/${proposed.id}/sync",
            SyncRequest(ContractFixtures.openApi, "https://example.com/review.yaml"),
        ).body<VersionResponse>()
        assertEquals(3, synced.contentRevision, "an identical-byte sync only stamps sync metadata")
        val syncRound = owner.postJson(reviews(contract, synced), CreateVersionReviewRequest(3))
            .body<VersionReviewResponse>()
        val changedBySync = owner.postJson(
            "${versions(contract)}/${proposed.id}/sync",
            SyncRequest(ContractFixtures.openApiJson, "https://example.com/review.yaml"),
        ).body<VersionResponse>()
        assertEquals(4, changedBySync.contentRevision)
        val syncClosed = owner.get("/api/v1/version-reviews/${syncRound.id}").body<VersionReviewResponse>()
        assertEquals(VersionReviewCloseReason.CONTENT_CHANGED, syncClosed.closeReason)
        assertEquals(VersionReviewStatus.OUTDATED, syncClosed.status)
    }

    @Test
    fun `review discussion is immutable and latest reviewer decision determines the counts`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-owner", UserRole.ADMIN)
        val reviewer = seededClient("reviewer", UserRole.USER)
        val contract = owner.contract("review-flow")
        val version = owner.proposed(contract)
        reviewer.putJson("/api/v1/contracts/${contract.id}/subscription", Unit)

        assertEquals(
            HttpStatusCode.BadRequest,
            owner.postJson(reviews(contract, version), CreateVersionReviewRequest(0)).status,
        )

        val createdResponse = owner.postJson(reviews(contract, version), CreateVersionReviewRequest(1))
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        assertNotNull(createdResponse.headers[HttpHeaders.Location])
        val round = createdResponse.body<VersionReviewResponse>()
        assertFalse(round.canDecide)
        assertTrue(round.canComment)
        assertEquals(HttpStatusCode.BadRequest, reviewer.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(0, VersionReviewEntryKind.COMMENT, "invalid revision"),
        ).status)
        assertEquals(HttpStatusCode.Forbidden, owner.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.APPROVED),
        ).status)

        val comment = reviewer.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "  Please clarify this.  "),
        ).body<VersionReviewEntryResponse>()
        assertEquals("Please clarify this.", comment.body)
        reviewer.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.APPROVED),
        )
        reviewer.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.CHANGES_REQUESTED, "Needs a change"),
        )
        val current = reviewer.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>()
        assertEquals(0, current.approvalCount)
        assertEquals(1, current.changesRequestedCount)
        assertEquals(3, current.entryCount)
        assertEquals(HttpStatusCode.BadRequest, reviewer.postJson(
            "/api/v1/version-reviews/${round.id}/entries",
            CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "   "),
        ).status)
        val entries = reviewer.get("/api/v1/version-reviews/${round.id}/entries")
            .body<VersionReviewEntryPageResponse>()
        assertEquals(
            listOf(
                VersionReviewEntryKind.COMMENT,
                VersionReviewEntryKind.APPROVED,
                VersionReviewEntryKind.CHANGES_REQUESTED,
            ),
            entries.items.map { it.kind },
        )
        assertEquals(comment, reviewer.get("/api/v1/version-reviews/${round.id}/entries/${comment.id}").body())

        val events = owner.get("/api/v1/contracts/${contract.id}/events?pageSize=100")
            .body<ContractEventPageResponse>().items
        assertTrue(events.any { it.type == ContractEventType.VERSION_REVIEW_REQUESTED })
        assertTrue(events.any { it.type == ContractEventType.VERSION_REVIEW_COMMENTED && "body" !in it.params })
        assertTrue(events.any {
            it.type == ContractEventType.VERSION_REVIEW_CHANGES_REQUESTED &&
                it.params["decision"] == "CHANGES_REQUESTED"
        })
        val notifications = reviewer.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
        val requested = notifications.single { it.type == NotificationType.VERSION_REVIEW_REQUESTED }
        assertEquals(round.id.toString(), requested.params["reviewId"])
        assertFalse("body" in requested.params)
        assertEquals("/contracts/${contract.id}/versions/${version.id}", requested.link)
        assertFalse(notifications.any {
            it.type == NotificationType.VERSION_REVIEW_COMMENTED ||
                it.type == NotificationType.VERSION_REVIEW_APPROVED ||
                it.type == NotificationType.VERSION_REVIEW_CHANGES_REQUESTED
        }, "the actor never receives notifications for their own entries")
    }

    @Test
    fun `concurrent review requests create exactly one open round`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-concurrent-request", UserRole.ADMIN)
        val contract = owner.contract("review-concurrent-request")
        val version = owner.proposed(contract)
        val statuses = coroutineScope {
            val first = async { owner.postJson(reviews(contract, version), CreateVersionReviewRequest(1)).status }
            val second = async { owner.postJson(reviews(contract, version), CreateVersionReviewRequest(1)).status }
            listOf(first.await(), second.await())
        }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
        val page = owner.get(reviews(contract, version)).body<VersionReviewPageResponse>()
        assertEquals(1, page.total)
        assertFalse(page.canRequest)
    }

    @Test
    fun `a concurrent edit cannot leave an entry appended after its round closes`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-edit-race-owner", UserRole.ADMIN)
        val reviewer = seededClient("review-edit-race-reviewer", UserRole.USER)
        val contract = owner.contract("review-edit-race")
        val version = owner.proposed(contract)
        val round = owner.postJson(reviews(contract, version), CreateVersionReviewRequest(1))
            .body<VersionReviewResponse>()
        val outcomes = coroutineScope {
            val edit = async {
                owner.putJson(
                    "${versions(contract)}/${version.id}/content",
                    VersionContentRequest(ContractFixtures.openApi + "\n# concurrent review edit\n"),
                ).status
            }
            val entry = async {
                reviewer.postJson(
                    "/api/v1/version-reviews/${round.id}/entries",
                    CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "racing comment"),
                ).status
            }
            edit.await() to entry.await()
        }
        assertEquals(HttpStatusCode.OK, outcomes.first)
        assertTrue(outcomes.second == HttpStatusCode.Created || outcomes.second == HttpStatusCode.Conflict)
        val closed = owner.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>()
        assertEquals(VersionReviewStatus.OUTDATED, closed.status)
        assertEquals(VersionReviewCloseReason.CONTENT_CHANGED, closed.closeReason)
        val entries = owner.get("/api/v1/version-reviews/${round.id}/entries")
            .body<VersionReviewEntryPageResponse>().items
        if (outcomes.second == HttpStatusCode.Created) {
            assertEquals(1, entries.size)
            assertTrue(entries.single().createdAt <= checkNotNull(closed.closedAt))
        } else {
            assertTrue(entries.isEmpty())
        }
    }

    @Test
    fun `lifecycle exits close rounds and an open round outside the requested page still prevents requests`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-close", UserRole.ADMIN)
        val contract = owner.contract("review-close")
        var version = owner.proposed(contract)
        val first = owner.postJson(reviews(contract, version), CreateVersionReviewRequest(version.contentRevision))
            .body<VersionReviewResponse>()
        owner.postJson("${versions(contract)}/${version.id}/transition", TransitionRequest(Lifecycle.DRAFT))
        assertEquals(
            VersionReviewCloseReason.WITHDRAWN,
            owner.get("/api/v1/version-reviews/${first.id}").body<VersionReviewResponse>().closeReason,
        )

        version = owner.postJson(
            "${versions(contract)}/${version.id}/transition",
            TransitionRequest(Lifecycle.PROPOSED),
        ).body()
        val second = owner.postJson(reviews(contract, version), CreateVersionReviewRequest(version.contentRevision))
            .body<VersionReviewResponse>()
        val secondPage = owner.get("${reviews(contract, version)}?page=2&pageSize=1")
            .body<VersionReviewPageResponse>()
        assertFalse(secondPage.canRequest, "the open round is on page one, outside this page")
        assertEquals(
            HttpStatusCode.Conflict,
            owner.postJson(reviews(contract, version), CreateVersionReviewRequest(version.contentRevision)).status,
        )

        owner.postJson("${versions(contract)}/${version.id}/transition", TransitionRequest(Lifecycle.ACTIVE))
        val published = owner.get("/api/v1/version-reviews/${second.id}").body<VersionReviewResponse>()
        assertEquals(VersionReviewStatus.CLOSED, published.status)
        assertEquals(VersionReviewCloseReason.PUBLISHED, published.closeReason)
        assertTrue(published.isCurrentContent)
    }

    @Test
    fun `active-user guards run before malformed review bodies`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("review-deleted-owner")
        val ownerId = TestUsers.seed(email, "pw", role = UserRole.ADMIN)
        val owner = authedClient(email, "pw")
        val contract = owner.contract("review-deleted")
        val version = owner.proposed(contract)
        TestUsers.softDelete(ownerId)
        val malformedRequest = owner.post(reviews(contract, version)) {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.Forbidden, malformedRequest.status)

        val activeOwner = seededClient("review-active-owner", UserRole.ADMIN)
        val secondContract = activeOwner.contract("review-deleted-entry")
        val secondVersion = activeOwner.proposed(secondContract)
        val round = activeOwner.postJson(reviews(secondContract, secondVersion), CreateVersionReviewRequest(1))
            .body<VersionReviewResponse>()
        val reviewerEmail = uniqueEmail("review-deleted-reviewer")
        val reviewerId = TestUsers.seed(reviewerEmail, "pw", role = UserRole.USER)
        val reviewer = authedClient(reviewerEmail, "pw")
        TestUsers.softDelete(reviewerId)
        val malformedEntry = reviewer.post("/api/v1/version-reviews/${round.id}/entries") {
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.Forbidden, malformedEntry.status)
        assertNull(activeOwner.get("/api/v1/version-reviews/${round.id}").body<VersionReviewResponse>().myDecision)
    }

    @Test
    fun `review resources return scoped forbidden and not found responses`() = testApplication {
        usePostgresTestcontainer()
        val owner = seededClient("review-status-owner", UserRole.ADMIN)
        val stranger = seededClient("review-status-stranger", UserRole.USER)
        val contract = owner.contract("review-status")
        val version = owner.proposed(contract)
        assertEquals(
            HttpStatusCode.Forbidden,
            stranger.postJson(reviews(contract, version), CreateVersionReviewRequest(1)).status,
        )
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/contracts/999999999/versions/1/reviews").status)
        assertEquals(
            HttpStatusCode.NotFound,
            owner.postJson("/api/v1/contracts/999999999/versions/1/reviews", CreateVersionReviewRequest(1)).status,
        )
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/version-reviews/999999999").status)
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/version-reviews/999999999/entries").status)
        assertEquals(
            HttpStatusCode.NotFound,
            owner.postJson(
                "/api/v1/version-reviews/999999999/entries",
                CreateVersionReviewEntryRequest(1, VersionReviewEntryKind.COMMENT, "missing"),
            ).status,
        )
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/version-reviews/999999999/entries/1").status)
    }
}
