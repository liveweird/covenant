package ch.nokillswit.domains

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.activeCountsBy
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.lockActiveForUpdate
import ch.nokillswit.infra.db.requireActiveForKeyShare
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import ch.nokillswit.toadie.*

val DomainServiceKey = AttributeKey<DomainService>("DomainService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to DomainService.Domains.id,
    "name" to DomainService.Domains.name,
    "createdAt" to DomainService.Domains.createdAt,
    "updatedAt" to DomainService.Domains.updatedAt,
)

val DOMAIN_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class DomainService(private val database: R2dbcDatabase) {
    object Domains : UIntIdTable("domains"), SoftDeletable {
        // uq_domains_name_active (V7) enforces case-folded uniqueness among active rows.
        val name = varchar("name", length = MAX_DOMAIN_NAME_LENGTH)
        val description = varchar("description", length = MAX_DOMAIN_DESCRIPTION_LENGTH).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    suspend fun list(filter: DomainListFilter, paging: PageRequest): DomainListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = Domains.active()
        filter.name?.takeIf { it.isNotBlank() }?.let { predicate = predicate and Domains.name.containsNormalized(it) }
        val total = Domains.selectAll().where { predicate }.count()
        val rows = Domains.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val sources = registrySources(ToadieRegistryKind.DOMAIN, rows.map { it[Domains.id].value })
        val counts = activeSystemCounts(rows.map { it[Domains.id].value })
        DomainListResult(items = rows.map {
            val id = it[Domains.id].value
            it.toResponse(counts[id] ?: 0, sources[id])
        }, total = total)
    }

    /** Every active domain, name-ordered — the tree's spine and the pickers' source (registry-scale, unpaged). */
    suspend fun listAll(): List<DomainResponse> = suspendTransaction(database) {
        val rows = Domains.selectAll().where { Domains.active() }
            .orderBy(Domains.name.lowerCase() to SortOrder.ASC, Domains.id to SortOrder.ASC).toList()
        val sources = registrySources(ToadieRegistryKind.DOMAIN, rows.map { it[Domains.id].value })
        val counts = activeSystemCounts(rows.map { it[Domains.id].value })
        rows.map { val id = it[Domains.id].value; it.toResponse(counts[id] ?: 0, sources[id]) }
    }

    suspend fun read(id: UInt): DomainResponse? = suspendTransaction(database) {
        val row = Domains.selectAll().where { (Domains.id eq id) and Domains.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        row.toResponse(activeSystemCounts(listOf(id))[id] ?: 0, registrySources(ToadieRegistryKind.DOMAIN, listOf(id))[id])
    }

    suspend fun create(request: DomainRequest): UInt = suspendTransaction(database) {
        acquireToadieRegistryLock()
        validateDomainRequest(request) // re-checked service-side so direct callers stay guarded
        val stamp = nowMillis()
        Domains.insert {
            it[name] = request.name
            it[description] = request.description
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Domains.id].value
    }

    suspend fun update(id: UInt, request: DomainRequest): Int = suspendTransaction(database) {
        acquireToadieRegistryLock()
        validateDomainRequest(request)
        Domains.selectAll().where { (Domains.id eq id) and Domains.active() }
            .forUpdate().toList().singleOrNull() ?: return@suspendTransaction 0
        requireRegistryFieldsMutable(ToadieRegistryKind.DOMAIN, id, request.name, request.description)
        Domains.update({ (Domains.id eq id) and Domains.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[updatedAt] = nowMillis()
        }
    }

    /** Soft delete — refused (409) while an active system still points here, so the tree never dangles. */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        acquireToadieRegistryLock()
        if (!Domains.lockActiveForUpdate(id)) return@suspendTransaction 0
        if ((activeSystemCounts(listOf(id))[id] ?: 0) > 0) {
            throw ConflictException("The domain still holds systems — move or delete them first")
        }
        val changed = Domains.update({ (Domains.id eq id) and Domains.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
        if (changed > 0) deleteRegistrySource(ToadieRegistryKind.DOMAIN, id)
        changed
    }

    /** Validates and locks an ACTIVE domain for a system attach/move in the caller's transaction. */
    suspend fun requireActiveForAttach(id: UInt) = Domains.requireActiveForKeyShare(id, "domain")

    /** One grouped query over the systems table for the rows' active-system counts (a sanctioned cross-feature read). */
    private suspend fun activeSystemCounts(ids: List<UInt>): Map<UInt, Int> =
        SystemService.Systems.activeCountsBy(SystemService.Systems.domainId, ids)

    private fun ResultRow.toResponse(systemCount: Int, source: ToadieRegistrySource?) = DomainResponse(
        id = this[Domains.id].value,
        name = this[Domains.name],
        description = this[Domains.description],
        systemCount = systemCount,
        createdAt = this[Domains.createdAt],
        updatedAt = this[Domains.updatedAt],
        source = source,
    )
}
