package ch.nokillswit.systems

import ch.nokillswit.domains.DomainService
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val SystemServiceKey = AttributeKey<SystemService>("SystemService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to SystemService.Systems.id,
    "name" to SystemService.Systems.name,
    "domainId" to SystemService.Systems.domainId,
    "createdAt" to SystemService.Systems.createdAt,
    "updatedAt" to SystemService.Systems.updatedAt,
)

val SYSTEM_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class SystemService(private val database: R2dbcDatabase, private val domains: DomainService) {
    object Systems : UIntIdTable("systems") {
        val domainId = reference("domain_id", DomainService.Domains)
        // uq_systems_domain_name_active (V8): (domain_id, LOWER(name)) among active rows.
        val name = varchar("name", length = MAX_SYSTEM_NAME_LENGTH)
        val description = varchar("description", length = MAX_SYSTEM_DESCRIPTION_LENGTH).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    private fun active(): Op<Boolean> = Systems.markedAsDeleted eq false

    private fun now() = System.currentTimeMillis()

    private fun joined() = Systems.innerJoin(DomainService.Domains)

    suspend fun list(filter: SystemListFilter, paging: PageRequest): SystemListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = active()
        filter.name?.takeIf { it.isNotBlank() }?.let { predicate = predicate and Systems.name.containsNormalized(it) }
        filter.domainId?.let { predicate = predicate and (Systems.domainId eq it) }
        val total = Systems.selectAll().where { predicate }.count()
        val rows = joined().selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        SystemListResult(items = rows.map { it.toResponse() }, total = total)
    }

    /** Every active system with its domain, name-ordered — the tree's second level (registry-scale, unpaged). */
    suspend fun listAll(): List<SystemResponse> = suspendTransaction(database) {
        joined().selectAll().where { active() }
            .orderBy(Systems.name.lowerCase() to SortOrder.ASC, Systems.id to SortOrder.ASC)
            .map { it.toResponse() }.toList()
    }

    suspend fun read(id: UInt): SystemResponse? = suspendTransaction(database) {
        joined().selectAll().where { (Systems.id eq id) and active() }.toList().singleOrNull()?.toResponse()
    }

    /** Creates inside an ACTIVE domain (an unknown one is the client's fault → 400). */
    suspend fun create(request: SystemRequest): UInt = suspendTransaction(database) {
        validateSystemRequest(request)
        requireDomain(request.domainId)
        val stamp = now()
        Systems.insert {
            it[domainId] = request.domainId
            it[name] = request.name
            it[description] = request.description
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Systems.id].value
    }

    /** Full replace — the domain may change (moving the system); the name clash rides the V8 index → 409. */
    suspend fun update(id: UInt, request: SystemRequest): Int = suspendTransaction(database) {
        validateSystemRequest(request)
        requireDomain(request.domainId)
        Systems.update({ (Systems.id eq id) and active() }) {
            it[domainId] = request.domainId
            it[name] = request.name
            it[description] = request.description
            it[updatedAt] = now()
        }
    }

    /** Soft delete; the contracts feature adds the 409 while an active contract still points here. */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        Systems.update({ (Systems.id eq id) and active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = now()
        }
    }

    private suspend fun requireDomain(domainId: UInt) {
        if (!domains.existsActive(domainId)) throw BadRequestException("Unknown or deleted domain id: $domainId")
    }

    private fun ResultRow.toResponse() = SystemResponse(
        id = this[Systems.id].value,
        domainId = this[Systems.domainId].value,
        domainName = this[DomainService.Domains.name],
        name = this[Systems.name],
        description = this[Systems.description],
        contractCount = 0,
        createdAt = this[Systems.createdAt],
        updatedAt = this[Systems.updatedAt],
    )
}
