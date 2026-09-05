package ch.nokillswit.contracts

import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey

val ContractActivityKey = AttributeKey<ContractActivity>("ContractActivity")

/**
 * The ONE chokepoint every contract mutation route calls after its commit: the followers'
 * notifications, then the history event (Lettuce's consistency model — separate transactions,
 * mutation → notifications → event → respond; a failure after the commit is a 500 with the
 * state already changed, accepted at this scale and documented in persistence.md). The actor
 * never receives their own notification.
 */
class ContractActivity(
    private val contracts: ContractService,
    private val users: UserService,
    private val subscriptions: ContractSubscriptionService,
    private val notifications: NotificationService,
    private val events: ContractEventService,
) {
    suspend fun record(
        contractId: UInt,
        byUserId: UInt,
        type: ContractEventType,
        params: Map<String, String> = emptyMap(),
        versionId: UInt? = null,
        breaking: Boolean = false,
    ) {
        val recipients = subscriptions.subscriberIds(contractId) - byUserId
        if (recipients.isNotEmpty()) {
            val link = if (versionId != null) ContractLinks.version(contractId, versionId) else ContractLinks.contract(contractId)
            val contractName = contracts.nameOf(contractId) ?: "#$contractId"
            val actorName = users.read(byUserId)?.name ?: "#$byUserId"
            notifications.createAll(contractNotifications(type, params, contractName, actorName, link, recipients, breaking))
        }
        events.record(contractId, byUserId, type, params)
    }
}
