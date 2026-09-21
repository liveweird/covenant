package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToadieRoutesTest {
    private val request = ToadieConnectionRequest(
        name = "route-${System.nanoTime()}",
        baseUrl = "http://toadie-route.internal/integration/graphql",
        browserUrl = "http://toadie-route.internal",
        apiKey = "route-secret",
        enabled = true,
        refreshIntervalMinutes = 60,
    )

    @Test
    fun `routes enforce permissions statuses and cached projections`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("toadie-route-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("toadie-route-owner"), "pw")
        val owner = authedClient(TestUsers.service.read(ownerId)!!.email, "pw")
        val stranger = seededClient("toadie-route-stranger")
        val contractId = seedOwnedContract(ownerId)
        val direct = ToadieService(
            sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )

        assertEquals(HttpStatusCode.OK, owner.get("/api/v1/toadie-connections").status)
        val created = admin.postJson("/api/v1/toadie-connections", request)
        assertEquals(HttpStatusCode.Created, created.status)
        val connection = created.body<ToadieConnectionResponse>()
        waitForIdle(admin, connection.id)
        val seedClaim = direct.claimRefresh(connection.id, force = false).second!!
        assertTrue(direct.publish(seedClaim, routeSnapshot()))
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/toadie-connections", request).status)
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/toadie-connections/2147483647").status)

        assertEquals(HttpStatusCode.Forbidden, stranger.putJson("/api/v1/toadie-connections/${connection.id}", request).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/toadie-connections/2147483647", request).status)
        assertEquals(
            HttpStatusCode.Conflict,
            admin.putJson("/api/v1/toadie-connections/${connection.id}", request.copy(baseUrl = "http://other.internal")).status,
        )
        val replacement = request.copy(name = "updated-${System.nanoTime()}", apiKey = null)
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/toadie-connections/${connection.id}", replacement).status)
        waitForIdle(admin, connection.id)

        assertEquals(HttpStatusCode.Forbidden, stranger.post("/api/v1/toadie-connections/${connection.id}/refresh").status)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/toadie-connections/2147483647/refresh").status)
        val connectionClaim = direct.claimRefresh(connection.id, force = false).second!!
        assertEquals(HttpStatusCode.Accepted, admin.post("/api/v1/toadie-connections/${connection.id}/refresh").status)
        assertFalse(direct.publish(connectionClaim, routeSnapshot()))
        waitForIdle(admin, connection.id)
        assertTrue(direct.publish(direct.claimRefresh(connection.id, force = false).second!!, routeSnapshot()))
        val api = waitForApi(admin, connection.id)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/toadie-connections/2147483647/apis").status)

        assertEquals(HttpStatusCode.OK, owner.get("/api/v1/contracts/$contractId/toadie-links").status)
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/contracts/2147483647/toadie-links").status)
        val links = ToadieLinksRequest(connection.id, listOf(api.entityId))
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson("/api/v1/contracts/$contractId/toadie-links", links).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/contracts/2147483647/toadie-links", links).status)
        assertEquals(HttpStatusCode.NoContent, owner.putJson("/api/v1/contracts/$contractId/toadie-links", links).status)
        assertEquals(HttpStatusCode.OK, stranger.get("/api/v1/contracts/$contractId/toadie-usage").status)
        assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/contracts/2147483647/toadie-usage").status)

        assertEquals(HttpStatusCode.Forbidden, stranger.post("/api/v1/contracts/$contractId/toadie-usage/refresh").status)
        assertEquals(HttpStatusCode.NotFound, owner.post("/api/v1/contracts/2147483647/toadie-usage/refresh").status)
        val usageClaim = direct.claimRefresh(connection.id, force = false).second!!
        assertEquals(HttpStatusCode.Accepted, owner.post("/api/v1/contracts/$contractId/toadie-usage/refresh").status)
        assertFalse(direct.publish(usageClaim, routeSnapshot()))
        waitForIdle(admin, connection.id)
        assertTrue(direct.publish(direct.claimRefresh(connection.id, force = false).second!!, routeSnapshot()))
        assertEquals(HttpStatusCode.TooManyRequests, admin.post("/api/v1/toadie-connections/${connection.id}/refresh").status)

        assertEquals(HttpStatusCode.Forbidden, stranger.delete("/api/v1/toadie-connections/${connection.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/toadie-connections/2147483647").status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/toadie-connections/${connection.id}").status)
    }

    private suspend fun ApplicationTestBuilder.seedOwnedContract(ownerId: UInt): UInt {
        val service = ContractService(sharedDatabaseForTests(), TestTeams.service)
        return service.create(
            ContractCreateRequest(
                TestContracts.seedSystem("toadie-routes"), ContractType.OPENAPI,
                "route-contract-${System.nanoTime()}", ownerUserId = ownerId,
            ),
            CallerPrincipal(ownerId, "owner@test", emptySet()),
        )
    }

    private suspend fun waitForApi(client: io.ktor.client.HttpClient, connectionId: UInt): ToadieEntityRef {
        repeat(50) {
            val response = client.get("/api/v1/toadie-connections/$connectionId/apis")
            assertEquals(HttpStatusCode.OK, response.status)
            val page = response.body<PageResponse<ToadieEntityRef>>()
            page.items.firstOrNull()?.let { return it }
            delay(50)
        }
        error("Timed out waiting for cached Toadie API")
    }

    private suspend fun waitForIdle(client: io.ktor.client.HttpClient, connectionId: UInt) {
        repeat(50) {
            if (!client.get("/api/v1/toadie-connections/$connectionId").body<ToadieConnectionResponse>().refreshing) return
            delay(50)
        }
        error("Timed out waiting for Toadie refresh")
    }
}

private fun routeSnapshot() = ToadieSnapshot(
    entities = listOf(
        ToadieEntitySnapshot("api-1", "api", "orders", "Orders", emptyList(), emptyMap(), 1),
        ToadieEntitySnapshot(
            "service-1", "service", "checkout", "Checkout", listOf("platform"),
            mapOf("provides_apis" to listOf("orders"), "system" to listOf("commerce")), 1,
        ),
        ToadieEntitySnapshot("system-1", "system", "commerce", "Commerce", emptyList(), emptyMap(), 1),
        ToadieEntitySnapshot("team-1", "_team", "platform", "Platform", emptyList(), emptyMap(), 1),
    ),
    systemBlueprint = "system",
    fetchedAt = System.currentTimeMillis(),
    revision = 1,
)
