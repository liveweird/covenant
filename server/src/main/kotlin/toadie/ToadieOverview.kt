package ch.nokillswit.toadie

import ch.nokillswit.contracts.ContractService.Contracts
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.toadie.ToadieService.Connections
import ch.nokillswit.toadie.ToadieService.Links
import ch.nokillswit.toadie.ToadieService.SnapshotEntities
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*

@Serializable
data class ToadieUsageSummary(
    val consumerCount: Long?,
    val providerCount: Long?,
    val cache: ToadieCacheStatus,
    val unavailableLinkCount: Int,
)

/** Same freshness rule for detailed usage and overview; callers capture time once per read. */
internal fun ResultRow.toadieCacheStatus(now: Long = nowMillis()): ToadieCacheStatus {
    val success = this[Connections.lastSuccessAt]
    val state = when {
        this[Connections.markedAsDeleted] -> ToadieCacheState.DISCONNECTED
        !this[Connections.enabled] -> ToadieCacheState.DISABLED
        success == null -> ToadieCacheState.NEVER_SYNCED
        this[Connections.lastErrorCode] != null || now - success > this[Connections.refreshIntervalMinutes] * 120_000L ->
            ToadieCacheState.STALE
        else -> ToadieCacheState.CURRENT
    }
    return ToadieCacheStatus(state, this[Connections.lastAttemptAt], success,
        this[Connections.refreshing], this[Connections.lastErrorCode])
}

/** SQL equivalent of an uncertain cache or any unavailable selected API; no service JSON reads. */
internal fun uncertainToadieUsage(now: Long): Op<Boolean> {
    val linked = Links.select(Links.id).where { Links.contractId eq Contracts.id }
    val apiExists = exists(SnapshotEntities.select(SnapshotEntities.entityId).where {
        (SnapshotEntities.connectionId eq Links.connectionId) and (SnapshotEntities.entityId eq Links.apiEntityId) and
            (SnapshotEntities.blueprint.lowerCase() eq Connections.apiBlueprint.lowerCase())
    })
    val bad = Links.innerJoin(Connections).select(Links.id).where {
        (Links.contractId eq Contracts.id) and (
            Connections.markedAsDeleted or (Connections.enabled eq false) or Connections.lastSuccessAt.isNull() or
                Connections.lastErrorCode.isNotNull() or
                (Connections.lastSuccessAt less
                    (longLiteral(now) - Connections.refreshIntervalMinutes.castTo(LongColumnType()) * longLiteral(120_000))) or
                not(apiExists)
            )
    }
    return notExists(linked) or exists(bad)
}

/** Runs inside the overview's transaction: three batch queries, independent of line count. */
internal suspend fun toadieUsageSummaries(contractIds: Set<UInt>, now: Long): Map<UInt, ToadieUsageSummary> {
    if (contractIds.isEmpty()) return emptyMap()
    val links = Links.selectAll().where { Links.contractId inList contractIds }.toList().groupBy { it[Links.contractId].value }
    val connectionIds = links.values.flatten().map { it[Links.connectionId].value }.toSet()
    val connections = Connections.selectAll().where { Connections.id inList connectionIds }.toList()
        .associateBy { it[Connections.id].value }
    // Deleted connections may retain snapshots indefinitely; only the bounded active registry
    // contributes graph data. Disconnected links need labels/status, never a cached service index.
    val readableIds = connections.filterValues { !it[Connections.markedAsDeleted] }.keys
    val snapshots = SnapshotEntities.selectAll().where { SnapshotEntities.connectionId inList readableIds }.toList()
        .groupBy { it[SnapshotEntities.connectionId].value }
    val indexes = connections.mapValues { (id, row) -> usageIndex(row, snapshots[id].orEmpty()) }
    return contractIds.associateWith { id ->
        val selected = links[id].orEmpty()
        val connection = selected.firstOrNull()?.let { connections[it[Links.connectionId].value] }
        val cache = connection?.toadieCacheStatus(now) ?: ToadieCacheStatus(
            if (selected.isEmpty()) ToadieCacheState.UNLINKED else ToadieCacheState.DISCONNECTED, null, null, false, null,
        )
        val index = connection?.let { indexes[it[Connections.id].value] }
        val missing = if (cache.state == ToadieCacheState.DISCONNECTED) selected.size else
            selected.count { it[Links.apiEntityId] !in index?.apis.orEmpty() }
        val known = cache.lastSuccessAt != null && cache.state != ToadieCacheState.DISCONNECTED && missing == 0
        val targets = selected.mapNotNull { index?.apis?.get(it[Links.apiEntityId]) }
        ToadieUsageSummary(
            if (known) targets.flatMap { index?.consumers?.get(it).orEmpty() }.toSet().size.toLong() else null,
            if (known) targets.flatMap { index?.providers?.get(it).orEmpty() }.toSet().size.toLong() else null,
            cache, missing,
        )
    }
}

private data class UsageIndex(
    val apis: Map<String, String>,
    val consumers: Map<String, Set<String>>,
    val providers: Map<String, Set<String>>,
)

private fun usageIndex(connection: ResultRow, entities: List<ResultRow>): UsageIndex {
    val apis = entities.filter { it[SnapshotEntities.blueprint].equals(connection[Connections.apiBlueprint], true) }
        .associate { it[SnapshotEntities.entityId] to it[SnapshotEntities.identifier] }
    val consumers = mutableMapOf<String, MutableSet<String>>()
    val providers = mutableMapOf<String, MutableSet<String>>()
    entities.filter { it[SnapshotEntities.blueprint].equals(connection[Connections.serviceBlueprint], true) }.forEach { entity ->
        val relations = Json.decodeFromString<Map<String, List<String>>>(entity[SnapshotEntities.relations])
        fun add(target: MutableMap<String, MutableSet<String>>, relation: String) {
            relations[relation].orEmpty().forEach { target.getOrPut(it) { mutableSetOf() }.add(entity[SnapshotEntities.entityId]) }
        }
        add(consumers, connection[Connections.consumesRelation])
        add(providers, connection[Connections.providesRelation])
    }
    return UsageIndex(apis, consumers, providers)
}
