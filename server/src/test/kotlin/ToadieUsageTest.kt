package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.core.eq
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.*

class ToadieUsageTest {
    private fun request(name: String = "Toadie") = ToadieConnectionRequest(
        name = name,
        baseUrl = "http://toadie.internal/integration/graphql",
        browserUrl = "http://toadie.internal",
        apiKey = "secret-api-key",
        enabled = true,
        refreshIntervalMinutes = 60,
    )

    @Test
    fun `connection registry is admin curated and never returns the API key`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("toadie-admin", UserRole.ADMIN)
        val user = seededClient("toadie-user")

        assertEquals(HttpStatusCode.Forbidden, user.postJson("/api/v1/toadie-connections", request("denied")).status)
        val created = admin.postJson("/api/v1/toadie-connections", request("conn-${System.nanoTime()}"))
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.bodyAsText()
        val response = Json.decodeFromString<ToadieConnectionResponse>(body)
        assertTrue(response.hasApiKey)
        assertFalse(body.contains("secret-api-key"))
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/toadie-connections/${response.id}").status)

        val stored = suspendTransaction(sharedDatabaseForTests()) {
            ToadieService.Connections.selectAll().where { ToadieService.Connections.id eq response.id }
                .toList().single()[ToadieService.Connections.apiKey]
        }
        assertTrue(stored.startsWith(FieldCipher.PREFIX))
    }

    @Test
    fun `validation accepts Port identifiers and refuses link-local targets`() {
        val mapped = request().copy(
            mapping = ToadieMapping(
                serviceBlueprint = "acme/service:v1",
                apiBlueprint = "@acme/api=v2",
                providesRelation = "provides-apis",
                consumesRelation = "consumes_apis",
                systemRelation = "system.parent",
            ),
        )
        validateToadieRequest(mapped, apiKeyRequired = true, allowHttp = true)
        assertFailsWith<io.ktor.server.plugins.BadRequestException> {
            validateToadieRequest(mapped.copy(baseUrl = "http://169.254.10.20/graphql"), true, true)
        }
        assertFailsWith<io.ktor.server.plugins.BadRequestException> {
            validateToadieRequest(mapped.copy(baseUrl = "http://2852039166/graphql"), true, true)
        }
        assertFailsWith<io.ktor.server.plugins.BadRequestException> {
            validateToadieRequest(mapped.copy(browserUrl = "http://[fe80::1]"), true, true)
        }
        assertFailsWith<io.ktor.server.plugins.BadRequestException> {
            validateToadieRequest(mapped.copy(baseUrl = "http://toadie.internal/graphql"), true, false)
        }
    }

    @Test
    fun `complete snapshots project linked providers and a failed refresh retains the last good cache`() = testApplication {
        usePostgresTestcontainer()
        val userId = TestUsers.seed(uniqueEmail("toadie-owner"), "pw")
        val caller = CallerPrincipal(userId, "owner@test", emptySet())
        val contractService = ContractService(sharedDatabaseForTests(), TestTeams.service)
        val service = ToadieService(sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY), contractService)
        val systemId = TestContracts.seedSystem("toadie-usage")
        val contractId = contractService.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, "contract-${System.nanoTime()}", ownerUserId = userId),
            caller,
        )
        val connectionId = service.create(request("projection-${System.nanoTime()}"))
        val claim = assertNotNull(service.claimRefresh(connectionId, force = false).second)
        val snapshot = ToadieSnapshot(
            entities = listOf(
                ToadieEntitySnapshot("10", "API", "payments", "Payments API", emptyList(), emptyMap(), 1),
                ToadieEntitySnapshot(
                    "20", "SERVICE", "checkout", "Checkout", listOf("platform"),
                    mapOf("provides_apis" to listOf("payments"), "system" to listOf("commerce")), 1,
                ),
                ToadieEntitySnapshot("30", "SYSTEM", "commerce", "Commerce", emptyList(), emptyMap(), 1),
                ToadieEntitySnapshot("40", "_TEAM", "platform", "Platform", emptyList(), emptyMap(), 1),
            ),
            systemBlueprint = "system",
            fetchedAt = System.currentTimeMillis(),
        )
        assertTrue(service.publish(claim, snapshot))
        assertTrue(service.replaceLinks(contractId, ToadieLinksRequest(connectionId, listOf("10")), caller))

        val usage = assertNotNull(service.usage(contractId, null, null, PageRequest(1, 20, listOf(SortField("id", false)))))
        assertEquals(1, usage.total)
        assertEquals(listOf(ToadieUsageRole.PROVIDER), usage.items.single().roles)
        assertEquals("commerce", usage.items.single().systems.single().identifier)
        assertEquals("platform", usage.items.single().teams.single().identifier)
        assertNull(usage.items.single().version)

        val failedClaim = assertNotNull(service.claimRefresh(connectionId, force = false).second)
        service.fail(failedClaim, "graphql partial response: token")
        val retained = assertNotNull(service.usage(contractId, null, null, PageRequest(1, 20, listOf(SortField("id", false)))))
        assertEquals(ToadieCacheState.STALE, retained.cache.state)
        assertEquals("Checkout", retained.items.single().title)
        assertEquals("GRAPHQL_PARTIAL_RESPONSE_TOKEN", retained.cache.lastErrorCode)

        val hugePage = assertNotNull(
            service.usage(contractId, null, null, PageRequest(Int.MAX_VALUE, 100, listOf(SortField("id", false)))),
        )
        assertTrue(hugePage.items.isEmpty())

        val staleClaim = assertNotNull(service.claimRefresh(connectionId, force = false).second)
        val replacement = request("projection-${System.nanoTime()}").copy(
            baseUrl = request().baseUrl,
            browserUrl = "http://renamed-toadie.internal",
            apiKey = null,
        )
        assertEquals(1, service.update(connectionId, replacement))
        assertFalse(service.publish(staleClaim, snapshot.copy(fetchedAt = System.currentTimeMillis())))
        val afterStalePublish = service.usage(
            contractId, null, null, PageRequest(1, 20, listOf(SortField("id", false))),
        )
        assertEquals("Checkout", afterStalePublish!!.items.single().title)

        val remapped = replacement.copy(mapping = replacement.mapping.copy(apiBlueprint = "different-api"))
        assertEquals(1, service.update(connectionId, remapped))
        assertEquals(ToadieLinkStatus.MISSING, service.links(contractId)!!.items.single().status)
        val currentClaim = service.claimRefresh(connectionId, force = false).second!!
        val reusedId = snapshot.copy(
            entities = listOf(snapshot.entities.first().copy(blueprint = "service")),
            fetchedAt = System.currentTimeMillis(),
        )
        assertTrue(service.publish(currentClaim, reusedId))
        assertEquals(ToadieLinkStatus.MISSING, service.links(contractId)!!.items.single().status)
        val nextClaim = service.claimRefresh(connectionId, force = false).second!!
        service.release(staleClaim)
        assertEquals(RefreshClaimResult.COALESCED, service.claimRefresh(connectionId, force = false).first)
        service.release(nextClaim)
        val reclaimed = service.claimRefresh(connectionId, force = false).second!!
        service.release(reclaimed)
        assertEquals(1, service.delete(connectionId))
        val disconnected = assertNotNull(service.links(contractId))
        assertEquals(ToadieCacheState.DISCONNECTED, disconnected.cache.state)
        assertEquals(ToadieLinkStatus.DISCONNECTED, disconnected.items.single().status)
    }
}
