package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ToadieAdoptionTest {
    @Test
    fun `authenticated adoption endpoint pages raw declarations and includes unmatched consumers`() = testApplication {
        usePostgresTestcontainer()
        val ownerId = TestUsers.seed(uniqueEmail("adoption-owner"), "pw", role = UserRole.ADMIN)
        val owner = authedClient(TestUsers.service.read(ownerId)!!.email, "pw")
        val reader = seededClient("adoption-reader")
        val contract = owner.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(
                TestContracts.seedSystem("adoption"), ContractType.OPENAPI, "adoption-${System.nanoTime()}",
                ownerUserId = ownerId,
            ),
        ).body<ContractResponse>()
        val service = ToadieService(
            sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )
        val mapping = ToadieAdoptionMapping(environmentRelation = "environment")
        val connectionId = service.create(ToadieConnectionRequest(
            name = "adoption-${System.nanoTime()}", baseUrl = "http://adoption.internal",
            browserUrl = "http://adoption.internal", apiKey = "secret", enabled = true,
            refreshIntervalMinutes = 60, adoptionMapping = mapping,
        ))
        try {
            val entities = listOf(
                ToadieEntitySnapshot("1", "api", "orders", "Orders", emptyList(), emptyMap(), 1),
                ToadieEntitySnapshot("2", "service", "checkout", "Checkout", emptyList(),
                    mapOf("consumes_apis" to emptyList()), 1),
                ToadieEntitySnapshot("3", "environment", "production", "Production", emptyList(), emptyMap(), 1),
                ToadieEntitySnapshot("4", "api_adoption", "declared-orders", "Declared Orders", emptyList(),
                    mapOf("consumer" to listOf("checkout"), "api" to listOf("orders"),
                        "environment" to listOf("production")), 1,
                    scalarProperties = mapOf("major_line" to "v2", "status" to "ready",
                        "verified_at" to "2026-09-22T08:15:30Z")),
                ToadieEntitySnapshot("5", "api_adoption", "all-orders", "All Orders", emptyList(),
                    mapOf("consumer" to listOf("checkout"), "api" to listOf("orders"),
                        "environment" to emptyList()), 1),
            )
            val claim = service.claimRefresh(connectionId, false).second!!
            assertTrue(service.publish(claim, ToadieSnapshot(
                entities, "system", System.currentTimeMillis(), 1,
                ToadieAdoptionAvailability.AVAILABLE, "environment",
            )))
            assertTrue(service.replaceLinks(
                contract.id, ToadieLinksRequest(connectionId, listOf("1")),
                CallerPrincipal(ownerId, "owner@test", setOf(UserRole.ADMIN)),
            ))

            val path = "/api/v1/contracts/${contract.id}/toadie-adoptions?page=1&pageSize=1&sort=-title&q=declared-orders"
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(path).status)
            val response = reader.get(path)
            assertEquals(HttpStatusCode.OK, response.status)
            val page = response.body<ToadieAdoptionResponse>()
            assertEquals(ToadieAdoptionAvailability.AVAILABLE, page.availability)
            assertEquals(1, page.total)
            val row = page.items.single()
            assertEquals("v2", row.value)
            assertEquals(1_790_064_930_000, row.verifiedAt)
            assertEquals(ToadieAdoptionEnvironmentScope.SPECIFIC, row.environmentScope)
            assertEquals("production", row.environment?.identifier)
            assertFalse(row.matchesConsumption)
            val all = reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions?pageSize=100")
                .body<ToadieAdoptionResponse>()
            assertEquals(ToadieAdoptionEnvironmentScope.ALL,
                all.items.single { it.identifier == "all-orders" }.environmentScope)
            assertNull(all.items.single { it.identifier == "all-orders" }.value)

            val failed = service.claimRefresh(connectionId, false).second!!
            service.fail(failed, "INVALID_RESPONSE")
            val retained = reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions?pageSize=100")
                .body<ToadieAdoptionResponse>()
            assertEquals(ToadieCacheState.STALE, retained.cache.state)
            assertEquals(2, retained.items.size, "a failed scan cannot publish a false zero")

            val unknownMapping = mapping.copy(environmentRelation = null)
            assertEquals(1, service.update(connectionId, ToadieConnectionRequest(
                name = "adoption-updated-${System.nanoTime()}", baseUrl = "http://adoption.internal",
                browserUrl = "http://adoption.internal", enabled = true, refreshIntervalMinutes = 60,
                adoptionMapping = unknownMapping,
            )))
            val invalidated = reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions")
                .body<ToadieAdoptionResponse>()
            assertEquals(ToadieAdoptionAvailability.NOT_SCANNED, invalidated.availability)
            assertTrue(invalidated.items.isEmpty())
            val rescan = service.claimRefresh(connectionId, false).second!!
            assertNull(rescan.config.knownRevision)
            val unknownEntities = entities.filterNot { it.id == "3" || it.id == "5" }.map { entity ->
                if (entity.id == "4") entity.copy(
                    relations = mapOf("consumer" to listOf("checkout"), "api" to listOf("orders")),
                    scalarProperties = emptyMap(),
                ) else entity
            }
            assertTrue(service.publish(rescan, ToadieSnapshot(
                unknownEntities, "system", System.currentTimeMillis(), 2,
                ToadieAdoptionAvailability.AVAILABLE,
            )))
            val unknown = reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions").body<ToadieAdoptionResponse>()
            assertEquals(ToadieAdoptionEnvironmentScope.UNKNOWN, unknown.items.single().environmentScope)
            assertNull(unknown.items.single().value)
            assertEquals(HttpStatusCode.NotFound,
                reader.get("/api/v1/contracts/2147483647/toadie-adoptions").status)
        } finally {
            service.delete(connectionId)
        }
    }
}
