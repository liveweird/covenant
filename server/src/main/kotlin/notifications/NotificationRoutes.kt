package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireNotificationRecipient
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/** The recipient-scoped notifications family. The literal `seen-all` segment wins over `{id}`. */
@Serializable
@Resource("/api/v1/notifications")
class NotificationsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: NotificationsRoute = NotificationsRoute(), val id: UInt) {
        @Serializable
        @Resource("seen")
        class Seen(val parent: Id)

        @Serializable
        @Resource("unseen")
        class Unseen(val parent: Id)
    }

    @Serializable
    @Resource("seen-all")
    class SeenAll(val parent: NotificationsRoute = NotificationsRoute())
}

/**
 * Lettuce's notification routes, ported: every endpoint is recipient-only — ADMIN included
 * (read-before-guard: a missing row is 404, a foreign one 403). Seen/unseen are view state and
 * deliberately unaudited (Toadie's drag-PUT exception); a delete is audited.
 */
fun Application.configureNotificationRoutes() {
    val notificationService = attributes[NotificationServiceKey]

    suspend fun ApplicationCall.own(id: UInt): NotificationResponse {
        val notification = notificationService.read(id) ?: throw NotFoundException("Notification not found")
        requireNotificationRecipient(caller(), notification.recipientId)
        return notification
    }

    routing {
        authenticate {
            get<NotificationsRoute> {
                val caller = call.caller()
                val paging = call.parsePaging(
                    sortable = setOf("id", "timestamp"),
                    defaultSort = listOf(SortField("timestamp", descending = true)),
                )
                val wasSeen = call.request.queryParameters.optionalBoolean("wasSeen")
                val result = notificationService.list(caller.userId, wasSeen, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<NotificationsRoute.Id> { route ->
                call.respond(HttpStatusCode.OK, call.own(route.id))
            }
            post<NotificationsRoute.Id.Seen> { route ->
                call.own(route.parent.id)
                if (notificationService.markSeen(route.parent.id) == 0) throw NotFoundException("Notification not found")
                call.respond(HttpStatusCode.NoContent)
            }
            post<NotificationsRoute.Id.Unseen> { route ->
                call.own(route.parent.id)
                if (notificationService.markUnseen(route.parent.id) == 0) throw NotFoundException("Notification not found")
                call.respond(HttpStatusCode.NoContent)
            }
            post<NotificationsRoute.SeenAll> {
                // No per-row guard needed: the update is intrinsically scoped to the caller's own rows.
                notificationService.markAllSeen(call.caller().userId)
                call.respond(HttpStatusCode.NoContent)
            }
            delete<NotificationsRoute.Id> { route ->
                val caller = call.caller()
                call.own(route.id)
                if (notificationService.delete(route.id) == 0) throw NotFoundException("Notification not found")
                audit("notification.deleted", "byUserId" to caller.userId.toLong(), "notificationId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
