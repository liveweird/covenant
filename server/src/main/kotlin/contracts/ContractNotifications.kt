package ch.nokillswit.contracts

import ch.nokillswit.notifications.Notification
import ch.nokillswit.notifications.NotificationType

/**
 * Pure mapping from a contract event to the notifications its FOLLOWERS receive (Lettuce's
 * `*Notifications.kt` builder pattern — no DB, the caller resolves names and recipients).
 * Params carry the contract's name, the actor's name and the event's version/lifecycle facts —
 * never document text; the SPA formats them in the viewer's language. `CREATED` and
 * `VERSION_RECHECKED` produce nothing: a new contract has no followers yet, and a recheck
 * changes no fact worth a push.
 */
fun contractNotifications(
    type: ContractEventType,
    params: Map<String, String>,
    contractName: String,
    actorName: String,
    link: String,
    recipients: Set<UInt>,
    breaking: Boolean = false,
): List<Notification> {
    val kind = notificationTypeOf(type) ?: return emptyList()
    val shared = buildMap {
        put("contractName", contractName)
        put("actor", actorName)
        params["version"]?.let { put("version", it) }
        params["from"]?.let { put("from", it) }
        params["to"]?.let { put("to", it) }
        params["name"]?.let { put("name", it) }
    }
    return recipients.sorted().flatMap { recipient ->
        buildList {
            add(Notification(recipient, kind, shared, link))
            if (breaking) add(Notification(recipient, NotificationType.VERSION_BREAKING_STORED, shared, link))
        }
    }
}

private fun notificationTypeOf(type: ContractEventType): NotificationType? = when (type) {
    ContractEventType.CREATED, ContractEventType.VERSION_RECHECKED -> null
    ContractEventType.UPDATED -> NotificationType.CONTRACT_UPDATED
    ContractEventType.OWNER_CHANGED -> NotificationType.CONTRACT_OWNER_CHANGED
    ContractEventType.DELETED -> NotificationType.CONTRACT_DELETED
    ContractEventType.VERSION_CREATED -> NotificationType.VERSION_CREATED
    ContractEventType.VERSION_CONTENT_UPDATED -> NotificationType.VERSION_CONTENT_UPDATED
    ContractEventType.VERSION_TRANSITIONED -> NotificationType.VERSION_TRANSITIONED
    ContractEventType.VERSION_DELETED -> NotificationType.VERSION_DELETED
    ContractEventType.VERSION_SOURCE_CHANGED -> NotificationType.VERSION_SOURCE_CHANGED
    ContractEventType.VERSION_SYNCED -> NotificationType.VERSION_SYNCED
    ContractEventType.IMPORTED -> NotificationType.VERSION_IMPORTED
}
