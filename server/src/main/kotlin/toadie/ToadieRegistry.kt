package ch.nokillswit.toadie

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.domains.DomainService
import ch.nokillswit.domains.MAX_DOMAIN_DESCRIPTION_LENGTH
import ch.nokillswit.domains.MAX_DOMAIN_NAME_LENGTH
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.MAX_SYSTEM_DESCRIPTION_LENGTH
import ch.nokillswit.systems.MAX_SYSTEM_NAME_LENGTH
import ch.nokillswit.systems.SystemService
import ch.nokillswit.teams.MAX_TEAM_DESCRIPTION_LENGTH
import ch.nokillswit.teams.MAX_TEAM_NAME_LENGTH
import ch.nokillswit.teams.TeamService
import io.ktor.server.plugins.BadRequestException
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.security.MessageDigest

private const val MAX_REGISTRY_SELECTIONS = 50
private const val MAX_REGISTRY_BINDINGS = 5_000
private const val REGISTRY_LOCK_KEY = 0x434F_5645_4E41_4E54L
private val registryJson = Json { encodeDefaults = true; explicitNulls = true }

internal object DomainToadieSources : Table("domain_toadie_sources") {
    val localId = reference("domain_id", DomainService.Domains)
    val connectionId = reference("connection_id", ToadieService.Connections)
    val entityId = varchar("entity_id", 100)
    val identifier = varchar("identifier", 200)
    val title = varchar("title", 200)
    val url = varchar("url", MAX_TOADIE_URL_LENGTH).nullable()
    val missing = bool("missing")
    val conflictCode = varchar("conflict_code", 100).nullable()
    val lastSyncedAt = long("last_synced_at").nullable()
    val descriptionSynced = bool("description_synced")
    override val primaryKey = PrimaryKey(localId)
}

internal object SystemToadieSources : Table("system_toadie_sources") {
    val localId = reference("system_id", SystemService.Systems)
    val connectionId = reference("connection_id", ToadieService.Connections)
    val entityId = varchar("entity_id", 100)
    val identifier = varchar("identifier", 200)
    val title = varchar("title", 200)
    val url = varchar("url", MAX_TOADIE_URL_LENGTH).nullable()
    val missing = bool("missing")
    val conflictCode = varchar("conflict_code", 100).nullable()
    val lastSyncedAt = long("last_synced_at").nullable()
    val descriptionSynced = bool("description_synced")
    val fallbackDomainId = reference("fallback_domain_id", DomainService.Domains).nullable()
    override val primaryKey = PrimaryKey(localId)
}

internal object TeamToadieSources : Table("team_toadie_sources") {
    val localId = reference("team_id", TeamService.Teams)
    val connectionId = reference("connection_id", ToadieService.Connections)
    val entityId = varchar("entity_id", 100)
    val identifier = varchar("identifier", 200)
    val title = varchar("title", 200)
    val url = varchar("url", MAX_TOADIE_URL_LENGTH).nullable()
    val missing = bool("missing")
    val conflictCode = varchar("conflict_code", 100).nullable()
    val lastSyncedAt = long("last_synced_at").nullable()
    val descriptionSynced = bool("description_synced")
    override val primaryKey = PrimaryKey(localId)
}

internal suspend fun R2dbcTransaction.acquireToadieRegistryLock() {
    exec("SELECT pg_advisory_xact_lock($REGISTRY_LOCK_KEY)")
}

internal suspend fun registrySources(
    kind: ToadieRegistryKind,
    localIds: Collection<UInt>,
): Map<UInt, ToadieRegistrySource> {
    if (localIds.isEmpty()) return emptyMap()
    val records = when (kind) {
        ToadieRegistryKind.DOMAIN -> DomainToadieSources.selectAll()
            .where { DomainToadieSources.localId inList localIds }.map {
                SourceRecord(
                    it[DomainToadieSources.localId].value, it[DomainToadieSources.connectionId].value,
                    it[DomainToadieSources.entityId], it[DomainToadieSources.identifier], it[DomainToadieSources.title],
                    it[DomainToadieSources.url], it[DomainToadieSources.missing], it[DomainToadieSources.conflictCode],
                    it[DomainToadieSources.lastSyncedAt], it[DomainToadieSources.descriptionSynced], null,
                )
            }.toList()
        ToadieRegistryKind.SYSTEM -> SystemToadieSources.selectAll()
            .where { SystemToadieSources.localId inList localIds }.map {
                SourceRecord(
                    it[SystemToadieSources.localId].value, it[SystemToadieSources.connectionId].value,
                    it[SystemToadieSources.entityId], it[SystemToadieSources.identifier], it[SystemToadieSources.title],
                    it[SystemToadieSources.url], it[SystemToadieSources.missing], it[SystemToadieSources.conflictCode],
                    it[SystemToadieSources.lastSyncedAt], it[SystemToadieSources.descriptionSynced],
                    it[SystemToadieSources.fallbackDomainId]?.value,
                )
            }.toList()
        ToadieRegistryKind.TEAM -> TeamToadieSources.selectAll()
            .where { TeamToadieSources.localId inList localIds }.map {
                SourceRecord(
                    it[TeamToadieSources.localId].value, it[TeamToadieSources.connectionId].value,
                    it[TeamToadieSources.entityId], it[TeamToadieSources.identifier], it[TeamToadieSources.title],
                    it[TeamToadieSources.url], it[TeamToadieSources.missing], it[TeamToadieSources.conflictCode],
                    it[TeamToadieSources.lastSyncedAt], it[TeamToadieSources.descriptionSynced], null,
                )
            }.toList()
    }
    val connections = ToadieService.Connections.selectAll()
        .where { ToadieService.Connections.id inList records.map { it.connectionId }.distinct() }
        .toList().associateBy { it[ToadieService.Connections.id].value }
    return records.associate { record ->
        val connection = connections[record.connectionId]
        record.localId to ToadieRegistrySource(
            connectionId = record.connectionId,
            connectionName = connection?.get(ToadieService.Connections.name) ?: "Deleted Toadie connection",
            entityId = record.entityId,
            identifier = record.identifier,
            title = record.title,
            url = connection?.takeUnless { it[ToadieService.Connections.markedAsDeleted] }
                ?.let { registryEntityUrl(it[ToadieService.Connections.browserUrl], record.entityId) } ?: record.url,
            status = when {
                connection == null || connection[ToadieService.Connections.markedAsDeleted] -> ToadieRegistrySourceStatus.DISCONNECTED
                record.conflictCode != null -> ToadieRegistrySourceStatus.CONFLICT
                record.missing -> ToadieRegistrySourceStatus.MISSING
                else -> ToadieRegistrySourceStatus.AVAILABLE
            },
            lastSyncedAt = record.lastSyncedAt,
            lastErrorCode = record.conflictCode,
            descriptionSynced = record.descriptionSynced,
            fallbackDomainId = record.fallbackDomainId,
            cache = connection?.let {
                val cache = it.toadieCacheStatus()
                if (!it[ToadieService.Connections.markedAsDeleted] && it.registryMapping() == null) {
                    cache.copy(state = ToadieCacheState.DISABLED)
                } else cache
            } ?: registryDisconnectedStatus(),
        )
    }
}

