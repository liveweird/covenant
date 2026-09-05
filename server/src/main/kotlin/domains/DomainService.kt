package ch.nokillswit.domains

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val DomainServiceKey = AttributeKey<DomainService>("DomainService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to DomainService.Domains.id,
    "name" to DomainService.Domains.name,
    "createdAt" to DomainService.Domains.createdAt,
    "updatedAt" to DomainService.Domains.updatedAt,
)

val DOMAIN_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class DomainService(private val database: R2dbcDatabase) {
    object Domains : UIntIdTable("domains") {
        // uq_domains_name_active (V7) enforces case-folded uniqueness among active rows.
        val name = varchar("name", length = MAX_DOMAIN_NAME_LENGTH)
        val description = varchar("description", length = MAX_DOMAIN_DESCRIPTION_LENGTH).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private fun active(): Op<Boolean> = Domains.markedAsDeleted eq false

    private fun now() = System.currentTimeMillis()

    suspend fun list(filter: DomainListFilter, paging: PageRequest): DomainListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = active()
        filter.name?.takeIf { it.isNotBlank() }?.let { predicate = predicate and Domains.name.containsNormalized(it) }
        val total = Domains.selectAll().where { predicate }.count()
        val rows = Domains.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val counts = activeSystemCounts(rows.map { it[Domains.id].value })
        DomainListResult(items = rows.map { it.toResponse(counts[it[Domains.id].value] ?: 0) }, total = total)
    }

    /** Every active domain, name-ordered — the tree's spine and the pickers' source (registry-scale, unpaged). */
    suspend fun listAll(): List<DomainResponse> = suspendTransaction(database) {
        val rows = Domains.selectAll().where { active() }
            .orderBy(Domains.name.lowerCase() to SortOrder.ASC, Domains.id to SortOrder.ASC).toList()
        val counts = activeSystemCounts(rows.map { it[Domains.id].value })
        rows.map { it.toResponse(counts[it[Domains.id].value] ?: 0) }
    }

    suspend fun read(id: UInt): DomainResponse? = suspendTransaction(database) {
        val row = Domains.selectAll().where { (Domains.id eq id) and active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        row.toResponse(activeSystemCounts(listOf(id))[id] ?: 0)
    }

    suspend fun create(request: DomainRequest): UInt = suspendTransaction(database) {
        validateDomainRequest(request) // re-checked service-side so direct callers stay guarded
        val stamp = now()
        Domains.insert {
            it[name] = request.name
            it[description] = request.description
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Domains.id].value
    }

    suspend fun update(id: UInt, request: DomainRequest): Int = suspendTransaction(database) {
        validateDomainRequest(request)
        Domains.update({ (Domains.id eq id) and active() }) {
            it[name] = request.name
            it[description] = request.description
            it[updatedAt] = now()
        }
    }

    /** Soft delete — refused (409) while an active system still points here, so the tree never dangles. */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if ((activeSystemCounts(listOf(id))[id] ?: 0) > 0) {
            throw ConflictException("The domain still holds systems — move or delete them first")
        }
        Domains.update({ (Domains.id eq id) and active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = now()
        }
    }

    /** True when the id is an ACTIVE domain — the systems service's FK check, run inside ITS transaction. */
    suspend fun existsActive(id: UInt): Boolean =
        Domains.select(Domains.id).where { (Domains.id eq id) and active() }.count() > 0

    /** One grouped query over the systems table for the rows' active-system counts (a sanctioned cross-feature read). */
    private suspend fun activeSystemCounts(ids: List<UInt>): Map<UInt, Int> {
        if (ids.isEmpty()) return emptyMap()
        val systems = SystemService.Systems
        val count = systems.id.count()
        return systems.select(systems.domainId, count)
            .where { (systems.domainId inList ids) and (systems.markedAsDeleted eq false) }
            .groupBy(systems.domainId)
            .map { it[systems.domainId].value to it[count].toInt() }
            .toList()
            .toMap()
    }

    private fun ResultRow.toResponse(systemCount: Int) = DomainResponse(
        id = this[Domains.id].value,
        name = this[Domains.name],
        description = this[Domains.description],
        systemCount = systemCount,
        createdAt = this[Domains.createdAt],
        updatedAt = this[Domains.updatedAt],
    )
}
