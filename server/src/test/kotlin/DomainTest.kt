package ch.nokillswit

import ch.nokillswit.domains.DomainPageResponse
import ch.nokillswit.domains.DomainRequest
import ch.nokillswit.domains.DomainResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The domain registry (V7): CRUD, the list filter, the authz split, the holds-systems 409, soft delete. */
class DomainTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/domains").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/domains").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/domains/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/domains/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("domuser")
        val id = TestDomains.seed(name("domro"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/domains").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/domains/$id").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/domains", DomainRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/domains/999999", DomainRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/domains/999999").status)
    }

    @Test
    fun `admin CRUD round-trips with the system count and soft delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("domcrud", UserRole.ADMIN)
        val n = name("Payments")
        val create = admin.postJson("/api/v1/domains", DomainRequest(name = "  $n ", description = " money "))
        assertEquals(HttpStatusCode.Created, create.status)
        assertNotNull(create.headers["Location"])
        val created = create.body<DomainResponse>()
        assertEquals(n, created.name)
        assertEquals("money", created.description)
        assertEquals(0, created.systemCount)

        TestSystems.seed(created.id, name("sys"))
        val read = admin.get("/api/v1/domains/${created.id}").body<DomainResponse>()
        assertEquals(1, read.systemCount)
        val page = admin.get("/api/v1/domains?name=${n.lowercase()}&sort=-updatedAt").body<DomainPageResponse>()
        assertEquals(1, page.total)
        assertEquals(1, page.items.single().systemCount)

        val renamed = name("Treasury")
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/domains/${created.id}", DomainRequest(renamed)).status)
        val after = admin.get("/api/v1/domains/${created.id}").body<DomainResponse>()
        assertEquals(renamed, after.name)
        assertEquals(null, after.description, "PUT is a full replace")

        // Holding a system: delete is refused; empty it first.
        val blocked = admin.delete("/api/v1/domains/${created.id}")
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertTrue(blocked.body<ProblemDetail>().detail!!.contains("still holds systems"))
        val sysPage = admin.get("/api/v1/systems?domainId=${created.id}").body<ch.nokillswit.systems.SystemPageResponse>()
        for (sys in sysPage.items) assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/systems/${sys.id}").status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/domains/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/domains/${created.id}").status)
        assertTrue(TestDomains.rawDeleted(created.id), "delete must soft-delete")
    }

    @Test
    fun `invalid payloads are 400, missing ids 404, an active name clash 409 and a deleted name is freed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dombad", UserRole.ADMIN)
        for (case in listOf(DomainRequest("  "), DomainRequest("x".repeat(101)), DomainRequest(name("ok"), "d".repeat(2001)))) {
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/domains", case).status, "expected 400 for $case")
        }
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/domains/999999", DomainRequest(name("nf"))).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/domains/999999").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/domains?sort=description").status)

        val n = name("Dup")
        val first = admin.postJson("/api/v1/domains", DomainRequest(n)).body<DomainResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/domains", DomainRequest(n.uppercase())).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/domains/${first.id}").status)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/domains", DomainRequest(n)).status)
    }

    @Test
    fun `mutations audit`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("domaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val created = admin.postJson("/api/v1/domains", DomainRequest(name("aud"))).body<DomainResponse>()
            assertNotNull(capture.awaitEvent { it.message == "domain.created" && it.hasKeyValue("domainId", created.id.toLong()) })
            admin.putJson("/api/v1/domains/${created.id}", DomainRequest(name("aud2")))
            assertNotNull(capture.awaitEvent { it.message == "domain.updated" && it.hasKeyValue("domainId", created.id.toLong()) })
            admin.delete("/api/v1/domains/${created.id}")
            assertNotNull(capture.awaitEvent { it.message == "domain.deleted" && it.hasKeyValue("domainId", created.id.toLong()) })
        }
    }
}
