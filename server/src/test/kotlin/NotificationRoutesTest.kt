package ch.nokillswit

import ch.nokillswit.notifications.Notification
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lettuce's recipient-only notification routes, ported: scoping, the wasSeen filter, seen/unseen, seen-all, soft delete. */
class NotificationRoutesTest {

    private fun of(recipient: UInt, type: NotificationType, version: String? = null, link: String? = null) = Notification(
        recipient, type, buildMap { put("contractName", "orders"); put("actor", "Ada"); version?.let { put("version", it) } }, link,
    )

    @Test
    fun `list - caller-scoped, newest first, the wasSeen filter, a malformed filter is 400`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("ntf")
        val userId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val client = authedClient(email, "pw")
        val other = TestUsers.seed(uniqueEmail("ntf-other"), "pw", role = UserRole.USER)
        TestNotifications.service.createAll(listOf(of(userId, NotificationType.VERSION_CREATED, "1.0.0", "/contracts/1/versions/2")))
        TestNotifications.service.createAll(
            listOf(of(userId, NotificationType.CONTRACT_UPDATED), of(other, NotificationType.VERSION_CREATED, "9.0.0")),
        )
        val page = client.get("/api/v1/notifications").body<NotificationPageResponse>()
        assertEquals(2, page.total)
        assertEquals(setOf(NotificationType.VERSION_CREATED, NotificationType.CONTRACT_UPDATED), page.items.map { it.type }.toSet())
        assertTrue(page.items.zipWithNext().all { (a, b) -> a.timestamp >= b.timestamp }, "newest first")
        val created = page.items.single { it.type == NotificationType.VERSION_CREATED }
        assertEquals(mapOf("contractName" to "orders", "actor" to "Ada", "version" to "1.0.0"), created.params)
        assertEquals("/contracts/1/versions/2", created.link)
        val badge = client.get("/api/v1/notifications?wasSeen=false&pageSize=1").body<NotificationPageResponse>()
        assertEquals(2, badge.total, "the badge query")
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/notifications/${created.id}/seen").status)
        assertEquals(1, client.get("/api/v1/notifications?wasSeen=false").body<NotificationPageResponse>().total)
        assertEquals(1, client.get("/api/v1/notifications?wasSeen=true").body<NotificationPageResponse>().total)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/notifications?wasSeen=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/notifications?sort=link").status)
    }

    @Test
    fun `recipient-only - a foreign row is 403 for anyone including ADMIN, a missing one 404, seen-all scopes to the caller`() =
        testApplication {
            usePostgresTestcontainer()
            val email = uniqueEmail("ntf-own")
            val userId = TestUsers.seed(email, "pw", role = UserRole.USER)
            val owner = authedClient(email, "pw")
            val admin = seededClient("ntf-admin", UserRole.ADMIN)
            val stranger = seededClient("ntf-stranger", UserRole.USER)
            TestNotifications.service.createAll(
                listOf(of(userId, NotificationType.VERSION_CREATED, "1.0.0"), of(userId, NotificationType.VERSION_DELETED, "0.9.0")),
            )
            val mine = owner.get("/api/v1/notifications").body<NotificationPageResponse>().items
            val one = mine.first()
            assertEquals(one, owner.get("/api/v1/notifications/${one.id}").body<NotificationResponse>())
            assertEquals(HttpStatusCode.Forbidden, admin.get("/api/v1/notifications/${one.id}").status)
            assertEquals(HttpStatusCode.Forbidden, stranger.post("/api/v1/notifications/${one.id}/seen").status)
            assertEquals(HttpStatusCode.Forbidden, stranger.post("/api/v1/notifications/${one.id}/unseen").status)
            assertEquals(HttpStatusCode.Forbidden, admin.delete("/api/v1/notifications/${one.id}").status)
            assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/notifications/999999999").status)
            assertEquals(HttpStatusCode.NotFound, owner.post("/api/v1/notifications/999999999/seen").status)
            assertEquals(HttpStatusCode.BadRequest, owner.get("/api/v1/notifications/-1").status)
            // seen-all touches only the caller's rows: the stranger's call changes nothing for the owner.
            assertEquals(HttpStatusCode.NoContent, stranger.post("/api/v1/notifications/seen-all").status)
            assertEquals(2, owner.get("/api/v1/notifications?wasSeen=false").body<NotificationPageResponse>().total)
            assertEquals(HttpStatusCode.NoContent, owner.post("/api/v1/notifications/seen-all").status)
            assertEquals(0, owner.get("/api/v1/notifications?wasSeen=false").body<NotificationPageResponse>().total)
            // seen/unseen are idempotent toggles.
            assertEquals(HttpStatusCode.NoContent, owner.post("/api/v1/notifications/${one.id}/unseen").status)
            assertEquals(HttpStatusCode.NoContent, owner.post("/api/v1/notifications/${one.id}/unseen").status)
            assertEquals(1, owner.get("/api/v1/notifications?wasSeen=false").body<NotificationPageResponse>().total)
            // A soft-deleted row vanishes from every read and mutation.
            assertEquals(HttpStatusCode.NoContent, owner.delete("/api/v1/notifications/${one.id}").status)
            assertEquals(HttpStatusCode.NotFound, owner.get("/api/v1/notifications/${one.id}").status)
            assertEquals(HttpStatusCode.NotFound, owner.post("/api/v1/notifications/${one.id}/seen").status)
            assertEquals(HttpStatusCode.NotFound, owner.delete("/api/v1/notifications/${one.id}").status)
            assertEquals(1, owner.get("/api/v1/notifications").body<NotificationPageResponse>().total)
        }
}