internal suspend fun requireRegistryFieldsMutable(
    kind: ToadieRegistryKind,
    localId: UInt,
    name: String,
    description: String?,
    domainId: UInt? = null,
) {
    val source = sourceRow(kind, localId) ?: return
    val local = localState(kind, localId) ?: return
    val nameChanged = name != local.name
    val ownedDescriptionChanged = sourceDescriptionSynced(kind, source) && description != local.description
    val placementChanged = kind == ToadieRegistryKind.SYSTEM && domainId != local.domainId
    if (nameChanged || ownedDescriptionChanged || placementChanged) {
        throw ConflictException("Linked Toadie metadata is read-only — detach the source first")
    }
}

internal suspend fun deleteRegistrySource(kind: ToadieRegistryKind, localId: UInt) {
    when (kind) {
        ToadieRegistryKind.DOMAIN -> DomainToadieSources.deleteWhere { DomainToadieSources.localId eq localId }
        ToadieRegistryKind.SYSTEM -> SystemToadieSources.deleteWhere { SystemToadieSources.localId eq localId }
        ToadieRegistryKind.TEAM -> TeamToadieSources.deleteWhere { TeamToadieSources.localId eq localId }
    }
}

private data class SourceRecord(
    val localId: UInt,
    val connectionId: UInt,
    val entityId: String,
    val identifier: String,
    val title: String,
    val url: String?,
    val missing: Boolean,
    val conflictCode: String?,
    val lastSyncedAt: Long?,
    val descriptionSynced: Boolean,
    val fallbackDomainId: UInt?,
)

internal data class RegistryAuditFact(
    val connectionId: UInt,
    val kind: ToadieRegistryKind,
    val localId: UInt,
    val entityId: String,
    val status: String,
)

private data class RegistryPlan(
    val connectionId: UInt,
    val configRevision: Long,
    val remoteRevision: Long,
    val kind: ToadieRegistryKind,
    val selections: List<ToadieRegistrySelection>,
    val items: List<ToadieRegistryPreviewItem>,
    val cache: ToadieCacheStatus,
    val bindings: List<Binding>,
)

class ToadieRegistryService(private val database: R2dbcDatabase) {
    private val domains = DomainService(database)

