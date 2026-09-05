package ch.nokillswit.contracts

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * The contract's structural history (V11) — the first `EventLogTable` clone in Covenant. Events
 * are minted by the ROUTES after a mutation commits (Toadie's consistency model: the mutation
 * and its event never share a transaction); params are small string maps the SPA localizes —
 * never document text (a content change is the FACT of the change; the diff is computed live
 * from the stored versions).
 */
@Serializable
enum class ContractEventType {
    CREATED, UPDATED, OWNER_CHANGED, DELETED,
    VERSION_CREATED, VERSION_CONTENT_UPDATED, VERSION_TRANSITIONED, VERSION_DELETED, VERSION_RECHECKED,
    VERSION_SOURCE_CHANGED, VERSION_SYNCED,
    IMPORTED,
}

@Serializable
data class ContractEventResponse(
    val id: UInt,
    val contractId: UInt,
    val userId: UInt,
    val userName: String,
    val timestamp: Long,
    val type: ContractEventType,
    val params: Map<String, String> = emptyMap(),
)

typealias ContractEventPageResponse = PageResponse<ContractEventResponse>

data class ContractEventListResult(val items: List<ContractEventResponse>, val total: Long)

val ContractEventServiceKey = AttributeKey<ContractEventService>("ContractEventService")

class ContractEventService(database: R2dbcDatabase) {
    object ContractEvents : EventLogTable("contract_events", "contract_id", ContractService.Contracts)

    private val log = EventLog(database, ContractEvents)

    suspend fun record(contractId: UInt, byUserId: UInt, type: ContractEventType, params: Map<String, String> = emptyMap()): UInt =
        log.create(contractId, byUserId, type.name, params)

    suspend fun listFor(contractId: UInt, paging: PageRequest): ContractEventListResult {
        val page = log.listFor(contractId, paging)
        return ContractEventListResult(
            items = page.items.map {
                ContractEventResponse(
                    it.id,
                    it.ownerId,
                    it.userId,
                    it.userName,
                    it.timestamp,
                    ContractEventType.valueOf(it.type),
                    it.params,
                )
            },
            total = page.total,
        )
    }
}

/** The `owner.from`/`owner.to` param spelling — `TEAM:<id>` / `USER:<id>`. */
fun Ownership.asParam(): String = if (teamId != null) "TEAM:$teamId" else "USER:$userId"
