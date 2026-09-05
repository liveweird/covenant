package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.systems.SystemPageResponse
import ch.nokillswit.systems.SystemRequest
import ch.nokillswit.systems.SystemResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The system registry (V8): CRUD inside a domain, the move, the per-domain name rule, the authz split. */
class SystemTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `unauthenticated requests are 401 and non-admins may only read`() = testApplication {
        usePostgresTestcontainer()
        val anon = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anon.get("/api/v1/systems").status)
        assertEquals(HttpStatusCode.Unauthorized, anon.post("/api/v1/systems").status)
        val user = seededClient("sysuser")
        val domain = TestDomains.seed(name("sysro"))
        val id = TestSystems.seed(domain, name("ro"))
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/systems").status)
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/systems/$id").status)
        assertEquals(HttpStatusCode.Forbidden, user.postJson("/api/v1/systems", SystemRequest(domain, name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, user.putJson("/api/v1/systems/999999", SystemRequest(domain, name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("/api/v1/systems/999999").status)
    }

    @Test
    fun `admin CRUD round-trips - the domain name is joined, the domainId filter narrows, a PUT may move`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("syscrud", UserRole.ADMIN)
        val domainName = name("Dom")
        val domainA = TestDomains.seed(domainName)
        val domainB = TestDomains.seed(name("DomB"))
        val n = name("gateway")

        val create = admin.postJson("/api/v1/systems", SystemRequest(domainId = domainA, name = " $n ", description = "edge"))
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<SystemResponse>()
        assertEquals(n, created.name)
        assertEquals(domainA, created.domainId)
        assertEquals(domainName, created.domainName)
        assertEquals(0, created.contractCount)

        val inA = admin.get("/api/v1/systems?domainId=$domainA").body<SystemPageResponse>()
        assertEquals(listOf(created.id), inA.items.map { it.id })
        assertEquals(0, admin.get("/api/v1/systems?domainId=$domainB").body<SystemPageResponse>().total)
        assertEquals(1, admin.get("/api/v1/systems?name=${n.uppercase()}&sort=-domainId,name").body<SystemPageResponse>().total)

        // Move to domain B by PUT.
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/systems/${created.id}", SystemRequest(domainB, n)).status)
        val moved = admin.get("/api/v1/systems/${created.id}").body<SystemResponse>()
        assertEquals(domainB, moved.domainId)
        assertEquals(null, moved.description)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/systems/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/systems/${created.id}").status)
    }

    @Test
    fun `rules - unknown domain 400, name unique per domain only, missing ids 404, bad params 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysrules", UserRole.ADMIN)
        val domainA = TestDomains.seed(name("A"))
        val domainB = TestDomains.seed(name("B"))
        val n = name("shared")
        val unknownDomain = admin.postJson("/api/v1/systems", SystemRequest(999999u, n))
        assertEquals(HttpStatusCode.BadRequest, unknownDomain.status)
        assertTrue(unknownDomain.body<ProblemDetail>().detail!!.contains("domain"))
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/systems", SystemRequest(domainA, "")).status)

        val first = admin.postJson("/api/v1/systems", SystemRequest(domainA, n)).body<SystemResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/systems", SystemRequest(domainA, n.uppercase())).status)
        // The same name in ANOTHER domain is fine.
        val other = admin.postJson("/api/v1/systems", SystemRequest(domainB, n))
        assertEquals(HttpStatusCode.Created, other.status)
        // Moving it onto the clash is a 409 too.
        val otherId = other.body<SystemResponse>().id
        assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/systems/$otherId", SystemRequest(domainA, n)).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/systems/${first.id}").status)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/systems", SystemRequest(domainA, n)).status)

        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/systems/999999", SystemRequest(domainA, name("nf"))).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/systems/999999").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/systems?domainId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/systems?sort=description").status)
    }

    @Test
    fun `mutations audit with the domain id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("sysaudit", UserRole.ADMIN)
        val domain = TestDomains.seed(name("aud"))
        withAuditCapture { capture ->
            val created = admin.postJson("/api/v1/systems", SystemRequest(domain, name("s"))).body<SystemResponse>()
            val event = capture.awaitEvent { it.message == "system.created" && it.hasKeyValue("systemId", created.id.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("domainId", domain.toLong()))
            admin.delete("/api/v1/systems/${created.id}")
            assertNotNull(capture.awaitEvent { it.message == "system.deleted" && it.hasKeyValue("systemId", created.id.toLong()) })
        }
    }
}
