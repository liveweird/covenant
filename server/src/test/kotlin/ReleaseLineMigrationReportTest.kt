package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.contracts.*
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class ReleaseLineMigrationReportTest {
    private fun connectionRequest(enabled: Boolean = true) = ToadieConnectionRequest(
        name = "migration-${System.nanoTime()}",
        baseUrl = "http://toadie-migration.internal/integration/graphql",
        browserUrl = "http://toadie-migration.internal",
        apiKey = "migration-secret-key",
        enabled = enabled,
        refreshIntervalMinutes = 60,
        adoptionMapping = ToadieAdoptionMapping(),
    )

    @Test
    fun `reader gets complete contract usage with explicit unknown adoption and retained missing links`() = testApplication {
        usePostgresTestcontainer()
        val ownerId = TestUsers.seed(uniqueEmail("migration-owner"), "pw", role = UserRole.ADMIN)
        val owner = authedClient(TestUsers.service.read(ownerId)!!.email, "pw")
        val reader = seededClient("migration-reader")
        val contract = owner.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(
                TestContracts.seedSystem("migration-report"),
                ContractType.OPENAPI,
                "migration-${System.nanoTime()}",
                ownerUserId = ownerId,
            ),
        ).body<ContractResponse>()
        val version = owner.postJson(
            "/api/v1/contracts/${contract.id}/versions",
            VersionCreateRequest("1.0.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        owner.postJson(
            "/api/v1/contracts/${contract.id}/versions/${version.id}/transition",
            TransitionRequest(Lifecycle.PROPOSED),
        )
        owner.postJson(
            "/api/v1/contracts/${contract.id}/versions/${version.id}/transition",
            TransitionRequest(Lifecycle.ACTIVE),
        )
        owner.putJson(
            "/api/v1/contracts/${contract.id}/release-lines/1",
            ReleaseLineUpdateRequest(
                supportStatus = SupportStatus.MAINTENANCE,
                deprecatesOn = "2030-06-01",
                supportEndsOn = "2030-12-31",
                supportPolicy = "Critical fixes",
                migrationGuide = "Move clients before support ends.",
            ),
        )

        val service = ToadieService(
            sharedDatabaseForTests(),
            FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )
        val connectionId = service.create(connectionRequest())
        val initial = snapshot(serviceCount = 101, includeSecondApi = true)
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, initial))
        assertTrue(
            service.replaceLinks(
                contract.id,
                ToadieLinksRequest(connectionId, listOf("1", "2")),
                CallerPrincipal(ownerId, "owner@test", setOf(UserRole.ADMIN)),
            ),
        )
        assertTrue(
            service.publish(
                service.claimRefresh(connectionId, false).second!!,
                snapshot(serviceCount = 101, includeSecondApi = false),
            ),
        )

        val path = "/api/v1/contracts/${contract.id}/release-lines/1/migration-report"
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(path).status)
        val response = reader.get(path)
        assertEquals(HttpStatusCode.OK, response.status)
        val raw = response.bodyAsText()
        val report = kotlinx.serialization.json.Json.decodeFromString<ReleaseLineMigrationReportResponse>(raw)
        assertEquals(contract.id, report.contractId)
        assertEquals(contract.name, report.contractName)
        assertEquals(ContractType.OPENAPI, report.contractType)
        assertEquals(SupportStatus.MAINTENANCE, report.supportStatus)
        assertEquals(MigrationUsageScope.CONTRACT, report.usageScope)
        assertEquals(MigrationVersionAdoption.UNKNOWN, report.versionAdoption)
        assertEquals(version.id, report.recommendedVersion?.id)
        assertEquals(ToadieCacheState.CURRENT, report.cache.state)
        assertEquals(101, report.services.size, "the singleton is not truncated to the paging maximum")
        assertEquals(setOf(ToadieUsageRole.PROVIDER, ToadieUsageRole.CONSUMER), report.services.first().roles.toSet())
        assertEquals(listOf("1"), report.services.first().providedApiEntityIds)
        assertEquals(listOf("1"), report.services.first().consumedApiEntityIds)
        assertEquals(
            setOf(ToadieLinkStatus.AVAILABLE, ToadieLinkStatus.MISSING),
            report.linkedApis.map { it.status }.toSet(),
        )
        assertEquals("API Two", report.linkedApis.single { it.apiEntityId == "2" }.title)
        assertFalse(raw.contains("migration-secret-key"))
        assertFalse(raw.contains("baseUrl"))
        assertEquals(2, report.adoptions.items.size)
        assertEquals(ToadieAdoptionAvailability.AVAILABLE, report.adoptions.availability)
        assertTrue(report.adoptions.items.all { !it.matchesConsumption })
        assertEquals(
            setOf(ToadieAdoptionEnvironmentScope.ALL, ToadieAdoptionEnvironmentScope.SPECIFIC),
            report.adoptions.items.map { it.environmentScope }.toSet(),
        )
        assertEquals("v2", report.adoptions.items.first().value, "the selected Covenant major does not reinterpret raw adoption")

        val paged = reader.get("/api/v1/contracts/${contract.id}/toadie-usage?pageSize=100")
            .body<ToadieUsageResponse>()
        assertEquals(101, paged.total)
        assertEquals(100, paged.items.size)
        val adoptionPage = reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions?pageSize=1")
            .body<ToadieAdoptionResponse>()
        assertEquals(2, adoptionPage.total)
        assertEquals(1, adoptionPage.items.size)
        suspendTransaction(sharedDatabaseForTests()) {
            assertFailsWith<ConflictException> {
                service.fullUsageInTransaction(contract.id, maxProjectedBytes = 1)
            }
        }
        val planRead = Channel<Unit>(1)
        val continueRead = Channel<Unit>(1)
        val pauseOnce = AtomicBoolean(true)
        val snapshotReader = ReleaseLineService(
            sharedDatabaseForTests(),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        ) {
            if (pauseOnce.getAndSet(false)) {
                planRead.send(Unit)
                continueRead.receive()
            }
        }
        withTimeout(10_000) {
            coroutineScope {
                val pending = async { snapshotReader.migrationReport(contract.id, 1, service)!! }
                try {
                    planRead.receive()
                    assertTrue(
                        service.publish(
                            service.claimRefresh(connectionId, false).second!!,
                            snapshot(serviceCount = 2, includeSecondApi = false),
                        ),
                    )
                    continueRead.send(Unit)
                    assertEquals(101, pending.await().services.size, "the report stays on its original database snapshot")
                    assertEquals(2, snapshotReader.migrationReport(contract.id, 1, service)!!.services.size)
                } finally {
                    continueRead.trySend(Unit)
                    pending.cancelAndJoin()
                }
            }
        }
        assertTrue(
            service.publish(
                service.claimRefresh(connectionId, false).second!!,
                combinedBudgetSnapshot(),
            ),
        )
        assertEquals(HttpStatusCode.Conflict, reader.get(path).status)
        val amplifiedPage = reader.get("/api/v1/contracts/${contract.id}/toadie-usage?pageSize=100")
            .body<ToadieUsageResponse>()
        assertEquals(1000, amplifiedPage.total)
        assertEquals(100, amplifiedPage.items.size)
        assertEquals(
            800,
            reader.get("/api/v1/contracts/${contract.id}/toadie-adoptions?pageSize=100")
                .body<ToadieAdoptionResponse>().total,
        )
        assertEquals(HttpStatusCode.NotFound, reader.get("/api/v1/contracts/${contract.id}/release-lines/9/migration-report").status)
        assertEquals(HttpStatusCode.BadRequest, reader.get("/api/v1/contracts/${contract.id}/release-lines/-1/migration-report").status)
        assertEquals(HttpStatusCode.NotFound, reader.get("/api/v1/contracts/2147483647/release-lines/1/migration-report").status)
    }

    @Test
    fun `report covers major zero and cache warning states without mutating retained observations`() = testApplication {
        usePostgresTestcontainer()
        val ownerId = TestUsers.seed(uniqueEmail("migration-states"), "pw", role = UserRole.ADMIN)
        val owner = authedClient(TestUsers.service.read(ownerId)!!.email, "pw")
        suspend fun contract(name: String): ContractResponse = owner.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(
                TestContracts.seedSystem(name), ContractType.ASYNCAPI, "$name-${System.nanoTime()}", ownerUserId = ownerId,
            ),
        ).body()
        val linked = contract("migration-linked")
        owner.postJson(
            "/api/v1/contracts/${linked.id}/versions",
            VersionCreateRequest("0.1.0", ContractFixtures.asyncApi3),
        )
        val unlinked = contract("migration-unlinked")
        owner.postJson(
            "/api/v1/contracts/${unlinked.id}/versions",
            VersionCreateRequest("0.2.0", ContractFixtures.asyncApi3),
        )
        val service = ToadieService(
            sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )
        val connectionId = service.create(connectionRequest())
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, snapshot(1, true)))
        service.replaceLinks(
            linked.id,
            ToadieLinksRequest(connectionId, listOf("1")),
            CallerPrincipal(ownerId, "owner@test", setOf(UserRole.ADMIN)),
        )
        suspend fun report() = owner.get(
            "/api/v1/contracts/${linked.id}/release-lines/0/migration-report",
        ).body<ReleaseLineMigrationReportResponse>()

        assertEquals(ToadieCacheState.CURRENT, report().cache.state)
        val failed = service.claimRefresh(connectionId, false).second!!
        service.fail(failed, "upstream failed")
        assertEquals(ToadieCacheState.STALE, report().cache.state)
        assertEquals(1, report().services.size)
        service.update(connectionId, connectionRequest(enabled = false).copy(apiKey = null))
        assertEquals(ToadieCacheState.DISABLED, report().cache.state)
        assertEquals(1, report().services.size)
        assertEquals(1, report().linkedApis.size)
        service.delete(connectionId)
        val disconnected = report()
        assertEquals(ToadieCacheState.DISCONNECTED, disconnected.cache.state)
        assertEquals(ToadieLinkStatus.DISCONNECTED, disconnected.linkedApis.single().status)
        assertTrue(disconnected.services.isEmpty())
        assertEquals(
            ToadieCacheState.UNLINKED,
            owner.get("/api/v1/contracts/${unlinked.id}/release-lines/0/migration-report")
                .body<ReleaseLineMigrationReportResponse>().cache.state,
        )
        assertEquals(HttpStatusCode.NoContent, owner.delete("/api/v1/contracts/${linked.id}").status)
        assertEquals(
            HttpStatusCode.NotFound,
            owner.get("/api/v1/contracts/${linked.id}/release-lines/0/migration-report").status,
        )
    }

    private fun snapshot(serviceCount: Int, includeSecondApi: Boolean): ToadieSnapshot {
        val apis = buildList {
            add(ToadieEntitySnapshot("1", "api", "api-one", "API One", emptyList(), emptyMap(), 1))
            if (includeSecondApi) add(ToadieEntitySnapshot("2", "api", "api-two", "API Two", emptyList(), emptyMap(), 1))
        }
        val services = (1..serviceCount).map { index ->
            ToadieEntitySnapshot(
                "${100 + index}", "service", "service-$index", "Service $index", emptyList(),
                mapOf(
                    "provides_apis" to listOf("api-one", "api-two"),
                    "consumes_apis" to listOf("api-one", "api-two"),
                ),
                1,
            )
        }
        val environment = ToadieEntitySnapshot(
            "9000", "environment", "production", "Production", emptyList(), emptyMap(), 1,
        )
        val unmatched = ToadieEntitySnapshot(
            "9001", "service", "declared-only", "Declared only", emptyList(),
            mapOf("consumes_apis" to emptyList()), 1,
        )
        val adoptions = listOf(
            ToadieEntitySnapshot(
                "9002", "api_adoption", "declared-production", "Declared production", emptyList(),
                mapOf("consumer" to listOf("declared-only"), "api" to listOf("api-one"),
                    "environment" to listOf("production")), 1,
                scalarProperties = mapOf("major_line" to "v2"),
            ),
            ToadieEntitySnapshot(
                "9003", "api_adoption", "declared-all", "Declared all", emptyList(),
                mapOf("consumer" to listOf("declared-only"), "api" to listOf("api-one"),
                    "environment" to emptyList()), 1,
                scalarProperties = mapOf("major_line" to "v2"),
            ),
        )
        return ToadieSnapshot(
            apis + services + environment + unmatched + adoptions, "system", System.currentTimeMillis(), 1,
            ToadieAdoptionAvailability.AVAILABLE, "environment",
        )
    }

    private fun combinedBudgetSnapshot(): ToadieSnapshot {
        val teams = (1..50).map { index ->
            ToadieEntitySnapshot("${3000 + index}", "_team", "team-$index", "Team $index", emptyList(), emptyMap(), 1)
        }
        val teamIdentifiers = teams.map { it.identifier }
        val services = (1..1000).map { index ->
            ToadieEntitySnapshot(
                "${4000 + index}", "service", "amplified-$index", "Amplified $index", teamIdentifiers,
                mapOf("provides_apis" to listOf("api-one")), 1,
            )
        }
        val api = ToadieEntitySnapshot("1", "api", "api-one", "API One", emptyList(), emptyMap(), 1)
        val scalar = "x".repeat(2000)
        val adoptions = (1..800).map { index ->
            ToadieEntitySnapshot(
                "${7000 + index}", "api_adoption", "large-$index", "Large $index", emptyList(),
                mapOf("consumer" to listOf("amplified-1"), "api" to listOf("api-one"),
                    "environment" to emptyList()), 1,
                scalarProperties = mapOf(
                    "major_line" to scalar, "status" to scalar, "declared_by" to scalar, "notes" to scalar,
                    "verified_at" to null,
                ),
            )
        }
        return ToadieSnapshot(
            listOf(api) + teams + services + adoptions, "system", System.currentTimeMillis(), 1,
            ToadieAdoptionAvailability.AVAILABLE, "environment",
        )
    }
}
