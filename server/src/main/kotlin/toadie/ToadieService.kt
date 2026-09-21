package ch.nokillswit.toadie

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.infra.crypto.EncryptedAtRest
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.crypto.reencryptRows
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.text.Normalizer

val ToadieServiceKey = AttributeKey<ToadieService>("ToadieService")

data class ToadieConnectionListResult(val items: List<ToadieConnectionResponse>, val total: Long)
data class ToadieApiListResult(val items: List<ToadieEntityRef>, val total: Long)
@kotlinx.serialization.Serializable
internal data class ToadieFullUsageProjection(
    val connection: ToadieConnectionRef?,
    val cache: ToadieCacheStatus,
    val linkedApis: List<ToadieLinkResponse>,
    val services: List<ToadieUsageRow>,
)
data class RefreshClaim(val connectionId: UInt, val revision: Long, val token: String, val config: ToadieFetchConfig)
enum class RefreshClaimResult { ACCEPTED, COALESCED, COOLDOWN, MISSING, DISABLED }

private val json = Json { ignoreUnknownKeys = false }
private val budgetJson = Json { encodeDefaults = true; explicitNulls = true }

class ToadieService(
    private val database: R2dbcDatabase,
    private val cipher: FieldCipher,
    private val contracts: ContractService,
) : EncryptedAtRest {
    object Connections : UIntIdTable("toadie_connections"), SoftDeletable {
        val name = varchar("name", MAX_TOADIE_NAME_LENGTH)
        val baseUrl = varchar("base_url", MAX_TOADIE_URL_LENGTH)
        val browserUrl = varchar("browser_url", MAX_TOADIE_URL_LENGTH)
        val apiKey = text("api_key")
        val enabled = bool("enabled")
        val refreshIntervalMinutes = integer("refresh_interval_minutes")
        val serviceBlueprint = varchar("service_blueprint", 100)
        val apiBlueprint = varchar("api_blueprint", 100)
        val providesRelation = varchar("provides_relation", 100)
        val consumesRelation = varchar("consumes_relation", 100)
        val systemRelation = varchar("system_relation", 100)
        val snapshotSystemBlueprint = varchar("snapshot_system_blueprint", 100).nullable()
        val configRevision = long("config_revision")
        val lastAttemptAt = long("last_attempt_at").nullable()
        val lastSuccessAt = long("last_success_at").nullable()
        val lastErrorCode = varchar("last_error_code", 100).nullable()
        val refreshing = bool("refreshing")
        val leaseUntil = long("lease_until").nullable()
        val refreshToken = varchar("refresh_token", 36).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted")
    }

    object SnapshotEntities : Table("toadie_snapshot_entities") {
        val connectionId = reference("connection_id", Connections)
        val entityId = varchar("entity_id", 100)
        val blueprint = varchar("blueprint", 100)
        val identifier = varchar("identifier", 200)
        val title = varchar("title", 200)
        val teamIdentifiers = text("team_identifiers")
        val relations = text("relations")
        val remoteUpdatedAt = long("remote_updated_at")
        override val primaryKey = PrimaryKey(connectionId, entityId)
    }

    object Links : UIntIdTable("contract_toadie_links") {
        val contractId = reference("contract_id", ContractService.Contracts)
        val connectionId = reference("connection_id", Connections)
        val apiEntityId = varchar("api_entity_id", 100)
        val identifier = varchar("identifier", 200)
        val title = varchar("title", 200)
        val url = varchar("url", MAX_TOADIE_URL_LENGTH).nullable()
        val createdAt = long("created_at")
    }

    override val encryptedRowLabel = "Toadie API key"

    override suspend fun encryptLegacyRows(reencryptAll: Boolean): Int = suspendTransaction(database) {
        cipher.reencryptRows(Connections, listOf(Connections.apiKey), reencryptAll)
    }

    suspend fun list(paging: PageRequest): ToadieConnectionListResult = suspendTransaction(database) {
        val predicate = Connections.active()
        val total = Connections.selectAll().where { predicate }.count()
        val columns = mapOf<String, Column<*>>(
            "id" to Connections.id, "name" to Connections.name,
            "createdAt" to Connections.createdAt, "updatedAt" to Connections.updatedAt,
        )
        val rows = Connections.selectAll().where { predicate }.applyPaging(paging, columns).toList()
        ToadieConnectionListResult(rows.map { it.toResponse() }, total)
    }

    suspend fun read(id: UInt): ToadieConnectionResponse? = suspendTransaction(database) {
        activeConnection(id)?.toResponse()
    }

    suspend fun create(request: ToadieConnectionRequest): UInt = suspendTransaction(database) {
        // The active-row limit is a predicate invariant, so serialize the tiny ADMIN-only
        // registry before count + insert; row locks cannot protect the not-yet-existing tenth row.
        exec("LOCK TABLE toadie_connections IN SHARE ROW EXCLUSIVE MODE")
        if (Connections.selectAll().where { Connections.active() }.count() >= MAX_TOADIE_CONNECTIONS) {
            throw ConflictException("At most $MAX_TOADIE_CONNECTIONS active Toadie connections are allowed")
        }
        val stamp = nowMillis()
        Connections.insert {
            it[name] = request.name
            it[baseUrl] = request.baseUrl
            it[browserUrl] = request.browserUrl
            it[apiKey] = cipher.encrypt(checkNotNull(request.apiKey))
            it[enabled] = request.enabled
            it[refreshIntervalMinutes] = request.refreshIntervalMinutes
            it.applyMapping(request.mapping)
            it[configRevision] = 1
            it[refreshing] = false
            it[createdAt] = stamp
            it[updatedAt] = stamp
            it[markedAsDeleted] = false
        }[Connections.id].value
    }

    suspend fun update(id: UInt, request: ToadieConnectionRequest): Int = suspendTransaction(database) {
        val current = Connections.selectAll().where { (Connections.id eq id) and Connections.active() }
            .forUpdate().toList().singleOrNull() ?: return@suspendTransaction 0
        if (request.baseUrl != current[Connections.baseUrl]) {
            throw ConflictException("baseUrl is the connection identity; create a new connection to change it")
        }
        val mappingChanged = request.mapping != current.mapping()
        val key = request.apiKey?.let(cipher::encrypt) ?: current[Connections.apiKey]
        val changed = Connections.update({ (Connections.id eq id) and Connections.active() }) {
            it[name] = request.name
            it[browserUrl] = request.browserUrl
            it[apiKey] = key
            it[enabled] = request.enabled
            it[refreshIntervalMinutes] = request.refreshIntervalMinutes
            it.applyMapping(request.mapping)
            it[configRevision] = current[Connections.configRevision] + 1
            it[refreshing] = false
            it[leaseUntil] = null
            it[refreshToken] = null
            if (mappingChanged) {
                it[lastSuccessAt] = null
                it[lastErrorCode] = null
                it[snapshotSystemBlueprint] = null
            }
            it[updatedAt] = nowMillis()
        }
        if (mappingChanged) SnapshotEntities.deleteWhere { SnapshotEntities.connectionId eq id }
        changed
    }

    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Connections.update({ (Connections.id eq id) and Connections.active() }) {
            it[markedAsDeleted] = true
            it[enabled] = false
            it[refreshing] = false
            it[leaseUntil] = null
            it[refreshToken] = null
            it[configRevision] = Connections.configRevision + 1
            it[updatedAt] = nowMillis()
        }
    }

    suspend fun listApis(id: UInt, query: String?, paging: PageRequest): ToadieApiListResult? = suspendTransaction(database) {
        val connection = activeConnection(id) ?: return@suspendTransaction null
        var predicate: Op<Boolean> = (SnapshotEntities.connectionId eq id) and
            (SnapshotEntities.blueprint.lowerCase() eq connection[Connections.apiBlueprint].lowercase())
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            predicate = predicate and (
                SnapshotEntities.identifier.containsNormalized(q) or SnapshotEntities.title.containsNormalized(q)
                )
        }
        val total = SnapshotEntities.selectAll().where { predicate }.count()
        val columns = mapOf<String, Column<*>>("id" to SnapshotEntities.entityId, "title" to SnapshotEntities.title)
        val items = SnapshotEntities.selectAll().where { predicate }.applyPaging(paging, columns).map {
            it.toEntityRef(connection[Connections.browserUrl])
        }.toList()
        ToadieApiListResult(items, total)
    }

    suspend fun replaceLinks(contractId: UInt, request: ToadieLinksRequest, caller: CallerPrincipal): Boolean =
        suspendTransaction(database) {
            contracts.requireCurrentWriter(caller, contractId)
            if (request.apiEntityIds.size > MAX_TOADIE_LINKS || request.apiEntityIds.distinct().size != request.apiEntityIds.size) {
                throw BadRequestException("apiEntityIds must contain at most $MAX_TOADIE_LINKS unique values")
            }
            if (request.connectionId == null) {
                if (request.apiEntityIds.isNotEmpty()) throw BadRequestException("apiEntityIds must be empty when connectionId is null")
                val changed = Links.deleteWhere { Links.contractId eq contractId } > 0
                return@suspendTransaction changed
            }
            val connection = activeConnection(request.connectionId)
                ?: throw BadRequestException("Unknown or deleted Toadie connection id: ${request.connectionId}")
            val apiRows = if (request.apiEntityIds.isEmpty()) emptyList() else SnapshotEntities.selectAll().where {
                (SnapshotEntities.connectionId eq request.connectionId) and
                    (SnapshotEntities.blueprint.lowerCase() eq connection[Connections.apiBlueprint].lowercase()) and
                    (SnapshotEntities.entityId inList request.apiEntityIds)
            }.toList()
            if (apiRows.size != request.apiEntityIds.size) {
                throw BadRequestException("Every apiEntityId must exist in the current connection snapshot")
            }
            val previous = Links.select(Links.connectionId, Links.apiEntityId).where { Links.contractId eq contractId }
                .map { it[Links.connectionId].value to it[Links.apiEntityId] }.toList().toSet()
            val next = request.apiEntityIds.map { request.connectionId to it }.toSet()
            if (previous == next) return@suspendTransaction false
            Links.deleteWhere { Links.contractId eq contractId }
            val byId = apiRows.associateBy { it[SnapshotEntities.entityId] }
            request.apiEntityIds.forEach { entityId ->
                val entity = checkNotNull(byId[entityId])
                Links.insert {
                    it[Links.contractId] = contractId
                    it[Links.connectionId] = request.connectionId
                    it[apiEntityId] = entityId
                    it[identifier] = entity[SnapshotEntities.identifier]
                    it[title] = entity[SnapshotEntities.title]
                    it[url] = entityUrl(connection[Connections.browserUrl], entityId)
                    it[createdAt] = nowMillis()
                }
            }
            true
        }

    suspend fun links(contractId: UInt): ToadieLinksResponse? = suspendTransaction(database) {
        val linkState = linksInTransaction(contractId) ?: return@suspendTransaction null
        val connection = linkState.first
        val links = linkState.second
        if (links.isEmpty()) return@suspendTransaction ToadieLinksResponse(contractId, null, unlinkedStatus(), emptyList())
        val cachedApis = cachedLinkedApis(connection, links)
        ToadieLinksResponse(
            contractId,
            connection?.toRef(),
            connection?.cacheStatus() ?: disconnectedStatus(),
            projectLinks(connection, links, cachedApis),
        )
    }

    suspend fun usage(
        contractId: UInt,
        query: String?,
        role: ToadieUsageRole?,
        paging: PageRequest,
    ): ToadieUsageResponse? = suspendTransaction(database) {
        val projection = fullUsageInTransaction(contractId) ?: return@suspendTransaction null
        var rows = projection.services.asSequence()
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val q = foldSearch(raw)
            rows = rows.filter { q in foldSearch(it.identifier) || q in foldSearch(it.title) }
        }
        role?.let { required -> rows = rows.filter { required in it.roles } }
        val comparator = paging.sort.map { field ->
            val ascending = when (field.name) {
                "title" -> compareBy<ToadieUsageRow> { it.title.lowercase() }
                else -> compareBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }
            }
            if (field.descending) ascending.reversed() else ascending
        }.reduce(Comparator<ToadieUsageRow>::thenComparing)
        val ordered = rows.sortedWith(comparator).toList()
        val offsetLong = (paging.page.toLong() - 1L) * paging.pageSize.toLong()
        val pageItems = if (offsetLong >= ordered.size) {
            emptyList()
        } else {
            ordered.drop(offsetLong.toInt()).take(paging.pageSize)
        }
        ToadieUsageResponse(
            items = pageItems, page = paging.page, pageSize = paging.pageSize,
            total = ordered.size.toLong(), connection = projection.connection, cache = projection.cache,
        )
    }

    /** Complete bounded cached projection. The caller may own a wider read transaction. */
    internal suspend fun fullUsageInTransaction(
        contractId: UInt,
        now: Long = nowMillis(),
        maxProjectedBytes: Long? = null,
    ): ToadieFullUsageProjection? {
        val linkState = linksInTransaction(contractId) ?: return null
        val connection = linkState.first
        val links = linkState.second
        if (links.isEmpty()) return ToadieFullUsageProjection(null, unlinkedStatus(), emptyList(), emptyList())
        val connectionId = links.first()[Links.connectionId].value
        if (connection == null) {
            val retained = projectLinks(null, links, cachedLinkedApis(null, links))
            return ToadieFullUsageProjection(null, disconnectedStatus(), retained, emptyList())
        }
        val mapping = connection.mapping()
        val entities = SnapshotEntities.selectAll().where { SnapshotEntities.connectionId eq connectionId }
            .map { it.toSnapshot() }.toList()
        val cachedApis = entities.filter {
            it.blueprint.equals(mapping.apiBlueprint, ignoreCase = true)
        }.associateBy { it.id }
        val linkedApis = projectLinks(connection, links, cachedApis)
        val linkedIds = links.map { it[Links.apiEntityId] }.toSet()
        val apiIdentifiersById = cachedApis.filterKeys { it in linkedIds }.mapValues { it.value.identifier }
        val targetIdentifiers = apiIdentifiersById.values.toSet()
        val systems = entities.filter {
            it.blueprint.equals(connection[Connections.snapshotSystemBlueprint], ignoreCase = true)
        }.associateBy { it.identifier }
        val teams = entities.filter { it.blueprint.equals("_team", ignoreCase = true) }.associateBy { it.identifier }
        val browserUrl = connection[Connections.browserUrl]
        val systemRefs = systems.mapValues { it.value.toRef(browserUrl) }
        val teamRefs = teams.mapValues { it.value.toRef(browserUrl) }
        var projectedBytes = maxProjectedBytes?.let {
            budgetJson.encodeToString(
                ToadieFullUsageProjection(connection.toRef(), connection.cacheStatus(now), linkedApis, emptyList()),
            ).toByteArray(Charsets.UTF_8).size.toLong()
        } ?: 0L
        if (maxProjectedBytes != null && projectedBytes > maxProjectedBytes) {
            throw ConflictException("Migration report exceeds the 8 MiB export limit")
        }
        val services = mutableListOf<ToadieUsageRow>()
        entities.asSequence()
            .filter { it.blueprint.equals(mapping.serviceBlueprint, ignoreCase = true) }
            .sortedBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }
            .mapNotNull { service ->
                val providedIdentifiers = service.relations[mapping.providesRelation].orEmpty()
                    .filter { it in targetIdentifiers }
                val consumedIdentifiers = service.relations[mapping.consumesRelation].orEmpty()
                    .filter { it in targetIdentifiers }
                if (providedIdentifiers.isEmpty() && consumedIdentifiers.isEmpty()) return@mapNotNull null
                val provided = apiIdentifiersById.filterValues { it in providedIdentifiers }.keys.sorted()
                val consumed = apiIdentifiersById.filterValues { it in consumedIdentifiers }.keys.sorted()
                ToadieUsageRow(
                    id = service.id, identifier = service.identifier, title = service.title,
                    url = entityUrl(browserUrl, service.id),
                    roles = buildList {
                        if (provided.isNotEmpty()) add(ToadieUsageRole.PROVIDER)
                        if (consumed.isNotEmpty()) add(ToadieUsageRole.CONSUMER)
                    },
                    providedApiEntityIds = provided, consumedApiEntityIds = consumed,
                    systems = service.relations[mapping.systemRelation].orEmpty()
                        .mapNotNull(systemRefs::get),
                    teams = service.teamIdentifiers.mapNotNull(teamRefs::get),
                )
            }.forEach { row ->
                maxProjectedBytes?.let { limit ->
                    val rowBytes = budgetJson.encodeToString(row).toByteArray(Charsets.UTF_8).size.toLong() + 1L
                    if (projectedBytes + rowBytes > limit) {
                        throw ConflictException("Migration report exceeds the 8 MiB export limit")
                    }
                    projectedBytes += rowBytes
                }
                services.add(row)
            }
        return ToadieFullUsageProjection(connection.toRef(), connection.cacheStatus(now), linkedApis, services)
    }

    suspend fun claimRefresh(id: UInt, force: Boolean): Pair<RefreshClaimResult, RefreshClaim?> = suspendTransaction(database) {
        val now = nowMillis()
        val row = Connections.selectAll().where { (Connections.id eq id) and Connections.active() }.forUpdate().toList().singleOrNull()
            ?: return@suspendTransaction RefreshClaimResult.MISSING to null
        if (!row[Connections.enabled]) return@suspendTransaction RefreshClaimResult.DISABLED to null
        if (row[Connections.refreshing] && (row[Connections.leaseUntil] ?: 0) > now) {
            return@suspendTransaction RefreshClaimResult.COALESCED to null
        }
        if (force && row[Connections.lastAttemptAt]?.let { now - it < 30_000 } == true) {
            return@suspendTransaction RefreshClaimResult.COOLDOWN to null
        }
        val token = java.util.UUID.randomUUID().toString()
        Connections.update({ (Connections.id eq id) and (Connections.configRevision eq row[Connections.configRevision]) }) {
            it[refreshing] = true
            // Ten active connections and two workers bound queueing to five fetch waves; the
            // token below is still the actual stale-worker CAS if a lease is reclaimed.
            it[leaseUntil] = now + 600_000
            it[refreshToken] = token
            it[lastAttemptAt] = now
        }
        RefreshClaimResult.ACCEPTED to RefreshClaim(
            id,
            row[Connections.configRevision],
            token,
            ToadieFetchConfig(row[Connections.baseUrl], cipher.decrypt(row[Connections.apiKey]), row.mapping()),
        )
    }

    suspend fun dueConnectionIds(): List<UInt> = suspendTransaction(database) {
        val now = nowMillis()
        Connections.selectAll().where { Connections.active() and (Connections.enabled eq true) }.mapNotNull { row ->
            val dueAt = (row[Connections.lastAttemptAt] ?: 0) + row[Connections.refreshIntervalMinutes] * 60_000L
            row[Connections.id].value.takeIf { dueAt <= now && (!row[Connections.refreshing] || (row[Connections.leaseUntil] ?: 0) <= now) }
        }.toList()
    }

    suspend fun publish(claim: RefreshClaim, snapshot: ToadieSnapshot): Boolean = suspendTransaction(database) {
        val valid = Connections.selectAll().where {
                (Connections.id eq claim.connectionId) and Connections.active() and
                (Connections.configRevision eq claim.revision) and (Connections.refreshToken eq claim.token) and
                (Connections.refreshing eq true)
        }.forUpdate().toList().singleOrNull() ?: return@suspendTransaction false
        SnapshotEntities.deleteWhere { SnapshotEntities.connectionId eq claim.connectionId }
        snapshot.entities.forEach { entity ->
            SnapshotEntities.insert {
                it[connectionId] = claim.connectionId
                it[entityId] = entity.id
                it[blueprint] = entity.blueprint
                it[identifier] = entity.identifier
                it[title] = entity.title
                it[teamIdentifiers] = json.encodeToString(entity.teamIdentifiers)
                it[relations] = json.encodeToString(entity.relations)
                it[remoteUpdatedAt] = entity.updatedAt
            }
        }
        Connections.update({
            (Connections.id eq claim.connectionId) and (Connections.configRevision eq claim.revision) and
                (Connections.refreshToken eq claim.token)
        }) {
            it[lastSuccessAt] = snapshot.fetchedAt
            it[snapshotSystemBlueprint] = snapshot.systemBlueprint
            it[lastErrorCode] = null
            it[refreshing] = false
            it[leaseUntil] = null
            it[refreshToken] = null
        }
        check(valid[Connections.refreshToken] == claim.token)
        true
    }

    suspend fun fail(claim: RefreshClaim, code: String) = suspendTransaction(database) {
        Connections.update({
            (Connections.id eq claim.connectionId) and Connections.active() and
                (Connections.configRevision eq claim.revision) and (Connections.refreshToken eq claim.token)
        }) {
            it[lastErrorCode] = sanitizeErrorCode(code)
            it[refreshing] = false
            it[leaseUntil] = null
            it[refreshToken] = null
        }
    }

    /** Clears only this worker's lease; a reclaimed/newer claim remains untouched. */
    suspend fun release(claim: RefreshClaim) = suspendTransaction(database) {
        Connections.update({
            (Connections.id eq claim.connectionId) and (Connections.configRevision eq claim.revision) and
                (Connections.refreshToken eq claim.token)
        }) {
            it[refreshing] = false
            it[leaseUntil] = null
            it[refreshToken] = null
        }
    }

    private suspend fun activeConnection(id: UInt): ResultRow? = Connections.selectAll()
        .where { (Connections.id eq id) and Connections.active() }.toList().singleOrNull()

    private suspend fun linksInTransaction(contractId: UInt): Pair<ResultRow?, List<ResultRow>>? {
        val exists = ContractService.Contracts.select(ContractService.Contracts.id).where {
            (ContractService.Contracts.id eq contractId) and ContractService.Contracts.active()
        }.toList().isNotEmpty()
        if (!exists) return null
        val links = Links.selectAll().where { Links.contractId eq contractId }.toList()
        val connection = links.firstOrNull()?.get(Links.connectionId)?.value?.let { activeConnection(it) }
        return connection to links
    }

    private suspend fun cachedLinkedApis(
        connection: ResultRow?,
        links: List<ResultRow>,
    ): Map<String, ToadieEntitySnapshot> {
        if (links.isEmpty()) return emptyMap()
        val connectionId = links.first()[Links.connectionId].value
        var predicate: Op<Boolean> = (SnapshotEntities.connectionId eq connectionId) and
            (SnapshotEntities.entityId inList links.map { it[Links.apiEntityId] })
        connection?.let {
            predicate = predicate and
                (SnapshotEntities.blueprint.lowerCase() eq it[Connections.apiBlueprint].lowercase())
        }
        return SnapshotEntities.selectAll().where { predicate }.map { it.toSnapshot() }.toList()
            .associateBy { it.id }
    }

    private fun projectLinks(
        connection: ResultRow?,
        links: List<ResultRow>,
        cachedApis: Map<String, ToadieEntitySnapshot>,
    ) = links.map { it.toLinkResponse(connection, cachedApis) }

    private fun ResultRow.toResponse(): ToadieConnectionResponse {
        val status = cacheStatus()
        return ToadieConnectionResponse(
            this[Connections.id].value, this[Connections.name], this[Connections.baseUrl], this[Connections.browserUrl],
            this[Connections.enabled], this[Connections.refreshIntervalMinutes], mapping(), this[Connections.apiKey].isNotEmpty(),
            this[Connections.createdAt], this[Connections.updatedAt], status.lastAttemptAt, status.lastSuccessAt,
            status.refreshing, status.lastErrorCode, status.state == ToadieCacheState.STALE,
        )
    }

    private fun ResultRow.mapping() = ToadieMapping(
        this[Connections.serviceBlueprint], this[Connections.apiBlueprint], this[Connections.providesRelation],
        this[Connections.consumesRelation], this[Connections.systemRelation],
    )

    private fun UpdateBuilder<*>.applyMapping(mapping: ToadieMapping) {
        this[Connections.serviceBlueprint] = mapping.serviceBlueprint
        this[Connections.apiBlueprint] = mapping.apiBlueprint
        this[Connections.providesRelation] = mapping.providesRelation
        this[Connections.consumesRelation] = mapping.consumesRelation
        this[Connections.systemRelation] = mapping.systemRelation
    }

    private fun ResultRow.cacheStatus(now: Long = nowMillis()): ToadieCacheStatus = toadieCacheStatus(now)

    private fun ResultRow.toRef() = ToadieConnectionRef(
        this[Connections.id].value, this[Connections.name], this[Connections.browserUrl],
    )
    private fun ResultRow.toEntityRef(browserUrl: String) = ToadieEntityRef(
        this[SnapshotEntities.entityId], this[SnapshotEntities.identifier], this[SnapshotEntities.title],
        entityUrl(browserUrl, this[SnapshotEntities.entityId]),
    )
    private fun ResultRow.toSnapshot() = ToadieEntitySnapshot(
        this[SnapshotEntities.entityId], this[SnapshotEntities.blueprint],
        this[SnapshotEntities.identifier], this[SnapshotEntities.title],
        json.decodeFromString(this[SnapshotEntities.teamIdentifiers]), json.decodeFromString(this[SnapshotEntities.relations]),
        this[SnapshotEntities.remoteUpdatedAt],
    )

    private fun ResultRow.toLinkResponse(
        connection: ResultRow?,
        cachedApis: Map<String, ToadieEntitySnapshot>,
    ): ToadieLinkResponse {
        val current = cachedApis[this[Links.apiEntityId]]
        return ToadieLinkResponse(
            id = this[Links.id].value,
            connectionId = this[Links.connectionId].value,
            apiEntityId = this[Links.apiEntityId],
            identifier = current?.identifier ?: this[Links.identifier],
            title = current?.title ?: this[Links.title],
            url = connection?.let { entityUrl(it[Connections.browserUrl], this[Links.apiEntityId]) } ?: this[Links.url],
            status = when {
                connection == null -> ToadieLinkStatus.DISCONNECTED
                current == null -> ToadieLinkStatus.MISSING
                else -> ToadieLinkStatus.AVAILABLE
            },
        )
    }
}

private fun ToadieEntitySnapshot.toRef(browserUrl: String) =
    ToadieEntityRef(id, identifier, title, entityUrl(browserUrl, id))
private fun entityUrl(browserUrl: String, entityId: String) = "${browserUrl.trimEnd('/')}/entities/$entityId/edit"
private fun unlinkedStatus() = ToadieCacheStatus(ToadieCacheState.UNLINKED, null, null, false, null)
private fun disconnectedStatus() = ToadieCacheStatus(ToadieCacheState.DISCONNECTED, null, null, false, null)
private fun sanitizeErrorCode(code: String): String =
    code.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(100).ifBlank { "REFRESH_FAILED" }
private fun foldSearch(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace("ł", "l").replace("ß", "ss").replace("æ", "ae").replace("ø", "o")
