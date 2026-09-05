package ch.nokillswit.notifications

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * The kind of in-app notification (Lettuce's model, ported): one value per contract event that
 * reaches a contract's FOLLOWERS (`contracts/ContractNotifications.kt` maps the event log's
 * types onto these), plus `VERSION_BREAKING_STORED` — a version stored with a waived
 * `BREAKING_WITHOUT_MAJOR_BUMP` finding, the one check verdict worth a push. The SPA renders
 * each one in the viewer's language from `notifications.event.*` keys; a kind a client build
 * does not know renders as its raw name.
 */
@Serializable
enum class NotificationType {
    CONTRACT_UPDATED,
    CONTRACT_OWNER_CHANGED,
    CONTRACT_DELETED,
    VERSION_CREATED,
    VERSION_CONTENT_UPDATED,
    VERSION_TRANSITIONED,
    VERSION_DELETED,
    VERSION_SYNCED,
    VERSION_SOURCE_CHANGED,
    VERSION_IMPORTED,
    VERSION_BREAKING_STORED,
}

/**
 * Internal create input. There is no public create endpoint: notifications are generated as a
 * side-effect of contract mutations and minted via [NotificationService.createAll]. [params]
 * carries the interpolation values (the contract's name, the actor's name, version numbers,
 * lifecycle names) — never document text; [link] is a language-independent SPA path.
 */
data class Notification(
    val recipientId: UInt,
    val type: NotificationType,
    val params: Map<String, String> = emptyMap(),
    val link: String? = null,
)

@Serializable
data class NotificationResponse(
    val id: UInt,
    val recipientId: UInt,
    /** Epoch millis, server-set at mint time. */
    val timestamp: Long,
    val type: NotificationType,
    val params: Map<String, String>,
    val link: String?,
    val wasSeen: Boolean,
)

typealias NotificationPageResponse = PageResponse<NotificationResponse>
