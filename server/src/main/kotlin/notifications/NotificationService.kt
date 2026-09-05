package ch.nokillswit.notifications

import ch.nokillswit.infra.db.decodeParams
import ch.nokillswit.infra.db.encodeParams
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val NotificationServiceKey = AttributeKey<NotificationService>("NotificationService")

data class NotificationListResult(val items: List<NotificationResponse>, val total: Long)

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to NotificationService.Notifications.id,
    "timestamp" to NotificationService.Notifications.timestamp,
)

/**
 * The recipient's own notifications (V14) — Lettuce's service, ported minus the feature-flag
 * exclusion (Covenant has no area-gating flags) and the email mirror (in-app only, for now).
 * Every read and mutation runs over `Notifications.active()`; the list's count and rows share ONE predicate,
 * because the SPA's badge is the `total` of a pageSize-1 unseen query.
 */
class NotificationService(private val database: R2dbcDatabase) {
    object Notifications : UIntIdTable("notifications"), SoftDeletable {
        val recipientId = reference("recipient_id", UserService.Users)
        val timestamp = long("created_at")
        val notificationType = varchar("notification_type", length = 60)
        val params = text("params")
        val link = text("link").nullable()
        val wasSeen = bool("was_seen").default(false)
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /** Batch mint — one transaction per fan-out, one server-set timestamp shared by the batch. */
    suspend fun createAll(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        suspendTransaction(database) {
            val now = System.currentTimeMillis()
            notifications.forEach { n ->
                Notifications.insert {
                    it[recipientId] = n.recipientId
                    it[timestamp] = now
                    it[notificationType] = n.type.name
                    it[params] = encodeParams(n.params)
                    it[link] = n.link
                    it[wasSeen] = false
                }
            }
        }
    }

    suspend fun read(id: UInt): NotificationResponse? = suspendTransaction(database) {
        Notifications.selectAll().where { (Notifications.id eq id) and Notifications.active() }.map { it.toResponse() }.singleOrNull()
    }

    suspend fun markSeen(id: UInt): Int = suspendTransaction(database) {
        Notifications.update({ (Notifications.id eq id) and Notifications.active() }) { it[wasSeen] = true }
    }

    suspend fun markUnseen(id: UInt): Int = suspendTransaction(database) {
        Notifications.update({ (Notifications.id eq id) and Notifications.active() }) { it[wasSeen] = false }
    }

    /** Marks every one of the recipient's still-unseen notifications as seen; returns the row count. */
    suspend fun markAllSeen(recipientId: UInt): Int = suspendTransaction(database) {
        val unseenOf = (Notifications.recipientId eq recipientId) and (Notifications.wasSeen eq false) and Notifications.active()
        Notifications.update({ unseenOf }) {
            it[wasSeen] = true
        }
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Notifications.update({ (Notifications.id eq id) and Notifications.active() }) { it[markedAsDeleted] = true }
    }

    suspend fun list(recipientId: UInt, wasSeen: Boolean?, paging: PageRequest): NotificationListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = (Notifications.recipientId eq recipientId) and Notifications.active()
        wasSeen?.let { predicate = predicate and (Notifications.wasSeen eq it) }
        val total = Notifications.selectAll().where { predicate }.count()
        val rows = Notifications.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).map { it.toResponse() }.toList()
        NotificationListResult(items = rows, total = total)
    }

    private fun ResultRow.toResponse() = NotificationResponse(
        id = this[Notifications.id].value,
        recipientId = this[Notifications.recipientId].value,
        timestamp = this[Notifications.timestamp],
        type = NotificationType.valueOf(this[Notifications.notificationType]),
        params = decodeParams(this[Notifications.params]),
        link = this[Notifications.link],
        wasSeen = this[Notifications.wasSeen],
    )
}
