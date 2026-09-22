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
import kotlinx.coroutines.flow.single
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
    fun `ODCS dataset usage and API usage remain isolated on connections to the same instance`() = testApplication {
        usePostgresTestcontainer()
        val userId = TestUsers.seed(uniqueEmail("dataset-owner"), "pw")
        val caller = CallerPrincipal(userId, "owner@test", emptySet())
        val contracts = ContractService(sharedDatabaseForTests(), TestTeams.service)
        val service = ToadieService(sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY), contracts)
        val systemId = TestContracts.seedSystem("dataset-usage")
        val odcs = contracts.create(
            ContractCreateRequest(systemId, ContractType.ODCS, "dataset-${System.nanoTime()}", ownerUserId = userId), caller,
        )
        val api = contracts.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, "api-${System.nanoTime()}", ownerUserId = userId), caller,
        )
        val connectionIds = mutableListOf<UInt>()
        try {
            val apiConnection = service.create(request("api-${System.nanoTime()}")).also(connectionIds::add)
            val datasetConnection = service.create(request("dataset-${System.nanoTime()}").copy(mapping = ToadieMapping(
                apiBlueprint = "dataset", providesRelation = "produces_datasets", consumesRelation = "consumes_datasets",
            ))).also(connectionIds::add)
            fun entity(id: String, blueprint: String, identifier: String, relations: Map<String, List<String>> = emptyMap()) =
                ToadieEntitySnapshot(id, blueprint, identifier, identifier, emptyList(), relations, 1)
            val apiSnapshot = ToadieSnapshot(listOf(
                entity("10", "api", "orders"),
                entity("20", "service", "api-provider", mapOf("provides_apis" to listOf("orders"))),
            ), "system", System.currentTimeMillis(), revision = 1)
            val datasetSnapshot = ToadieSnapshot(listOf(
                entity("10", "dataset", "orders"),
                entity("11", "dataset", "settlements"),
                entity("20", "service", "pipeline", mapOf(
                    "produces_datasets" to listOf("orders", "settlements"),
                    "consumes_datasets" to listOf("orders"),
                )),
                entity("21", "service", "database-only", mapOf("depends_on" to listOf("warehouse"))),
            ), "system", System.currentTimeMillis(), revision = 1)
            assertTrue(service.publish(assertNotNull(service.claimRefresh(apiConnection, force = false).second), apiSnapshot))
            assertTrue(service.publish(assertNotNull(service.claimRefresh(datasetConnection, force = false).second), datasetSnapshot))
            assertTrue(service.replaceLinks(api, ToadieLinksRequest(apiConnection, listOf("10")), caller))
            assertTrue(service.replaceLinks(odcs, ToadieLinksRequest(datasetConnection, listOf("10", "11")), caller))
            val page = PageRequest(1, 20, listOf(SortField("id", false)))
            val usage = assertNotNull(service.usage(odcs, null, null, page))
            assertEquals(1, usage.total)
            val pipeline = usage.items.single()
            assertEquals("pipeline", pipeline.identifier)
            assertEquals(setOf(ToadieUsageRole.PROVIDER, ToadieUsageRole.CONSUMER), pipeline.roles.toSet())
            assertEquals(setOf("10", "11"), pipeline.providedApiEntityIds.toSet())
            assertEquals(listOf("10"), pipeline.consumedApiEntityIds)
            assertNull(pipeline.version)
            assertNull(pipeline.releaseLine)
            assertEquals("api-provider", assertNotNull(service.usage(api, null, null, page)).items.single().identifier)
            service.fail(assertNotNull(service.claimRefresh(datasetConnection, force = false).second), "fixture failure")
            assertEquals(ToadieCacheState.STALE, assertNotNull(service.usage(odcs, null, null, page)).cache.state)
            assertEquals(ToadieCacheState.CURRENT, assertNotNull(service.usage(api, null, null, page)).cache.state)
            assertEquals("pipeline", assertNotNull(service.usage(odcs, null, null, page)).items.single().identifier)
            assertTrue(service.publish(assertNotNull(service.claimRefresh(datasetConnection, force = false).second),
                datasetSnapshot.copy(entities = datasetSnapshot.entities.filter { it.id != "11" }, revision = 2)))
            val links = assertNotNull(service.links(odcs))
            assertEquals(ToadieLinkStatus.MISSING, links.items.single { it.apiEntityId == "11" }.status)
            assertEquals(ToadieLinkStatus.AVAILABLE, assertNotNull(service.links(api)).items.single().status)
        } finally {
            connectionIds.forEach { service.delete(it) }
        }
    }

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
    fun `manual refresh promotes an in-flight revision probe to one forced full claim`() = testApplication {
        usePostgresTestcontainer()
        val service = ToadieService(
            sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )
        val connectionId = service.create(request("promotion-${System.nanoTime()}"))
        try {
            val initialClaim = service.claimRefresh(connectionId, force = false).second!!
            assertTrue(service.publish(initialClaim, ToadieSnapshot(emptyList(), "system", 100, 11)))

            val scheduled = service.claimRefresh(connectionId, force = false).second!!
            assertEquals(11, scheduled.config.knownRevision)
            val (promotedResult, promoted) = service.claimRefresh(connectionId, force = true)
            assertEquals(RefreshClaimResult.ACCEPTED, promotedResult)
            assertNull(promoted!!.config.knownRevision)
            assertNotEquals(scheduled.token, promoted.token)
            assertEquals(RefreshClaimResult.COALESCED, service.claimRefresh(connectionId, force = true).first)
            assertFalse(service.publish(scheduled, ToadieUnchanged(11, 200)))
            service.fail(scheduled, "OBSOLETE")
            service.release(scheduled)
            assertTrue(service.publish(promoted, ToadieSnapshot(emptyList(), "system", 300, 12)))

            val next = service.claimRefresh(connectionId, force = false).second!!
            assertEquals(12, next.config.knownRevision)
            service.release(next)
        } finally {
            service.delete(connectionId)
        }
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
            revision = 1,
        )
        assertTrue(service.publish(claim, snapshot))
        suspend fun snapshotIdentity(): String? = suspendTransaction(sharedDatabaseForTests()) {
            exec(
                "SELECT string_agg(entity_id || ':' || xmin::text, ',' ORDER BY entity_id) " +
                    "FROM toadie_snapshot_entities WHERE connection_id = ${connectionId.toLong()}",
            ) { row -> row.get(0, String::class.java) }?.single()
        }
        val originalSnapshotIdentity = snapshotIdentity()
        suspend fun storedState(): Triple<Long?, Long?, Long> = suspendTransaction(sharedDatabaseForTests()) {
            val connection = ToadieService.Connections.selectAll().where { ToadieService.Connections.id eq connectionId }
                .toList().single()
            Triple(
                connection[ToadieService.Connections.remoteRevision],
                connection[ToadieService.Connections.lastSuccessAt],
                ToadieService.SnapshotEntities.selectAll().where {
                    ToadieService.SnapshotEntities.connectionId eq connectionId
                }.count(),
            )
        }
        assertEquals(Triple(1L, snapshot.fetchedAt, 4L), storedState())
        val unchangedClaim = service.claimRefresh(connectionId, force = false).second!!
        assertEquals(1, unchangedClaim.config.knownRevision)
        val checkedAt = snapshot.fetchedAt + 100
        assertTrue(service.publish(unchangedClaim, ToadieUnchanged(1, checkedAt)))
        assertEquals(Triple(1L, checkedAt, 4L), storedState(), "unchanged verification never rewrites snapshot rows")
        assertEquals(originalSnapshotIdentity, snapshotIdentity(), "unchanged verification preserves the physical cache rows")
        assertTrue(service.replaceLinks(contractId, ToadieLinksRequest(connectionId, listOf("10")), caller))

        val usage = assertNotNull(service.usage(contractId, null, null, PageRequest(1, 20, listOf(SortField("id", false)))))
        assertEquals(1, usage.total)
        assertEquals(listOf(ToadieUsageRole.PROVIDER), usage.items.single().roles)
        assertEquals("commerce", usage.items.single().systems.single().identifier)
        assertEquals("platform", usage.items.single().teams.single().identifier)
        assertNull(usage.items.single().version)

        val failedClaim = assertNotNull(service.claimRefresh(connectionId, force = false).second)
        assertEquals(1, failedClaim.config.knownRevision)
        service.fail(failedClaim, "graphql partial response: token")
        assertEquals(1, storedState().first, "a failed refresh retains the last observed revision")
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
        assertFalse(service.publish(staleClaim, ToadieUnchanged(1, System.currentTimeMillis())))
        val displayClaim = service.claimRefresh(connectionId, force = false).second!!
        assertEquals(1, displayClaim.config.knownRevision, "display-only changes retain the observed revision")
        service.release(displayClaim)
        val afterStalePublish = service.usage(
            contractId, null, null, PageRequest(1, 20, listOf(SortField("id", false))),
        )
        assertEquals("Checkout", afterStalePublish!!.items.single().title)

        assertEquals(1, service.update(connectionId, replacement.copy(apiKey = "rotated-secret")))
        val rotatedKeyClaim = service.claimRefresh(connectionId, force = false).second!!
        assertNull(rotatedKeyClaim.config.knownRevision)
        service.release(rotatedKeyClaim)
        assertEquals(4, storedState().third, "key replacement retains the last cache while forcing a full scan")

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
