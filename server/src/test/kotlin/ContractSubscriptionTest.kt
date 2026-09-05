package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ContractUpdateRequest
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.OwnerUpdateRequest
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.TreeResponse
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Following a contract (V13) and the notifications its events fan out to followers (V14) — never to the actor. */
class ContractSubscriptionTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun HttpClient.contract(prefix: String): ContractResponse {
        val systemId = TestContracts.seedSystem(prefix)
        val teamId = TestTeams.seed(name("t"))
        val request = ContractCreateRequest(systemId, ContractType.OPENAPI, name(prefix), null, ownerTeamId = teamId)
        return postJson("/api/v1/contracts", request).body()
    }

    private suspend fun HttpClient.notifications(): List<NotificationResponse> =
        get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items

    private val narrowedPetstore = ContractFixtures.openApi.replace("\n        name: { type: string }", "")

    @Test
    fun `follow - idempotent, visible as the caller's flag and the count on the detail and the tree, 404 when nothing to unfollow`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("sub-admin", UserRole.ADMIN)
            val follower = seededClient("sub-follower", UserRole.USER)
            val c = admin.contract("sub")
            val path = "/api/v1/contracts/${c.id}/subscription"
            assertEquals(HttpStatusCode.NoContent, follower.put(path).status)
            assertEquals(HttpStatusCode.NoContent, follower.put(path).status, "idempotent")
            val mine = follower.get("/api/v1/contracts/${c.id}").body<ContractResponse>()
            assertTrue(mine.subscribed)
            assertEquals(1, mine.subscriberCount)
            val theirs = admin.get("/api/v1/contracts/${c.id}").body<ContractResponse>()
            assertFalse(theirs.subscribed)
            assertEquals(1, theirs.subscriberCount)
            val inTree = admin.get("/api/v1/contracts/tree").body<TreeResponse>().domains.flatMap { it.systems }.flatMap { it.contracts }
                .single { it.id == c.id }
            assertEquals(1, inTree.subscriberCount)
            assertFalse(inTree.subscribed)
            withAuditCapture { capture ->
                assertEquals(HttpStatusCode.NoContent, follower.delete(path).status)
                assertNotNull(capture.awaitEvent { it.message == "contract.unsubscribed" && it.hasKeyValue("contractId", c.id.toLong()) })
            }
            assertEquals(HttpStatusCode.NotFound, follower.delete(path).status)
            assertEquals(HttpStatusCode.NotFound, follower.put("/api/v1/contracts/999999999/subscription").status)
            assertFalse(follower.get("/api/v1/contracts/${c.id}").body<ContractResponse>().subscribed)
        }

    @Test
    fun `fan-out - followers hear every event with the contract, actor and link, the actor never does, a waived breaking save pushes`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("fan-admin", UserRole.ADMIN)
            val follower = seededClient("fan-follower", UserRole.USER)
            val leaver = seededClient("fan-leaver", UserRole.USER)
            val bystander = seededClient("fan-bystander", UserRole.USER)
            val c = admin.contract("fan")
            follower.put("/api/v1/contracts/${c.id}/subscription")
            leaver.put("/api/v1/contracts/${c.id}/subscription")
            admin.put("/api/v1/contracts/${c.id}/subscription") // the actor follows too — and still hears nothing about their own acts

            val versions = "/api/v1/contracts/${c.id}/versions"
            val v1 = admin.postJson(versions, VersionCreateRequest("1.0.0", ContractFixtures.openApi)).body<VersionResponse>()
            val created = follower.notifications().single()
            assertEquals(NotificationType.VERSION_CREATED, created.type)
            assertEquals(mapOf("contractName" to c.name, "actor" to "Test", "version" to "1.0.0"), created.params)
            assertEquals("/contracts/${c.id}/versions/${v1.id}", created.link)
            assertFalse(created.wasSeen)
            assertEquals(emptyList(), admin.notifications(), "the actor is never a recipient")
            assertEquals(emptyList(), bystander.notifications())

            admin.postJson("/api/v1/contracts/${c.id}/versions/${v1.id}/transition", TransitionRequest(Lifecycle.PROPOSED))
            admin.postJson("/api/v1/contracts/${c.id}/versions/${v1.id}/transition", TransitionRequest(Lifecycle.ACTIVE))
            val transitions = follower.notifications().filter { it.type == NotificationType.VERSION_TRANSITIONED }
            assertEquals(2, transitions.size)
            assertTrue(transitions.any { it.params["from"] == "PROPOSED" && it.params["to"] == "ACTIVE" })
            assertTrue(transitions.all { it.params["version"] == "1.0.0" })

            // A waived breaking save: the VERSION_CREATED push plus the breaking one.
            val v2 = admin.postJson("$versions?allowInvalid=true", VersionCreateRequest("1.1.0", narrowedPetstore)).body<VersionResponse>()
            val breaking = follower.notifications().single { it.type == NotificationType.VERSION_BREAKING_STORED }
            assertEquals("1.1.0", breaking.params["version"])
            assertEquals("/contracts/${c.id}/versions/${v2.id}", breaking.link)
            assertEquals(2, follower.notifications().count { it.type == NotificationType.VERSION_CREATED })

            admin.putJson("/api/v1/contracts/${c.id}", ContractUpdateRequest(c.name + "-renamed", null))
            val renamed = follower.notifications().single { it.type == NotificationType.CONTRACT_UPDATED }
            assertEquals(c.name + "-renamed", renamed.params["name"])
            val newOwner = TestUsers.seed(uniqueEmail("fan-owner"), "pw", role = UserRole.USER)
            admin.putJson("/api/v1/contracts/${c.id}/owner", OwnerUpdateRequest(ownerUserId = newOwner))
            assertEquals("/contracts/${c.id}", follower.notifications().single { it.type == NotificationType.CONTRACT_OWNER_CHANGED }.link)

            // One follower leaves before the deletion: only the one who stays hears about it.
            leaver.delete("/api/v1/contracts/${c.id}/subscription")
            val before = leaver.notifications().size
            admin.delete("/api/v1/contracts/${c.id}/versions/${v2.id}")
            admin.postJson("/api/v1/contracts/${c.id}/versions/${v1.id}/transition", TransitionRequest(Lifecycle.DEPRECATED))
            admin.postJson("/api/v1/contracts/${c.id}/versions/${v1.id}/transition", TransitionRequest(Lifecycle.RETIRED))
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${c.id}").status)
            val deleted = follower.notifications().single { it.type == NotificationType.CONTRACT_DELETED }
            assertEquals(c.name + "-renamed", deleted.params["contractName"], "the deleted contract still names itself")
            assertEquals("/contracts/${c.id}", deleted.link)
            assertEquals(before, leaver.notifications().size)
            assertTrue(follower.notifications().any { it.type == NotificationType.VERSION_DELETED && it.params["version"] == "1.1.0" })
        }
}