    suspend fun candidates(
        connectionId: UInt,
        kind: ToadieRegistryKind,
        query: String?,
        paging: PageRequest,
    ): ToadieRegistryCandidatePageResponse? = suspendTransaction(
        database,
        transactionIsolation = IsolationLevel.REPEATABLE_READ,
        readOnly = true,
    ) {
        val connection = activeConnection(connectionId) ?: return@suspendTransaction null
        val mapping = connection.registryMapping()
            ?: throw ConflictException("Registry synchronization is not configured for this connection")
        if (connection[ToadieService.Connections.remoteRevision] == null ||
            connection[ToadieService.Connections.snapshotSystemBlueprint] == null ||
            connection[ToadieService.Connections.lastSuccessAt] == null
        ) throw ConflictException("The connection has no complete registry snapshot")
        val blueprint = connection.registryBlueprint(kind, mapping)
            ?: throw ConflictException("The connection has no complete registry snapshot")
        var predicate: Op<Boolean> = (ToadieService.SnapshotEntities.connectionId eq connectionId) and
            (ToadieService.SnapshotEntities.blueprint.lowerCase() eq blueprint.lowercase())
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            predicate = predicate and (
                ToadieService.SnapshotEntities.identifier.containsNormalized(q) or
                    ToadieService.SnapshotEntities.title.containsNormalized(q)
                )
        }
        val total = ToadieService.SnapshotEntities.selectAll().where { predicate }.count()
        val columns = mapOf<String, Column<*>>(
            "id" to ToadieService.SnapshotEntities.entityId,
            "title" to ToadieService.SnapshotEntities.title,
        )
        val entities = ToadieService.SnapshotEntities.selectAll().where { predicate }
            .applyPaging(paging, columns).map { it.registrySnapshot() }.toList()
        val bindings = bindingsByEntity(kind, connectionId)
        val localNames = localNames(kind, bindings.values.map { it.localId })
        val parentIds = entities.mapNotNull { it.parentIdentifier(kind, mapping) }.toSet()
        val parentBlueprint = if (kind == ToadieRegistryKind.TEAM) "_team" else mapping.domainBlueprint
        val parentEntities = if (parentIds.isEmpty()) emptyMap() else ToadieService.SnapshotEntities.selectAll().where {
            (ToadieService.SnapshotEntities.connectionId eq connectionId) and
                (ToadieService.SnapshotEntities.blueprint.lowerCase() eq parentBlueprint.lowercase()) and
                (ToadieService.SnapshotEntities.identifier inList parentIds)
        }.map { it.registrySnapshot() }.toList().associateBy { it.identifier }
        ToadieRegistryCandidatePageResponse(
            items = entities.map { entity ->
                val parent = entity.parentIdentifier(kind, mapping)?.let(parentEntities::get)
                val binding = bindings[entity.id]
                ToadieRegistryCandidate(
                    entityId = entity.id,
                    identifier = entity.identifier,
                    title = entity.title,
                    description = entity.registryDescription,
                    remoteUpdatedAt = entity.updatedAt,
                    parentEntityId = parent?.id,
                    parentIdentifier = parent?.identifier,
                    parentTitle = parent?.title,
                    linkedLocalId = binding?.localId,
                    linkedLocalName = binding?.localId?.let(localNames::get),
                    fallbackDomainId = binding?.fallbackDomainId,
                    issues = listOfNotNull(entity.registryErrorCode),
                )
            },
            page = paging.page,
            pageSize = paging.pageSize,
            total = total,
            cache = connection.toadieCacheStatus(),
        )
    }

    suspend fun preview(connectionId: UInt, request: ToadieRegistryPreviewRequest): ToadieRegistryPreviewResponse? =
        suspendTransaction(database, transactionIsolation = IsolationLevel.REPEATABLE_READ, readOnly = true) {
            validateSelections(request.kind, request.items)
            val connection = activeConnection(connectionId) ?: return@suspendTransaction null
            val plan = buildPlan(connection, request.kind, request.items)
            plan.toResponse()
        }

    suspend fun apply(connectionId: UInt, request: ToadieRegistryApplyRequest): ToadieRegistryApplyResponse? =
        suspendTransaction(database) {
            validateSelections(request.kind, request.items)
            if (!request.expectedPlanToken.matches(Regex("^[0-9a-f]{64}$"))) {
                throw BadRequestException("expectedPlanToken must be a 64-character lowercase hexadecimal value")
            }
            val connection = ToadieService.Connections.selectAll().where {
                (ToadieService.Connections.id eq connectionId) and ToadieService.Connections.active()
            }.forUpdate().toList().singleOrNull() ?: return@suspendTransaction null
            acquireToadieRegistryLock()
            val plan = buildPlan(connection, request.kind, request.items)
            if (plan.token() != request.expectedPlanToken) throw ConflictException("Registry sync preview is stale — preview again")
            if (plan.items.any { it.issues.isNotEmpty() }) throw ConflictException("Registry sync plan has conflicts")
            (plan.items.mapNotNull { it.after.domainId } + plan.selections.mapNotNull { it.fallbackDomainId })
                .distinct().forEach { domains.requireActiveForAttach(it) }
            val stamp = nowMillis()
            val applied = plan.items.map { item ->
                val selection = plan.selections.single { it.entityId == item.entityId }
                val localId = when (item.action) {
                    ToadieRegistryAction.IMPORT -> insertLocal(plan.kind, item.after, stamp)
                    ToadieRegistryAction.LINK, ToadieRegistryAction.UPDATE -> checkNotNull(item.localId)
                }
                applyLocal(plan.kind, localId, item.after, stamp)
                val descriptionSynced = sourceRow(plan.kind, localId)?.let {
                    sourceDescriptionSynced(plan.kind, it)
                } == true || descriptionConfigured(connection, plan.kind)
                upsertBinding(
                    plan.kind, localId, connection, item.entityId,
                    selection.fallbackDomainId, descriptionSynced, stamp,
                )
                ToadieRegistryApplyItem(item.entityId, localId, item.action)
            }
            ToadieRegistryApplyResponse(plan.kind, applied)
        }

    suspend fun detach(kind: ToadieRegistryKind, localId: UInt): Boolean = suspendTransaction(database) {
        acquireToadieRegistryLock()
        if (localState(kind, localId, lock = true) == null) return@suspendTransaction false
        deleteRegistrySource(kind, localId)
        true
    }

    internal suspend fun reconcile(connection: ResultRow): List<RegistryAuditFact> {
        val mapping = connection.registryMapping() ?: return emptyList()
        val audits = mutableListOf<RegistryAuditFact>()
        ToadieRegistryKind.entries.forEach { kind -> reconcileKind(connection, mapping, kind, audits) }
        return audits
    }

    internal fun auditReconciliations(facts: List<RegistryAuditFact>) {
        facts.forEach { fact ->
            audit(
                "toadie_registry.reconciled",
                "connectionId" to fact.connectionId.toLong(),
                "kind" to fact.kind.name,
                "localId" to fact.localId.toLong(),
                "entityId" to fact.entityId,
                "status" to fact.status,
            )
        }
    }

    private suspend fun reconcileKind(
        connection: ResultRow,
        mapping: ToadieRegistryMapping,
        kind: ToadieRegistryKind,
        audits: MutableList<RegistryAuditFact>,
    ) {
        val connectionId = connection[ToadieService.Connections.id].value
        val bindings = bindingsByEntity(kind, connectionId).values.sortedBy { it.localId }
        if (bindings.isEmpty()) return
        val entities = ToadieService.SnapshotEntities.selectAll().where {
            ToadieService.SnapshotEntities.connectionId eq connectionId
        }.map { it.registrySnapshot() }.toList()
        val entitiesById = entities.associateBy { it.id }
        val localStates = localStates(kind, bindings.map { it.localId }, lock = true)
        val domainEntities = entities.filter { it.blueprint.equals(mapping.domainBlueprint, true) }
            .associateBy { it.identifier }
        val domainBindings = bindingsByEntity(ToadieRegistryKind.DOMAIN, connectionId)
        val activeDomainIds = DomainService.Domains.select(DomainService.Domains.id)
            .where { DomainService.Domains.active() }.map { it[DomainService.Domains.id].value }.toList().toSet()
        val context = ReconcileContext(entitiesById, localStates, domainEntities, domainBindings, activeDomainIds)
        for (binding in bindings) reconcileBinding(connection, mapping, kind, binding, context, audits)
    }

    private suspend fun reconcileBinding(
        connection: ResultRow,
        mapping: ToadieRegistryMapping,
        kind: ToadieRegistryKind,
        binding: Binding,
        context: ReconcileContext,
        audits: MutableList<RegistryAuditFact>,
    ) {
        val entity = context.entitiesById[binding.entityId]
        val expectedBlueprint = connection.registryBlueprint(kind, mapping)
        if (entity == null || !entity.blueprint.equals(expectedBlueprint, ignoreCase = true)) {
            if (markBinding(kind, binding.localId, missing = true, conflict = null)) {
                audits += reconciliationFact(connection, kind, binding, "MISSING")
            }
            return
        }
        val before = context.localStates[binding.localId] ?: return
        val issues = mutableListOf<String>()
        entity.registryErrorCode?.let(issues::add)
        val domainId = reconcileDomainId(kind, mapping, binding, entity, context, issues)
        val after = ToadieRegistryLocalState(
            entity.title,
            if (descriptionConfigured(connection, kind)) entity.registryDescription else before.description,
            domainId,
        )
        validateLocal(kind, after, issues)
        val rawPlan = ToadieRegistryPreviewItem(
            entity.id, binding.localId, ToadieRegistryAction.UPDATE, before, after, issues.distinct(),
        )
        val plan = addNamespaceConflicts(kind, listOf(rawPlan)).single()
        if (plan.issues.isNotEmpty()) {
            val sourceChanged = markBinding(
                kind, binding.localId, missing = false, conflict = plan.issues.first(),
                entity = entity, browserUrl = connection[ToadieService.Connections.browserUrl],
            )
            if (sourceChanged) audits += reconciliationFact(connection, kind, binding, "CONFLICT")
            return
        }
        val changed = before != plan.after
        val stamp = nowMillis()
        if (changed) applyLocal(kind, binding.localId, plan.after, stamp)
        val sourceChanged = updateBindingMetadata(
            kind, binding.localId, connection, entity, null, stamp, changed,
            descriptionConfigured(connection, kind),
        )
        if (changed || sourceChanged) audits += reconciliationFact(connection, kind, binding, "AVAILABLE")
    }

    private fun reconcileDomainId(
        kind: ToadieRegistryKind,
        mapping: ToadieRegistryMapping,
        binding: Binding,
        entity: ToadieEntitySnapshot,
        context: ReconcileContext,
        issues: MutableList<String>,
    ): UInt? {
        if (kind != ToadieRegistryKind.SYSTEM) return null
        val remoteDomain = mapping.systemDomainRelation?.let { entity.relations[it].orEmpty().singleOrNull() }
        if (remoteDomain == null) {
            return binding.fallbackDomainId?.takeIf { it in context.activeDomainIds }
                ?: run { issues += "FALLBACK_DOMAIN_REQUIRED"; null }
        }
        return context.domainEntities[remoteDomain]?.let { context.domainBindings[it.id]?.localId }
            ?.takeIf { it in context.activeDomainIds }
            ?: run { issues += "DOMAIN_UNMAPPED"; null }
    }

    private suspend fun buildPlan(
        connection: ResultRow,
        kind: ToadieRegistryKind,
        rawSelections: List<ToadieRegistrySelection>,
        requireCurrent: Boolean = true,
    ): RegistryPlan {
        val mapping = connection.registryMapping()
            ?: throw ConflictException("Registry synchronization is not configured for this connection")
        val cache = connection.toadieCacheStatus()
        if (requireCurrent && cache.state != ToadieCacheState.CURRENT) {
            throw ConflictException("Registry cache is not current — refresh the connection first")
        }
        val remoteRevision = connection[ToadieService.Connections.remoteRevision]
            ?: throw ConflictException("The connection has no complete registry snapshot")
        val connectionId = connection[ToadieService.Connections.id].value
        val selections = rawSelections.sortedBy { it.entityId }
        val existingBindings = bindingsByEntity(kind, connectionId)
        val selectedLocalIds = (selections.mapNotNull { it.localId } +
            selections.mapNotNull { existingBindings[it.entityId]?.localId }).toSet()
        val states = localStates(kind, selectedLocalIds, lock = !requireCurrent)
        val boundByLocal = bindingsByLocal(kind, selectedLocalIds)
        val relevantBindings = (
            selections.mapNotNull { existingBindings[it.entityId] } +
                selections.mapNotNull { it.localId?.let(boundByLocal::get) }
            ).distinctBy { Triple(it.connectionId, it.entityId, it.localId) }
            .sortedWith(compareBy<Binding> { it.connectionId }.thenBy { it.entityId }.thenBy { it.localId })
        val entities = snapshots(connectionId, selections.map { it.entityId }).associateBy { it.id }
        val currentBindingCount = bindingCount(connectionId)
        val newBindingCount = selections.count { existingBindings[it.entityId] == null }
        val preliminary = selections.map { selection ->
            val entity = entities[selection.entityId]
            val currentBinding = existingBindings[selection.entityId]
            val effectiveLocalId = currentBinding?.localId ?: selection.localId
            val before = effectiveLocalId?.let(states::get)
            val issues = mutableListOf<String>()
            if (entity == null || !entity.blueprint.equals(connection.registryBlueprint(kind, mapping), true)) {
                issues += "SOURCE_MISSING"
            }
            if (currentBinding != null && selection.localId != currentBinding.localId) issues += "REMOTE_ALREADY_LINKED"
            if (selection.localId != null && before == null) issues += "LOCAL_NOT_FOUND"
            boundByLocal[selection.localId]?.takeIf {
                it.connectionId != connectionId || it.entityId != selection.entityId
            }?.let { issues += "LOCAL_ALREADY_LINKED" }
            if (currentBindingCount + newBindingCount > MAX_REGISTRY_BINDINGS) issues += "SOURCE_LIMIT_EXCEEDED"
            entity?.registryErrorCode?.let(issues::add)
            val domainId = if (kind == ToadieRegistryKind.SYSTEM && entity != null) {
                resolveDomain(connectionId, entity, selection.fallbackDomainId, mapping, issues)
            } else null
            val description = if (descriptionConfigured(connection, kind)) entity?.registryDescription else before?.description
            val after = ToadieRegistryLocalState(entity?.title.orEmpty(), description, domainId)
            validateLocal(kind, after, issues)
            ToadieRegistryPreviewItem(
                entityId = selection.entityId,
                localId = effectiveLocalId,
                action = when {
                    currentBinding != null -> ToadieRegistryAction.UPDATE
                    selection.localId != null -> ToadieRegistryAction.LINK
                    else -> ToadieRegistryAction.IMPORT
                },
                before = before,
                after = after,
                issues = issues.distinct(),
            )
        }
        val items = addNamespaceConflicts(kind, preliminary)
        return RegistryPlan(
            connectionId, connection[ToadieService.Connections.configRevision], remoteRevision,
            kind, selections, items, cache, relevantBindings,
        )
    }

    private suspend fun resolveDomain(
        connectionId: UInt,
        entity: ToadieEntitySnapshot,
        fallbackDomainId: UInt?,
        mapping: ToadieRegistryMapping,
        issues: MutableList<String>,
    ): UInt? {
        if (fallbackDomainId != null && localState(ToadieRegistryKind.DOMAIN, fallbackDomainId) == null) {
            issues += "DOMAIN_NOT_FOUND"
        }
        val remoteDomain = mapping.systemDomainRelation?.let { entity.relations[it].orEmpty().singleOrNull() }
        if (remoteDomain != null) {
            val domainEntity = ToadieService.SnapshotEntities.selectAll().where {
                (ToadieService.SnapshotEntities.connectionId eq connectionId) and
                    (ToadieService.SnapshotEntities.blueprint.lowerCase() eq mapping.domainBlueprint.lowercase()) and
                    (ToadieService.SnapshotEntities.identifier eq remoteDomain)
            }.toList().singleOrNull()
            val binding = domainEntity?.get(ToadieService.SnapshotEntities.entityId)?.let { entityId ->
                DomainToadieSources.selectAll().where {
                    (DomainToadieSources.connectionId eq connectionId) and (DomainToadieSources.entityId eq entityId)
                }.toList().singleOrNull()
            }
            val localId = binding?.get(DomainToadieSources.localId)?.value
            if (localId == null || localState(ToadieRegistryKind.DOMAIN, localId) == null) issues += "DOMAIN_UNMAPPED"
            return localId
        }
        if (fallbackDomainId == null) {
            issues += "FALLBACK_DOMAIN_REQUIRED"
            return null
        }
        return fallbackDomainId
    }

    private suspend fun addNamespaceConflicts(
        kind: ToadieRegistryKind,
        items: List<ToadieRegistryPreviewItem>,
    ): List<ToadieRegistryPreviewItem> {
        val duplicates = items.groupBy {
            if (kind == ToadieRegistryKind.SYSTEM) it.after.domainId to it.after.name.lowercase()
            else null to it.after.name.lowercase()
        }.filterValues { it.size > 1 }.keys
        return items.map { item ->
            val key = if (kind == ToadieRegistryKind.SYSTEM) item.after.domainId to item.after.name.lowercase()
            else null to item.after.name.lowercase()
            val exists = when (kind) {
                ToadieRegistryKind.DOMAIN -> DomainService.Domains.selectAll().where {
                    DomainService.Domains.active() and
                        (DomainService.Domains.name.lowerCase() eq item.after.name.lowercase()) and
                        (item.localId?.let { DomainService.Domains.id neq it } ?: Op.TRUE)
                }.count() > 0
                ToadieRegistryKind.SYSTEM -> item.after.domainId?.let { domainId ->
                    SystemService.Systems.selectAll().where {
                        SystemService.Systems.active() and (SystemService.Systems.domainId eq domainId) and
                            (SystemService.Systems.name.lowerCase() eq item.after.name.lowercase()) and
                            (item.localId?.let { SystemService.Systems.id neq it } ?: Op.TRUE)
                    }.count() > 0
                } ?: false
                ToadieRegistryKind.TEAM -> TeamService.Teams.selectAll().where {
                    TeamService.Teams.active() and
                        (TeamService.Teams.name.lowerCase() eq item.after.name.lowercase()) and
                        (item.localId?.let { TeamService.Teams.id neq it } ?: Op.TRUE)
                }.count() > 0
            }
            item.copy(issues = (item.issues + listOfNotNull("NAME_CONFLICT".takeIf { exists || key in duplicates })).distinct())
        }
    }

    private fun validateSelections(kind: ToadieRegistryKind, items: List<ToadieRegistrySelection>) {
        if (items.isEmpty() || items.size > MAX_REGISTRY_SELECTIONS) {
            throw BadRequestException("items must contain between 1 and $MAX_REGISTRY_SELECTIONS selections")
        }
        if (items.map { it.entityId }.distinct().size != items.size ||
            items.mapNotNull { it.localId }.distinct().size != items.count { it.localId != null }
        ) throw BadRequestException("entityId and supplied localId values must be unique")
        if (items.any { selection ->
                selection.entityId.toULongOrNull()?.toString() != selection.entityId || selection.entityId == "0"
            }
        ) throw BadRequestException("entityId must be a canonical positive decimal string")
        if (kind != ToadieRegistryKind.SYSTEM && items.any { it.fallbackDomainId != null }) {
            throw BadRequestException("fallbackDomainId is supported only for SYSTEM selections")
        }
    }

    private fun validateLocal(kind: ToadieRegistryKind, state: ToadieRegistryLocalState, issues: MutableList<String>) {
        val maxName = when (kind) {
            ToadieRegistryKind.DOMAIN -> MAX_DOMAIN_NAME_LENGTH
            ToadieRegistryKind.SYSTEM -> MAX_SYSTEM_NAME_LENGTH
            ToadieRegistryKind.TEAM -> MAX_TEAM_NAME_LENGTH
        }
        val maxDescription = when (kind) {
            ToadieRegistryKind.DOMAIN -> MAX_DOMAIN_DESCRIPTION_LENGTH
            ToadieRegistryKind.SYSTEM -> MAX_SYSTEM_DESCRIPTION_LENGTH
            ToadieRegistryKind.TEAM -> MAX_TEAM_DESCRIPTION_LENGTH
        }
        if (state.name.isBlank() || state.name.length > maxName || state.name.any(Char::isISOControl)) issues += "INVALID_NAME"
        if ((state.description?.length ?: 0) > maxDescription || state.description?.any(Char::isISOControl) == true) {
            issues += "INVALID_DESCRIPTION"
        }
    }

    private suspend fun insertLocal(kind: ToadieRegistryKind, state: ToadieRegistryLocalState, stamp: Long): UInt = when (kind) {
        ToadieRegistryKind.DOMAIN -> DomainService.Domains.insert {
            it[name] = state.name; it[description] = state.description; it[createdAt] = stamp; it[updatedAt] = stamp
        }[DomainService.Domains.id].value
        ToadieRegistryKind.SYSTEM -> SystemService.Systems.insert {
            it[domainId] = checkNotNull(state.domainId); it[name] = state.name; it[description] = state.description
            it[createdAt] = stamp; it[updatedAt] = stamp
        }[SystemService.Systems.id].value
        ToadieRegistryKind.TEAM -> TeamService.Teams.insert {
            it[name] = state.name; it[description] = state.description; it[createdAt] = stamp; it[updatedAt] = stamp
        }[TeamService.Teams.id].value
    }

    private suspend fun applyLocal(kind: ToadieRegistryKind, localId: UInt, state: ToadieRegistryLocalState, stamp: Long) {
        when (kind) {
            ToadieRegistryKind.DOMAIN -> DomainService.Domains.update({ DomainService.Domains.id eq localId }) {
                it[name] = state.name; it[description] = state.description; it[updatedAt] = stamp
            }
            ToadieRegistryKind.SYSTEM -> SystemService.Systems.update({ SystemService.Systems.id eq localId }) {
                it[domainId] = checkNotNull(state.domainId); it[name] = state.name
                it[description] = state.description; it[updatedAt] = stamp
            }
            ToadieRegistryKind.TEAM -> TeamService.Teams.update({ TeamService.Teams.id eq localId }) {
                it[name] = state.name; it[description] = state.description; it[updatedAt] = stamp
            }
        }
    }

    private suspend fun upsertBinding(
        kind: ToadieRegistryKind,
        localId: UInt,
        connection: ResultRow,
        entityId: String,
        fallbackDomainId: UInt?,
        descriptionSynced: Boolean,
        stamp: Long,
    ) {
        deleteRegistrySource(kind, localId)
        val entity = checkNotNull(snapshot(connection[ToadieService.Connections.id].value, entityId))
        val url = registryEntityUrl(connection[ToadieService.Connections.browserUrl], entityId)
        when (kind) {
            ToadieRegistryKind.DOMAIN -> DomainToadieSources.insert {
                it[DomainToadieSources.localId] = localId; it[connectionId] = connection[ToadieService.Connections.id]
                it[DomainToadieSources.entityId] = entityId; it[identifier] = entity.identifier; it[title] = entity.title
                it[DomainToadieSources.url] = url; it[missing] = false; it[conflictCode] = null
                it[lastSyncedAt] = stamp; it[DomainToadieSources.descriptionSynced] = descriptionSynced
            }
            ToadieRegistryKind.SYSTEM -> SystemToadieSources.insert {
                it[SystemToadieSources.localId] = localId; it[connectionId] = connection[ToadieService.Connections.id]
                it[SystemToadieSources.entityId] = entityId; it[identifier] = entity.identifier; it[title] = entity.title
                it[SystemToadieSources.url] = url; it[missing] = false; it[conflictCode] = null
                it[lastSyncedAt] = stamp; it[SystemToadieSources.descriptionSynced] = descriptionSynced
                it[SystemToadieSources.fallbackDomainId] = fallbackDomainId
            }
            ToadieRegistryKind.TEAM -> TeamToadieSources.insert {
                it[TeamToadieSources.localId] = localId; it[connectionId] = connection[ToadieService.Connections.id]
                it[TeamToadieSources.entityId] = entityId; it[identifier] = entity.identifier; it[title] = entity.title
                it[TeamToadieSources.url] = url; it[missing] = false; it[conflictCode] = null
                it[lastSyncedAt] = stamp; it[TeamToadieSources.descriptionSynced] = descriptionSynced
            }
        }
    }

    private suspend fun updateBindingMetadata(
        kind: ToadieRegistryKind,
        localId: UInt,
        connection: ResultRow,
        entity: ToadieEntitySnapshot,
        conflict: String?,
        stamp: Long,
        localChanged: Boolean,
        descriptionConfigured: Boolean,
    ): Boolean {
        val url = registryEntityUrl(connection[ToadieService.Connections.browserUrl], entity.id)
        val previous = bindingStatus(kind, localId) ?: return false
        val changed = previous.identifier != entity.identifier || previous.title != entity.title ||
            previous.url != url || previous.missing || previous.conflictCode != conflict || localChanged ||
            (descriptionConfigured && !previous.descriptionSynced)
        if (!changed) return false
        when (kind) {
            ToadieRegistryKind.DOMAIN -> DomainToadieSources.update({ DomainToadieSources.localId eq localId }) {
                it[identifier] = entity.identifier; it[title] = entity.title; it[DomainToadieSources.url] = url
                it[missing] = false; it[conflictCode] = conflict
                if (descriptionConfigured) it[descriptionSynced] = true
                it[lastSyncedAt] = stamp
            }
            ToadieRegistryKind.SYSTEM -> SystemToadieSources.update({ SystemToadieSources.localId eq localId }) {
                it[identifier] = entity.identifier; it[title] = entity.title; it[SystemToadieSources.url] = url
                it[missing] = false; it[conflictCode] = conflict
                if (descriptionConfigured) it[descriptionSynced] = true
                it[lastSyncedAt] = stamp
            }
            ToadieRegistryKind.TEAM -> TeamToadieSources.update({ TeamToadieSources.localId eq localId }) {
                it[identifier] = entity.identifier; it[title] = entity.title; it[TeamToadieSources.url] = url
                it[missing] = false; it[conflictCode] = conflict
                if (descriptionConfigured) it[descriptionSynced] = true
                it[lastSyncedAt] = stamp
            }
        }
        return true
    }

    private suspend fun markBinding(
        kind: ToadieRegistryKind,
        localId: UInt,
        missing: Boolean,
        conflict: String?,
        entity: ToadieEntitySnapshot? = null,
        browserUrl: String? = null,
    ): Boolean {
        val previous = bindingStatus(kind, localId) ?: return false
        val url = entity?.let { browserUrl?.let { base -> registryEntityUrl(base, it.id) } }
        val statusUnchanged = previous.missing == missing && previous.conflictCode == conflict
        val metadataUnchanged = entity == null ||
            (previous.identifier == entity.identifier && previous.title == entity.title && previous.url == url)
        if (statusUnchanged && metadataUnchanged) return false
        when (kind) {
            ToadieRegistryKind.DOMAIN -> DomainToadieSources.update({ DomainToadieSources.localId eq localId }) {
                it[DomainToadieSources.missing] = missing; it[conflictCode] = conflict
                entity?.let { source ->
                    it[identifier] = source.identifier; it[title] = source.title; it[DomainToadieSources.url] = url
                }
            }
            ToadieRegistryKind.SYSTEM -> SystemToadieSources.update({ SystemToadieSources.localId eq localId }) {
                it[SystemToadieSources.missing] = missing; it[conflictCode] = conflict
                entity?.let { source ->
                    it[identifier] = source.identifier; it[title] = source.title; it[SystemToadieSources.url] = url
                }
            }
            ToadieRegistryKind.TEAM -> TeamToadieSources.update({ TeamToadieSources.localId eq localId }) {
                it[TeamToadieSources.missing] = missing; it[conflictCode] = conflict
                entity?.let { source ->
                    it[identifier] = source.identifier; it[title] = source.title; it[TeamToadieSources.url] = url
                }
            }
        }
        return true
    }

    private fun reconciliationFact(
        connection: ResultRow,
        kind: ToadieRegistryKind,
        binding: Binding,
        status: String,
    ) = RegistryAuditFact(
        connection[ToadieService.Connections.id].value,
        kind,
        binding.localId,
        binding.entityId,
        status,
    )

    private fun RegistryPlan.token(): String {
        val payload = buildString {
            append(connectionId).append('|').append(configRevision).append('|').append(remoteRevision).append('|').append(kind)
            selections.forEach { append('|').append(registryJson.encodeToString(it)) }
            bindings.forEach { binding ->
                append('|').append(binding.connectionId).append(':').append(binding.entityId)
                    .append(':').append(binding.localId).append(':').append(binding.fallbackDomainId)
                    .append(':').append(binding.descriptionSynced)
            }
            items.forEach { append('|').append(registryJson.encodeToString(it)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun RegistryPlan.toResponse() = ToadieRegistryPreviewResponse(
        kind, cache, token(), items.none { it.issues.isNotEmpty() }, items,
    )
}

private data class Binding(
    val localId: UInt,
    val connectionId: UInt,
    val entityId: String,
    val fallbackDomainId: UInt?,
    val descriptionSynced: Boolean,
)
private data class ReconcileContext(
    val entitiesById: Map<String, ToadieEntitySnapshot>,
    val localStates: Map<UInt, ToadieRegistryLocalState>,
    val domainEntities: Map<String, ToadieEntitySnapshot>,
    val domainBindings: Map<String, Binding>,
    val activeDomainIds: Set<UInt>,
)
private data class BindingStatus(
    val identifier: String,
    val title: String,
    val url: String?,
    val missing: Boolean,
    val conflictCode: String?,
    val descriptionSynced: Boolean,
)

private suspend fun bindingStatus(kind: ToadieRegistryKind, localId: UInt): BindingStatus? {
    val row = sourceRow(kind, localId) ?: return null
    return when (kind) {
        ToadieRegistryKind.DOMAIN -> BindingStatus(
            row[DomainToadieSources.identifier], row[DomainToadieSources.title], row[DomainToadieSources.url],
            row[DomainToadieSources.missing], row[DomainToadieSources.conflictCode],
            row[DomainToadieSources.descriptionSynced],
        )
        ToadieRegistryKind.SYSTEM -> BindingStatus(
            row[SystemToadieSources.identifier], row[SystemToadieSources.title], row[SystemToadieSources.url],
            row[SystemToadieSources.missing], row[SystemToadieSources.conflictCode],
            row[SystemToadieSources.descriptionSynced],
        )
        ToadieRegistryKind.TEAM -> BindingStatus(
            row[TeamToadieSources.identifier], row[TeamToadieSources.title], row[TeamToadieSources.url],
            row[TeamToadieSources.missing], row[TeamToadieSources.conflictCode],
            row[TeamToadieSources.descriptionSynced],
        )
    }
}

private suspend fun bindingsByEntity(kind: ToadieRegistryKind, connectionId: UInt): Map<String, Binding> = when (kind) {
    ToadieRegistryKind.DOMAIN -> DomainToadieSources.selectAll().where { DomainToadieSources.connectionId eq connectionId }
        .map {
            Binding(
                it[DomainToadieSources.localId].value, it[DomainToadieSources.connectionId].value,
                it[DomainToadieSources.entityId], null, it[DomainToadieSources.descriptionSynced],
            )
        }.toList()
    ToadieRegistryKind.SYSTEM -> SystemToadieSources.selectAll().where { SystemToadieSources.connectionId eq connectionId }
        .map {
            Binding(
                it[SystemToadieSources.localId].value, it[SystemToadieSources.connectionId].value,
                it[SystemToadieSources.entityId], it[SystemToadieSources.fallbackDomainId]?.value,
                it[SystemToadieSources.descriptionSynced],
            )
        }.toList()
    ToadieRegistryKind.TEAM -> TeamToadieSources.selectAll().where { TeamToadieSources.connectionId eq connectionId }
        .map {
            Binding(
                it[TeamToadieSources.localId].value, it[TeamToadieSources.connectionId].value,
                it[TeamToadieSources.entityId], null, it[TeamToadieSources.descriptionSynced],
            )
        }.toList()
}.associateBy { it.entityId }

private suspend fun bindingsByLocal(kind: ToadieRegistryKind, ids: Collection<UInt>): Map<UInt, Binding> {
    if (ids.isEmpty()) return emptyMap()
    return when (kind) {
        ToadieRegistryKind.DOMAIN -> DomainToadieSources.selectAll().where { DomainToadieSources.localId inList ids }
            .map {
                Binding(
                    it[DomainToadieSources.localId].value, it[DomainToadieSources.connectionId].value,
                    it[DomainToadieSources.entityId], null, it[DomainToadieSources.descriptionSynced],
                )
            }.toList()
        ToadieRegistryKind.SYSTEM -> SystemToadieSources.selectAll().where { SystemToadieSources.localId inList ids }
            .map {
                Binding(
                    it[SystemToadieSources.localId].value, it[SystemToadieSources.connectionId].value,
                    it[SystemToadieSources.entityId], it[SystemToadieSources.fallbackDomainId]?.value,
                    it[SystemToadieSources.descriptionSynced],
                )
            }.toList()
        ToadieRegistryKind.TEAM -> TeamToadieSources.selectAll().where { TeamToadieSources.localId inList ids }
            .map {
                Binding(
                    it[TeamToadieSources.localId].value, it[TeamToadieSources.connectionId].value,
                    it[TeamToadieSources.entityId], null, it[TeamToadieSources.descriptionSynced],
                )
            }.toList()
    }.associateBy { it.localId }
}

private suspend fun sourceRow(kind: ToadieRegistryKind, localId: UInt): ResultRow? = when (kind) {
    ToadieRegistryKind.DOMAIN -> DomainToadieSources.selectAll().where { DomainToadieSources.localId eq localId }.toList().singleOrNull()
    ToadieRegistryKind.SYSTEM -> SystemToadieSources.selectAll().where { SystemToadieSources.localId eq localId }.toList().singleOrNull()
    ToadieRegistryKind.TEAM -> TeamToadieSources.selectAll().where { TeamToadieSources.localId eq localId }.toList().singleOrNull()
}

private suspend fun localNames(kind: ToadieRegistryKind, ids: Collection<UInt>): Map<UInt, String> =
    localStates(kind, ids).mapValues { it.value.name }

private suspend fun localStates(
    kind: ToadieRegistryKind,
    ids: Collection<UInt>,
    lock: Boolean = false,
): Map<UInt, ToadieRegistryLocalState> {
    if (ids.isEmpty()) return emptyMap()
    val query = when (kind) {
        ToadieRegistryKind.DOMAIN -> DomainService.Domains.selectAll().where {
            (DomainService.Domains.id inList ids) and DomainService.Domains.active()
        }
        ToadieRegistryKind.SYSTEM -> SystemService.Systems.selectAll().where {
            (SystemService.Systems.id inList ids) and SystemService.Systems.active()
        }
        ToadieRegistryKind.TEAM -> TeamService.Teams.selectAll().where {
            (TeamService.Teams.id inList ids) and TeamService.Teams.active()
        }
    }
    val rows = if (lock) query.forUpdate().toList() else query.toList()
    return rows.associate { row -> when (kind) {
        ToadieRegistryKind.DOMAIN -> row[DomainService.Domains.id].value to ToadieRegistryLocalState(
            row[DomainService.Domains.name], row[DomainService.Domains.description], null,
        )
        ToadieRegistryKind.SYSTEM -> row[SystemService.Systems.id].value to ToadieRegistryLocalState(
            row[SystemService.Systems.name], row[SystemService.Systems.description], row[SystemService.Systems.domainId].value,
        )
        ToadieRegistryKind.TEAM -> row[TeamService.Teams.id].value to ToadieRegistryLocalState(
            row[TeamService.Teams.name], row[TeamService.Teams.description], null,
        )
    } }
}

private suspend fun localState(kind: ToadieRegistryKind, id: UInt, lock: Boolean = false) =
    localStates(kind, listOf(id), lock)[id]

private suspend fun bindingCount(connectionId: UInt): Long =
    DomainToadieSources.selectAll().where { DomainToadieSources.connectionId eq connectionId }.count() +
        SystemToadieSources.selectAll().where { SystemToadieSources.connectionId eq connectionId }.count() +
        TeamToadieSources.selectAll().where { TeamToadieSources.connectionId eq connectionId }.count()

private suspend fun activeConnection(id: UInt): ResultRow? = ToadieService.Connections.selectAll().where {
    (ToadieService.Connections.id eq id) and ToadieService.Connections.active()
}.toList().singleOrNull()

private suspend fun snapshots(connectionId: UInt, entityIds: Collection<String>): List<ToadieEntitySnapshot> {
    if (entityIds.isEmpty()) return emptyList()
    return ToadieService.SnapshotEntities.selectAll().where {
        (ToadieService.SnapshotEntities.connectionId eq connectionId) and
            (ToadieService.SnapshotEntities.entityId inList entityIds)
    }.map { it.registrySnapshot() }.toList()
}

private suspend fun snapshot(connectionId: UInt, entityId: String) = snapshots(connectionId, listOf(entityId)).singleOrNull()

private fun ResultRow.registrySnapshot() = ToadieEntitySnapshot(
    id = this[ToadieService.SnapshotEntities.entityId],
    blueprint = this[ToadieService.SnapshotEntities.blueprint],
    identifier = this[ToadieService.SnapshotEntities.identifier],
    title = this[ToadieService.SnapshotEntities.title],
    teamIdentifiers = registryJson.decodeFromString(this[ToadieService.SnapshotEntities.teamIdentifiers]),
    relations = registryJson.decodeFromString(this[ToadieService.SnapshotEntities.relations]),
    updatedAt = this[ToadieService.SnapshotEntities.remoteUpdatedAt],
    registryDescription = this[ToadieService.SnapshotEntities.registryDescription],
    registryErrorCode = this[ToadieService.SnapshotEntities.registryErrorCode],
)

private fun ToadieEntitySnapshot.parentIdentifier(kind: ToadieRegistryKind, mapping: ToadieRegistryMapping): String? =
    when (kind) {
        ToadieRegistryKind.DOMAIN -> mapping.domainParentRelation
        ToadieRegistryKind.SYSTEM -> mapping.systemDomainRelation
        ToadieRegistryKind.TEAM -> "parent"
    }?.let { relations[it].orEmpty().singleOrNull() }

internal fun ResultRow.registryMapping(): ToadieRegistryMapping? {
    val domainBlueprint = this[ToadieService.Connections.registryDomainBlueprint] ?: return null
    return ToadieRegistryMapping(
        domainBlueprint = domainBlueprint,
        systemDomainRelation = this[ToadieService.Connections.registrySystemDomainRelation],
        domainParentRelation = this[ToadieService.Connections.registryDomainParentRelation],
        flattenDomains = this[ToadieService.Connections.registryFlattenDomains] == true,
        domainDescriptionProperty = this[ToadieService.Connections.registryDomainDescriptionProperty],
        systemDescriptionProperty = this[ToadieService.Connections.registrySystemDescriptionProperty],
        teamDescriptionProperty = this[ToadieService.Connections.registryTeamDescriptionProperty],
    )
}

private fun ResultRow.registryBlueprint(kind: ToadieRegistryKind, mapping: ToadieRegistryMapping): String? = when (kind) {
    ToadieRegistryKind.DOMAIN -> mapping.domainBlueprint
    ToadieRegistryKind.SYSTEM -> this[ToadieService.Connections.snapshotSystemBlueprint]
    ToadieRegistryKind.TEAM -> "_team"
}

private fun descriptionConfigured(connection: ResultRow, kind: ToadieRegistryKind): Boolean =
    connection.registryMapping()?.let { mapping -> when (kind) {
        ToadieRegistryKind.DOMAIN -> mapping.domainDescriptionProperty
        ToadieRegistryKind.SYSTEM -> mapping.systemDescriptionProperty
        ToadieRegistryKind.TEAM -> mapping.teamDescriptionProperty
    } } != null

private fun sourceDescriptionSynced(kind: ToadieRegistryKind, row: ResultRow): Boolean = when (kind) {
    ToadieRegistryKind.DOMAIN -> row[DomainToadieSources.descriptionSynced]
    ToadieRegistryKind.SYSTEM -> row[SystemToadieSources.descriptionSynced]
    ToadieRegistryKind.TEAM -> row[TeamToadieSources.descriptionSynced]
}

private fun registryEntityUrl(browserUrl: String, entityId: String): String? =
    "${browserUrl.trimEnd('/')}/entities/$entityId/edit".takeIf { it.length <= MAX_TOADIE_URL_LENGTH }
private fun registryDisconnectedStatus() = ToadieCacheStatus(ToadieCacheState.DISCONNECTED, null, null, false, null)
