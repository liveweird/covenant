package ch.nokillswit

import ch.nokillswit.contracts.BlockedUrlException
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ContractUpdateRequest
import ch.nokillswit.contracts.ContractUrlFetcher
import ch.nokillswit.contracts.ContractUrlFetcherKey
import ch.nokillswit.contracts.FETCH_URL_INVALID_DETAIL
import ch.nokillswit.contracts.FetchUrlRequest
import ch.nokillswit.contracts.FetchUrlResponse
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.OwnerUpdateRequest
import ch.nokillswit.contracts.SyncRequest
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionContentRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.tryit.TryKafkaPublishRequest
import ch.nokillswit.contracts.tryit.TryKafkaReadRequest
import ch.nokillswit.domains.DomainRequest
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.EnvironmentResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The statuses the feature tests never reach on their way through the happy paths — pinned here so
 * the OpenAPI coverage gate (OpenApiConformance.kt) stays green AND the rules stay true: a stranger's
 * 403 before any body work, the plain 404 for a foreign id, the 409 on a rename onto an active name,
 * and the fetch route's three outcomes through the fetcher seam.
 */
class RouteStatusMatrixTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `version family - a stranger's mutations are 403, a foreign contract or version id is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("matrix-admin", UserRole.ADMIN)
        val stranger = seededClient("matrix-stranger", UserRole.USER)
        val systemId = TestContracts.seedSystem("matrix")
        val teamId = TestTeams.seed(name("owners"))
        val c = admin.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("api"), null, ownerTeamId = teamId),
        ).body<ContractResponse>()
        val v = admin.postJson("/api/v1/contracts/${c.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi))
            .body<VersionResponse>()
        val vp = "/api/v1/contracts/${c.id}/versions/${v.id}"

        // 403 wins: the stranger's body is never decoded.
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson("$vp/content", VersionContentRequest(ContractFixtures.openApi)).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.postJson("$vp/transition", TransitionRequest(Lifecycle.PROPOSED)).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.post("$vp/recheck").status)
        assertEquals(HttpStatusCode.Forbidden, stranger.delete(vp).status)

        val ghost = "/api/v1/contracts/999999"
        assertEquals(HttpStatusCode.NotFound, admin.get("$ghost/versions").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.postJson("$ghost/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi)).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.get("$ghost/events").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("$ghost/owner", OwnerUpdateRequest(ownerTeamId = teamId)).status)
        val gv = "/api/v1/contracts/${c.id}/versions/999999"
        assertEquals(HttpStatusCode.NotFound, admin.get("$gv/content").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("$gv/content", VersionContentRequest(ContractFixtures.openApi)).status)
        assertEquals(HttpStatusCode.NotFound, admin.postJson("$gv/transition", TransitionRequest(Lifecycle.PROPOSED)).status)
        assertEquals(HttpStatusCode.NotFound, admin.post("$gv/recheck").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("$gv/sync").status)
        assertEquals(HttpStatusCode.NotFound, admin.postJson("$gv/sync", SyncRequest(ContractFixtures.openApi)).status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.postJson("$gv/try/kafka/publish", TryKafkaPublishRequest(environmentId = 1u, channel = "orders", payload = "{}")).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            admin.postJson("$gv/try/kafka/read", TryKafkaReadRequest(environmentId = 1u, channel = "orders")).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/notifications/999999999/unseen").status)
    }

    @Test
    fun `renaming onto an active name is 409 for domains, environments and contracts - an unknown environment is 404`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("matrix-rename", UserRole.ADMIN)
            val taken = name("Taken")
            TestDomains.seed(taken)
            val other = TestDomains.seed(name("Other"))
            assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/domains/$other", DomainRequest(taken.uppercase())).status)

            val systemId = TestContracts.seedSystem("matrix-rename")
            val env = { n: String -> EnvironmentRequest(systemId, n, httpBaseUrl = "http://gateway.internal:8080") }
            val first = admin.postJson("/api/v1/environments", env(name("staging"))).body<EnvironmentResponse>()
            val second = admin.postJson("/api/v1/environments", env(name("prod"))).body<EnvironmentResponse>()
            assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/environments/${second.id}", env(first.name)).status)
            assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/environments/999999", env(name("ghost"))).status)

            val teamId = TestTeams.seed(name("owners"))
            val c1 = admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.ODCS, name("ledger"), null, ownerTeamId = teamId),
            ).body<ContractResponse>()
            val c2 = admin.postJson(
                "/api/v1/contracts",
                ContractCreateRequest(systemId, ContractType.ODCS, name("orders"), null, ownerTeamId = teamId),
            ).body<ContractResponse>()
            assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/contracts/${c2.id}", ContractUpdateRequest(c1.name)).status)
        }

    @Test
    fun `fetch route - a reachable document is returned, a blocked URL is the uniform 400, an unreachable host 502`() =
        testApplication {
            configureApp()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/doc.yaml") { exchange ->
                val bytes = "openapi: 3.1.0\n".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/yaml")
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            // The seam: a lenient validator so a loopback fixture is a legal target, with one marker host
            // standing in for everything requirePublicHost refuses in production.
            val fetcher = ContractUrlFetcher(urlValidator = { raw ->
                if ("blocked.example" in raw) throw BlockedUrlException("https", "blocked.example") else URI(raw)
            })
            application { attributes.put(ContractUrlFetcherKey, fetcher) }
            startApplication()
            try {
                val admin = seededClient("matrix-fetch", UserRole.ADMIN)
                val ok = admin.postJson("/api/v1/contracts/fetch", FetchUrlRequest("http://127.0.0.1:${server.address.port}/doc.yaml"))
                assertEquals(HttpStatusCode.OK, ok.status)
                assertEquals("openapi: 3.1.0\n", ok.body<FetchUrlResponse>().content)
                val blocked = admin.postJson("/api/v1/contracts/fetch", FetchUrlRequest("https://blocked.example/doc.yaml"))
                assertEquals(HttpStatusCode.BadRequest, blocked.status)
                assertEquals(FETCH_URL_INVALID_DETAIL, blocked.body<ProblemDetail>().detail)
                val unreachable = admin.postJson("/api/v1/contracts/fetch", FetchUrlRequest("http://127.0.0.1:9/doc.yaml"))
                assertEquals(HttpStatusCode.BadGateway, unreachable.status)
            } finally {
                server.stop(0)
            }
        }
}
