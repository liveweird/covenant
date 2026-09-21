package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.*
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class LifecycleOverviewTest {
    private val clock = Clock.fixed(Instant.parse("2030-06-01T00:00:00Z"), ZoneOffset.UTC)
    private val paging = PageRequest(1, 100, listOf(SortField("nextDeadline", false), SortField("id", false)))

    private suspend fun contract(client: HttpClient, prefix: String, ownerUser: UInt? = null): ContractResponse =
        client.postJson("/api/v1/contracts", ContractCreateRequest(
            TestContracts.seedSystem(prefix), ContractType.OPENAPI, "$prefix-${System.nanoTime()}",
            ownerTeamId = if (ownerUser == null) TestTeams.seed("$prefix-team-${System.nanoTime()}") else null,
            ownerUserId = ownerUser,
        )).body()

    private suspend fun version(client: HttpClient, id: UInt, major: Int): VersionResponse = client.postJson(
        "/api/v1/contracts/$id/versions", VersionCreateRequest("$major.0.0", ContractFixtures.openApi),
    ).body()

    private suspend fun policy(client: HttpClient, id: UInt, major: Int, body: ReleaseLineUpdateRequest) {
        assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/contracts/$id/release-lines/$major") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }.status)
    }

    @Test
    fun `UTC windows line counts paging empty lines and migration flags agree`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("overview-dates", UserRole.ADMIN)
        val source = contract(admin, "overview-dates")
        for (major in 0..6) version(admin, source.id, major)
        val empty = version(admin, source.id, 7)
        admin.delete("/api/v1/contracts/${source.id}/versions/${empty.id}")
        policy(admin, source.id, 0, ReleaseLineUpdateRequest(
            SupportStatus.SUPPORTED, supportEndsOn = "2030-06-01", deprecatesOn = "2030-05-31",
        ))
        policy(admin, source.id, 1, ReleaseLineUpdateRequest(
            SupportStatus.SUPPORTED, supportEndsOn = "2030-07-01", deprecatesOn = "2030-06-02",
        ))
        policy(admin, source.id, 2, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, supportEndsOn = "2030-07-02"))
        policy(admin, source.id, 3, ReleaseLineUpdateRequest(SupportStatus.END_OF_LIFE, supportEndsOn = "2030-05-01"))
        policy(admin, source.id, 4, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, supportEndsOn = "2030-06-30",
            migrationGuide = "Move to 6.x", replacementContractId = source.id, replacementMajor = 6))
        policy(admin, source.id, 6, ReleaseLineUpdateRequest(SupportStatus.UNSPECIFIED, deprecatesOn = "2030-06-01"))
        val service = LifecycleOverviewService(sharedDatabaseForTests(), TestTeams.service, clock)
        val scope = LifecycleOverviewFilter(ContractListFilter(q = source.name))
        val readerId = TestUsers.seed(uniqueEmail("overview-reader"), "pw", role = UserRole.USER)
        val caller = CallerPrincipal(readerId, "reader@test", emptySet())
        val all = service.list(scope, paging, caller)
        assertEquals(listOf(0, 6, 1, 4, 2, 3, 5), all.items.map { it.major })
        assertTrue(all.items.all { !it.contract.canWrite })
        assertTrue(all.items.all { it.usageUncertain && it.usage.consumerCount == null })
        assertFalse(all.items.single { it.major == 5 }.migrationIncomplete)
        assertFalse(all.items.single { it.major == 4 }.migrationIncomplete)
        assertFalse(all.items.single { it.major == 3 }.supportEnded)
        val summary = service.summary(scope)
        assertEquals("2030-06-01", summary.asOfDate)
        assertEquals(7, summary.total)
        assertEquals(2, summary.deadlineSoon)
        assertEquals(1, summary.supportEnded)
        assertEquals(4, summary.migrationIncomplete)
        for (attention in LifecycleAttention.entries) {
            val narrowed = service.list(scope.copy(attention = attention), paging, caller)
            val count = when (attention) {
                LifecycleAttention.DEADLINE_SOON -> summary.deadlineSoon
                LifecycleAttention.SUPPORT_ENDED -> summary.supportEnded
                LifecycleAttention.MIGRATION_INCOMPLETE -> summary.migrationIncomplete
                LifecycleAttention.USAGE_UNCERTAIN -> summary.usageUncertain
            }
            assertEquals(count, narrowed.total)
            assertEquals(summary, service.summary(scope.copy(attention = attention)))
        }
        assertEquals(listOf(0, 6), service.list(scope.copy(deadline = LifecycleDeadline.REACHED), paging, caller).items.map { it.major })
        assertEquals(listOf(5), service.list(scope.copy(deadline = LifecycleDeadline.NONE), paging, caller).items.map { it.major })
        assertEquals(listOf(1, 4),
            service.list(scope.copy(deadline = LifecycleDeadline.NEXT_30_DAYS), paging, caller).items.map { it.major })
        val second = service.list(scope, paging.copy(page = 2, pageSize = 2), caller)
        assertEquals(listOf(1, 4), second.items.map { it.major })
        assertEquals(7, second.total)
        assertTrue(service.list(scope, paging.copy(page = Int.MAX_VALUE), caller).items.isEmpty())
        assertEquals(listOf(3),
            service.list(scope.copy(supportStatuses = listOf(SupportStatus.END_OF_LIFE)), paging, caller).items.map { it.major })
    }

    @Test
    fun `authenticated overview exposes scoped owners without user registry permissions and validates queries`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("overview-http", UserRole.ADMIN)
        val userId = TestUsers.seed(uniqueEmail("overview-owner"), "pw", role = UserRole.USER)
        val source = contract(admin, "Żółw-overview", userId)
        version(admin, source.id, 1)
        val reader = seededClient("overview-http-reader", UserRole.USER)
        val path = "/api/v1/contracts/lifecycle-overview"
        val filter = "systemId=${source.system.id}"
        val rows = reader.get("$path?$filter&q=zolw").body<LifecycleOverviewPage>()
        assertEquals(source.id, rows.items.single().contract.id)
        assertFalse(rows.items.single().contract.canWrite)
        assertTrue(admin.get("$path?$filter").body<LifecycleOverviewPage>().items.single().contract.canWrite)
        val summary = reader.get("$path/summary?$filter&ownerTeamId=0&attention=DEADLINE_SOON").body<LifecycleOverviewSummary>()
        assertEquals(0, summary.total)
        assertEquals(userId, summary.ownerUser.single().id)
        assertEquals(1, reader.get("$path?$filter&ownerUserId=$userId&type=OPENAPI&type=ODCS").body<LifecycleOverviewPage>().total)
        assertEquals(0, reader.get("$path?$filter&type=ODCS").body<LifecycleOverviewPage>().total)
        for (query in listOf("page=0", "pageSize=101", "sort=version", "deadline=nope", "attention=nope", "supportStatus=nope",
            "ownerUserId=1&ownerUserId=2", "deadline=NONE&deadline=REACHED")) {
            assertEquals(HttpStatusCode.BadRequest, reader.get("$path?$query").status, query)
        }
        assertEquals(HttpStatusCode.BadRequest, reader.get("$path/summary?attention=bad").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("$path/summary").status)
        admin.delete("/api/v1/contracts/${source.id}")
        assertEquals(0, reader.get("$path?$filter").body<LifecycleOverviewPage>().total)
    }

    @Test
    fun `unavailable replacements retain names and make guidance incomplete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("overview-replacements", UserRole.ADMIN)
        val source = contract(admin, "overview-source")
        val target = contract(admin, "overview-target")
        version(admin, source.id, 1)
        val targetVersion = version(admin, target.id, 2)
        admin.delete("/api/v1/contracts/${target.id}/versions/${targetVersion.id}")
        policy(admin, source.id, 1, ReleaseLineUpdateRequest(SupportStatus.MAINTENANCE,
            supportEndsOn = "2030-06-15", replacementContractId = target.id, replacementMajor = 2, migrationGuide = "Move there"))
        val service = LifecycleOverviewService(sharedDatabaseForTests(), TestTeams.service, clock)
        val caller = CallerPrincipal(TestUsers.seed(uniqueEmail("replacement-reader"), "pw"), "reader@test", emptySet())
        val filter = LifecycleOverviewFilter(ContractListFilter(q = source.name))
        val current = service.list(filter, paging, caller).items.single()
        assertTrue(current.replacement!!.available, "empty retained target line is still a valid replacement")
        assertFalse(current.migrationIncomplete)
        admin.delete("/api/v1/contracts/${target.id}")
        val missing = service.list(filter, paging, caller).items.single()
        assertFalse(missing.replacement!!.available)
        assertEquals(target.name, missing.replacement.contractName)
        assertTrue(missing.migrationIncomplete)
        assertEquals(1, service.summary(filter).migrationIncomplete)
    }

    @Test
    fun `cached usage deduplicates services and distinguishes current zero from uncertain observations`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("overview-cache", UserRole.ADMIN)
        val owner = TestUsers.seed(uniqueEmail("overview-cache-owner"), "pw", role = UserRole.USER)
        val caller = CallerPrincipal(owner, "owner@test", emptySet())
        val source = contract(admin, "overview-cache", owner)
        version(admin, source.id, 1)
        version(admin, source.id, 2)
        val contracts = ContractService(sharedDatabaseForTests(), TestTeams.service)
        val toadie = ToadieService(sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY), contracts)
        val request = ToadieConnectionRequest(
            "overview-${System.nanoTime()}", "http://overview.invalid", "http://overview.invalid", "fixture-only", true, 60,
        )
        val connection = toadie.create(request)
        try {
            val apis = listOf("a", "b").mapIndexed { i, id -> ToadieEntitySnapshot("${i + 1}", "api", id, id, emptyList(), emptyMap(), 1) }
            val consumer = ToadieEntitySnapshot("3", "service", "shop", "Shop", emptyList(), mapOf("consumes_apis" to listOf("a", "b")), 1)
            suspend fun publish(entities: List<ToadieEntitySnapshot>, time: Long = clock.millis()) {
                assertTrue(toadie.publish(toadie.claimRefresh(connection, false).second!!, ToadieSnapshot(entities, "system", time, 1)))
            }
            publish(apis + consumer)
            toadie.replaceLinks(source.id, ToadieLinksRequest(connection, listOf("1", "2")), caller)
            val service = LifecycleOverviewService(sharedDatabaseForTests(), TestTeams.service, clock)
            val filter = LifecycleOverviewFilter(ContractListFilter(q = source.name))
            suspend fun rows() = service.list(filter, paging, caller).items
            assertTrue(rows().all { it.contract.canWrite && it.usage.consumerCount == 1L && !it.usageUncertain })
            assertEquals(0, service.summary(filter).usageUncertain)
            publish(apis)
            assertTrue(rows().all { it.usage.consumerCount == 0L && !it.usageUncertain })
            publish(apis + consumer, clock.millis() - 7_200_000)
            assertFalse(rows().first().usageUncertain, "exact freshness boundary stays current")
            publish(apis + consumer, clock.millis() - 7_200_001)
            assertTrue(rows().all { it.usage.consumerCount == 1L && it.usage.cache.state == ToadieCacheState.STALE && it.usageUncertain })
            assertEquals(2, service.summary(filter).usageUncertain)
            publish(apis.take(1) + consumer)
            assertTrue(rows().all { it.usage.consumerCount == null && it.usage.unavailableLinkCount == 1 && it.usageUncertain })
            publish(apis + consumer)
            toadie.update(connection, request.copy(apiKey = null, enabled = false))
            assertTrue(rows().all {
                it.usage.consumerCount == 1L && it.usage.cache.state == ToadieCacheState.DISABLED && it.usageUncertain
            })
            toadie.delete(connection)
            assertTrue(rows().all {
                it.usage.consumerCount == null && it.usage.cache.state == ToadieCacheState.DISCONNECTED &&
                    it.usage.unavailableLinkCount == 2 && it.usageUncertain
            })
        } finally {
            toadie.delete(connection)
        }
    }
}
