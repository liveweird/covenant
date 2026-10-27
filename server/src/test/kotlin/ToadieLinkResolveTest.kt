package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/toadie-links` — the REVERSE link read behind the deep link Toadie renders on its own
 * `api`/`dataset` entity pages (the URL contract is pinned in `.claude/docs/toadie-integration.md`).
 * The forward reads are covered by [ToadieRoutesTest]; what is pinned here is the lookup itself:
 * the blueprint filter, byte-exact identifier matching, the live-vs-stored identifier preference,
 * soft-delete exclusion, the multi-connection case, and the key validation.
 *
 * Every test mints its OWN entity identifier: the suite shares one Postgres container and this read
 * is keyed by (blueprint, identifier) alone — nothing scopes it to a system or a contract — so a
 * shared literal would have each test resolving its neighbours' rows.
 */
class ToadieLinkResolveTest {

    /**
     * Every connection this suite creates, soft-deleted after each test. [MAX_TOADIE_CONNECTIONS]
     * caps ACTIVE connections GLOBALLY and the whole suite shares one Postgres container, so
     * leaking them here makes `ToadieRoutesTest`/`ToadieUsageTest`/`ToadieRegistryTest` fail with
     * "At most 10 active Toadie connections are allowed" — a failure that points nowhere near here.
     */
    private val createdConnections = mutableListOf<UInt>()

    @AfterTest
    fun releaseConnections() = runBlocking {
        val service = ToadieService(
            sharedDatabaseForTests(),
            FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
            ContractService(sharedDatabaseForTests(), TestTeams.service),
        )
        createdConnections.forEach { runCatching { service.delete(it) } }
        createdConnections.clear()
    }

    @Test
    fun `the reverse read resolves an entity to every contract that covers it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("resolve-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("resolve-owner"), "pw")
        val caller = CallerPrincipal(ownerId, "resolve-owner@test", emptySet())
        val service = directService()
        val systemId = TestContracts.seedSystem("resolve")
        val entity = uniqueIdentifier("orders")

        // Two connections over the SAME `api` blueprint — Covenant can talk to more than one Toadie,
        // and both may link the same identifier; the answer must name which one each match came through.
        val first = service.seedConnection("resolve-first", entity)
        val second = service.seedConnection("resolve-second", entity)
        // Two contracts through the first connection, one through the second: three matches.
        val covering = service.linkContract(systemId, ownerId, caller, first, "a")
        val alsoCovering = service.linkContract(systemId, ownerId, caller, first, "b")
        val throughSecond = service.linkContract(systemId, ownerId, caller, second, "c")
        // A contract linked to a DIFFERENT entity of the same connection must never surface.
        service.linkContract(systemId, ownerId, caller, first, "d", entityId = DECOY_ENTITY_ID)

        val matches = admin.resolve("api", entity)
        assertEquals(3, matches.total)
        assertEquals(
            listOf(covering, alsoCovering, throughSecond).sorted(),
            matches.items.map { it.contractId }.sorted(),
        )
        // Each match carries the connection it came through, so the page can tell two Toadies apart.
        assertEquals(first, matches.items.first { it.contractId == covering }.connection.id)
        assertEquals(second, matches.items.first { it.contractId == throughSecond }.connection.id)
        assertEquals(
            checkNotNull(service.read(second)).name,
            matches.items.first { it.contractId == throughSecond }.connection.name,
        )
        val throughFirst = matches.items.first { it.contractId == covering }
        assertEquals("api", throughFirst.blueprint)
        assertEquals(entity, throughFirst.identifier)
        assertEquals("Orders", throughFirst.title)
        assertEquals(ContractType.OPENAPI, throughFirst.type)
        assertEquals(systemId, throughFirst.system.id)
        // The seeded contracts carry no version yet, so the catalog's latest-version pointer is null.
        assertNull(throughFirst.latestVersion)

        // The blueprint filter: these are `api` links, so a `dataset` lookup for the same identifier
        // must find nothing — the ODCS preset's entities live under their own connection.
        assertEquals(0, admin.resolve("dataset", entity).total)
        // Byte-exact: Toadie resolves relation targets byte-exactly, so a case-differing key misses.
        assertEquals(0, admin.resolve("api", entity.uppercase()).total)
        assertEquals(0, admin.resolve("api", entity.dropLast(1)).total)
        // The blueprint itself is compared case-insensitively, as every other snapshot read does.
        assertEquals(3, admin.resolve("API", entity).total)
        // Nothing linked is an empty page, never a 404 — the page says "not linked yet".
        assertEquals(0, admin.resolve("api", uniqueIdentifier("never-linked")).total)

        // A soft-deleted contract drops out; the others stay.
        ContractService(sharedDatabaseForTests(), TestTeams.service).delete(alsoCovering, caller)
        val afterDelete = admin.resolve("api", entity)
        assertEquals(2, afterDelete.total)
        assertTrue(afterDelete.items.none { it.contractId == alsoCovering })

        // A soft-deleted CONNECTION drops its links too.
        service.delete(second)
        val afterConnectionDelete = admin.resolve("api", entity)
        assertEquals(1, afterConnectionDelete.total)
        assertEquals(covering, afterConnectionDelete.items.single().contractId)
    }

    @Test
    fun `a renamed entity resolves under its new identifier and a vanished one under the stored copy`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("resolve-rename-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("resolve-rename-owner"), "pw")
        val caller = CallerPrincipal(ownerId, "resolve-rename-owner@test", emptySet())
        val service = directService()
        val systemId = TestContracts.seedSystem("resolve-rename")
        val original = uniqueIdentifier("orders")
        val renamedTo = uniqueIdentifier("orders-v2")
        val connection = service.seedConnection("resolve-rename", original)
        val contractId = service.linkContract(systemId, ownerId, caller, connection, "renamed")

        // Upstream renames the entity — same Port id, new identifier. The link row still carries the
        // OLD copy, so only the live snapshot can answer the URL Toadie now generates.
        service.publishSnapshot(connection, renamedTo, "Orders v2")
        val renamed = admin.resolve("api", renamedTo)
        assertEquals(1, renamed.total)
        assertEquals(contractId, renamed.items.single().contractId)
        assertEquals("Orders v2", renamed.items.single().title)
        // The old identifier is gone from the live snapshot, so it no longer resolves.
        assertEquals(0, admin.resolve("api", original).total)

        // The entity disappears from Toadie altogether: with no live row the lookup falls back to the
        // identifier stored when the link was made, so the contract stays reachable under the old name.
        service.publishSnapshot(connection, identifier = null, title = null)
        val vanished = admin.resolve("api", original)
        assertEquals(1, vanished.total)
        assertEquals(contractId, vanished.items.single().contractId)
        assertEquals("Orders", vanished.items.single().title)
        assertEquals(0, admin.resolve("api", renamedTo).total)
    }

    @Test
    fun `a match carries the contract's highest version, survives a disabled connection and pages`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("resolve-version-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("resolve-version-owner"), "pw")
        val caller = CallerPrincipal(ownerId, "resolve-version-owner@test", emptySet())
        val service = directService()
        val contracts = ContractService(sharedDatabaseForTests(), TestTeams.service)
        val systemId = TestContracts.seedSystem("resolve-version")
        val entity = uniqueIdentifier("orders")
        val connection = service.seedConnection("resolve-version", entity)

        // Two contracts so the page can be split, the first carrying a published version.
        val first = service.linkContract(systemId, ownerId, caller, connection, "aaa")
        val second = service.linkContract(systemId, ownerId, caller, connection, "zzz")
        // Through the real API, so the summary is compared against the canonical version read
        // rather than against hand-written expectations (ADMIN is always a writer).
        val created: VersionResponse = admin.postJson(
            "/api/v1/contracts/$first/versions",
            VersionCreateRequest("1.0.0", ContractFixtures.openApi),
        ).body()

        val all = admin.resolve("api", entity)
        assertEquals(2, all.total)
        val withVersion = checkNotNull(all.items.first { it.contractId == first }.latestVersion)
        assertEquals(created.id, withVersion.id)
        assertEquals(created.version, withVersion.version)
        assertEquals(created.lifecycle, withVersion.lifecycle)
        assertEquals(created.checkErrors, withVersion.checkErrors)
        assertEquals(created.checkWarnings, withVersion.checkWarnings)
        assertEquals(created.checkComplete, withVersion.checkComplete)
        assertNull(all.items.first { it.contractId == second }.latestVersion)

        // Paging: `total` is the filtered count, not the page size, and the pages partition the set.
        val firstPage = admin.resolve("api", entity, "&page=1&pageSize=1")
        val secondPage = admin.resolve("api", entity, "&page=2&pageSize=1")
        assertEquals(2, firstPage.total)
        assertEquals(2, secondPage.total)
        assertEquals(1, firstPage.items.size)
        assertEquals(
            listOf(first, second).sorted(),
            (firstPage.items + secondPage.items).map { it.contractId }.sorted(),
        )
        // Sorting by the whitelisted contract name, descending.
        val byName = admin.resolve("api", entity, "&sort=-contractName")
        assertEquals(listOf(second, first), byName.items.map { it.contractId })

        // A DISABLED connection still resolves: its links remain the right answer, only its cache
        // has stopped moving. (A soft-DELETED one does not — pinned in the first test.)
        setConnectionEnabled(connection, enabled = false)
        assertEquals(2, admin.resolve("api", entity).total)

        contracts.delete(first, caller)
        contracts.delete(second, caller)
    }

    @Test
    fun `a never-scanned connection resolves through the stored copy and yields to a live rename`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("resolve-fallback-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("resolve-fallback-owner"), "pw")
        val caller = CallerPrincipal(ownerId, "resolve-fallback-owner@test", emptySet())
        val service = directService()
        val systemId = TestContracts.seedSystem("resolve-fallback")
        val entity = uniqueIdentifier("orders")
        val connection = service.seedConnection("resolve-fallback", entity)
        val stale = service.linkContract(systemId, ownerId, caller, connection, "stale")

        // Wipe the snapshot entirely — the "never scanned" shape the doc comment names. The link
        // row's stored copy is then the only identifier there is, and it must still resolve.
        wipeSnapshot(connection)
        assertEquals(1, admin.resolve("api", entity).total)
        assertEquals(stale, admin.resolve("api", entity).items.single().contractId)

        // Now the identifier is taken over by a LIVE entity under a new Port id, which a second
        // contract links. The live row is authoritative, so the dead stored-copy row must drop out
        // rather than padding the chooser with a link that can never be followed anywhere useful.
        service.publishSnapshot(connection, entity, "Orders reborn", entityId = "api-9")
        val reborn = service.linkContract(systemId, ownerId, caller, connection, "reborn", entityId = "api-9")
        val matches = admin.resolve("api", entity)
        assertEquals(1, matches.total)
        assertEquals(reborn, matches.items.single().contractId)
        assertEquals("Orders reborn", matches.items.single().title)
    }

    @Test
    fun `the reverse read validates its lookup keys and requires authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("resolve-validate")

        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/toadie-links?blueprint=api&identifier=x").status)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=api").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?identifier=orders").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=&identifier=orders").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=api&identifier=%20").status)
        // Over the stored column width — a 400, not a silently empty page.
        val tooLong = "a".repeat(MAX_TOADIE_IDENTIFIER_LENGTH + 1)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=api&identifier=$tooLong").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/toadie-links?blueprint=${"b".repeat(MAX_TOADIE_NAME_LENGTH + 1)}&identifier=x").status,
        )
        // Repetition is reserved for documented IN semantics, which these keys do not have.
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=api&blueprint=dataset&identifier=x").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/toadie-links?blueprint=api&identifier=x&sort=title").status)

        // At the stored width exactly, and with every character class a Port identifier may carry
        // (`/` and `+` arrive percent-encoded and must survive query decoding intact).
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/toadie-links?blueprint=api&identifier=${"a".repeat(MAX_TOADIE_IDENTIFIER_LENGTH)}").status,
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/toadie-links?blueprint=api&identifier=a%2Fb%2Bc%3Ad%40e%3Df.g_h-i%27j").status)
    }

    @Test
    fun `an identifier carrying slashes and plus signs matches the stored value byte for byte`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("resolve-escapes-admin", UserRole.ADMIN)
        val ownerId = TestUsers.seed(uniqueEmail("resolve-escapes-owner"), "pw")
        val caller = CallerPrincipal(ownerId, "resolve-escapes-owner@test", emptySet())
        val service = directService()
        val systemId = TestContracts.seedSystem("resolve-escapes")
        // Port's identifier pattern allows `/ + : @ = . _ - '` and unicode letters.
        val awkward = "team/orders+v2:eu@prod=1.0_x-y'z-${uniqueIdentifier("suffix")}"
        val connection = service.seedConnection("resolve-escapes", awkward)
        val contractId = service.linkContract(systemId, ownerId, caller, connection, "awkward")

        val matches = admin.resolve("api", awkward)
        assertEquals(1, matches.total)
        assertEquals(contractId, matches.items.single().contractId)
        assertEquals(awkward, matches.items.single().identifier)
        // A `+` read as a space would resolve the wrong entity — pin that it does not.
        assertEquals(0, admin.resolve("api", awkward.replace("+", " ")).total)
        // So would an encoded `/` collapsing into a path separator.
        assertEquals(0, admin.resolve("api", awkward.substringAfter("/")).total)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun ApplicationTestBuilder.directService() = ToadieService(
        sharedDatabaseForTests(),
        FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
        ContractService(sharedDatabaseForTests(), TestTeams.service),
    )

    private fun uniqueIdentifier(prefix: String) =
        "$prefix-${java.util.UUID.randomUUID().toString().substring(0, 12)}"

    private suspend fun HttpClient.resolve(blueprint: String, identifier: String, extra: String = ""): PageResponse<ToadieLinkMatch> {
        val response = get("/api/v1/toadie-links?blueprint=${encode(blueprint)}&identifier=${encode(identifier)}$extra")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    /** The browser's own encoding of one query value — `+` must travel as `%2B`, never as a literal. */
    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** An enabled connection on the `api` blueprint whose snapshot holds [identifier] plus a decoy. */
    private suspend fun ToadieService.seedConnection(name: String, identifier: String): UInt {
        val unique = "$name-${System.nanoTime()}"
        val id = create(
            ToadieConnectionRequest(
                name = unique,
                baseUrl = "http://$unique.internal/integration/graphql",
                browserUrl = "http://$name.internal",
                apiKey = "secret-$name",
                enabled = true,
                refreshIntervalMinutes = 60,
            ),
        )
        createdConnections += id
        publishSnapshot(id, identifier, "Orders")
        return id
    }

    /**
     * Replaces the connection's snapshot. A null [identifier] publishes a snapshot WITHOUT the linked
     * entity, which is how an upstream deletion reaches Covenant (the link row survives it).
     */
    private suspend fun ToadieService.publishSnapshot(
        connectionId: UInt,
        identifier: String?,
        title: String?,
        entityId: String = LINKED_ENTITY_ID,
    ) {
        awaitIdle(connectionId)
        val claim = checkNotNull(claimRefresh(connectionId, force = false).second) {
            "The connection should have been claimable for a snapshot publish"
        }
        val linked = identifier?.let {
            listOf(ToadieEntitySnapshot(entityId, "api", it, checkNotNull(title), emptyList(), emptyMap(), 1))
        }.orEmpty()
        val decoy = ToadieEntitySnapshot(DECOY_ENTITY_ID, "api", "events-$connectionId", "Order events", emptyList(), emptyMap(), 1)
        assertTrue(
            publish(
                claim,
                ToadieSnapshot(
                    entities = linked + decoy,
                    systemBlueprint = null,
                    fetchedAt = System.currentTimeMillis(),
                    revision = 1,
                ),
            ),
        )
    }

    /** Disables a connection without going through `update`, which would also wipe the snapshot. */
    private suspend fun setConnectionEnabled(connectionId: UInt, enabled: Boolean) =
        suspendTransaction(sharedDatabaseForTests()) {
            ToadieService.Connections.update({ ToadieService.Connections.id eq connectionId }) {
                it[ToadieService.Connections.enabled] = enabled
            }
            Unit
        }

    /** The "never scanned" shape: a connection holding no snapshot rows at all. */
    private suspend fun wipeSnapshot(connectionId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
        ToadieService.SnapshotEntities.deleteWhere { ToadieService.SnapshotEntities.connectionId eq connectionId }
        Unit
    }

    private suspend fun ToadieService.awaitIdle(connectionId: UInt) {
        repeat(100) {
            if (!checkNotNull(read(connectionId)).refreshing) return
            delay(50)
        }
        error("Timed out waiting for the Toadie connection to go idle")
    }

    /** A fresh contract linked to one snapshot entity of [connectionId]. */
    private suspend fun ToadieService.linkContract(
        systemId: UInt,
        ownerId: UInt,
        caller: CallerPrincipal,
        connectionId: UInt,
        nameSuffix: String,
        entityId: String = LINKED_ENTITY_ID,
    ): UInt {
        val contracts = ContractService(sharedDatabaseForTests(), TestTeams.service)
        val contractId = contracts.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, "resolve-$nameSuffix-${System.nanoTime()}", ownerUserId = ownerId),
            caller,
        )
        assertTrue(replaceLinks(contractId, ToadieLinksRequest(connectionId, listOf(entityId)), caller))
        return contractId
    }

    private companion object {
        const val LINKED_ENTITY_ID = "api-1"
        const val DECOY_ENTITY_ID = "api-2"
    }
}
