package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.domains.DomainService
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.activeCountsBy
import ch.nokillswit.infra.db.requireActive
import ch.nokillswit.infra.db.nowMillis
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ContractServiceKey = AttributeKey<ContractService>("ContractService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ContractService.Contracts.id,
    "name" to ContractService.Contracts.name,
    "type" to ContractService.Contracts.type,
    "createdAt" to ContractService.Contracts.createdAt,
    "updatedAt" to ContractService.Contracts.updatedAt,
)

val CONTRACT_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The caller as the read path sees it: ADMIN flag + the active teams they belong to (loaded once per request). */
data class Viewer(val caller: CallerPrincipal, val teamIds: Set<UInt>)

class ContractService(private val database: R2dbcDatabase, private val teams: TeamService) {
    object Contracts : UIntIdTable("contracts"), SoftDeletable {
        val systemId = reference("system_id", SystemService.Systems)
        val type = varchar("type", length = 20)
        val name = varchar("name", length = MAX_CONTRACT_NAME_LENGTH)
        val description = varchar("description", length = MAX_CONTRACT_DESCRIPTION_LENGTH).nullable()
        val ownerTeamId = reference("owner_team_id", TeamService.Teams).nullable()
        val ownerUserId = reference("owner_user_id", UserService.Users).nullable()
        val createdBy = reference("created_by", UserService.Users)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        // The denormalized "highest active version" pointer (V10) — a plain integer column here
        // (no reference(): the versions table references contracts, and Exposed dislikes cycles).
        val latestVersionId = uinteger("latest_version_id").nullable()
    }

    // Owner display names: two OUTER joins (a contract has one owner side or the other).
    private val ownerTeams = TeamService.Teams.alias("owner_teams")
    private val ownerUsers = UserService.Users.alias("owner_users")
    private val latest = ContractVersionService.ContractVersions.alias("latest_versions")

    private fun joined() = Contracts
        .innerJoin(SystemService.Systems)
        .innerJoin(DomainService.Domains)
        .join(ownerTeams, JoinType.LEFT, onColumn = Contracts.ownerTeamId, otherColumn = ownerTeams[TeamService.Teams.id])
        .join(ownerUsers, JoinType.LEFT, onColumn = Contracts.ownerUserId, otherColumn = ownerUsers[UserService.Users.id])
        .join(latest, JoinType.LEFT, onColumn = Contracts.latestVersionId, otherColumn = latest[ContractVersionService.ContractVersions.id])

    /** The caller's active team set — read once per request, then handed to every `canWrite`. */
    suspend fun viewer(caller: CallerPrincipal): Viewer =
        Viewer(caller, if (caller.isAdmin()) emptySet() else teams.teamIdsOf(caller.userId))

    /**
     * The write gate: the ACTIVE contract's ownership (404 when missing) checked against the
     * caller INSIDE one transaction — membership is never trusted from the JWT. Returns the
     * ownership so the route can reuse it (events, responses).
     */
    suspend fun authorizeWrite(caller: CallerPrincipal, contractId: UInt): Ownership = suspendTransaction(database) {
        val ownership = ownershipOf(contractId) ?: throw NotFoundException("Contract not found")
        val teamIds = if (caller.isAdmin()) emptySet() else teams.activeTeamIdsOf(caller.userId)
        requireContractWriter(caller, ownership, teamIds)
        ownership
    }

    suspend fun list(filter: ContractListFilter, paging: PageRequest, viewer: Viewer): ContractListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Contracts.active()
        val total = joined().selectAll().where { predicate }.count()
        val rows = joined().selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val facts = facts(rows.map { it[Contracts.id].value }, viewer)
        ContractListResult(items = rows.map { it.toResponse(viewer, facts) }, total = total)
    }

    suspend fun read(id: UInt, viewer: Viewer): ContractResponse? = suspendTransaction(database) {
        val row = joined().selectAll().where { (Contracts.id eq id) and Contracts.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        row.toResponse(viewer, facts(listOf(id), viewer))
    }

    /**
     * Create — the owner must be assignable by the caller (403) BEFORE the client-supplied ids are checked
     * (400: an inactive system or owner), so a non-admin naming a team they are not in learns nothing about
     * whether it exists; the name clash is the index's 409. The shape rules (owner XOR) are the one 400 that
     * precedes the guard — without a well-formed owner there is nothing to authorize.
     */
    suspend fun create(request: ContractCreateRequest, caller: CallerPrincipal): UInt = suspendTransaction(database) {
        val ownership = validateContractCreate(request) // re-checked service-side
        val teamIds = if (caller.isAdmin()) emptySet() else teams.activeTeamIdsOf(caller.userId)
        requireOwnerAssignable(caller, ownership, teamIds)
        requireActiveSystem(request.systemId)
        requireActiveOwner(ownership)
        val stamp = nowMillis()
        Contracts.insert {
            it[systemId] = request.systemId
            it[type] = request.type.name
            it[name] = request.name
            it[description] = request.description
            it[ownerTeamId] = ownership.teamId
            it[ownerUserId] = ownership.userId
            it[createdBy] = caller.userId
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Contracts.id].value
    }

    suspend fun update(id: UInt, request: ContractUpdateRequest): Int = suspendTransaction(database) {
        validateContractUpdate(request)
        Contracts.update({ (Contracts.id eq id) and Contracts.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[updatedAt] = nowMillis()
        }
    }

    /** ADMIN-only ownership transfer; returns the previous ownership (for the event) or null when missing. */
    suspend fun transferOwner(id: UInt, ownership: Ownership): Ownership? = suspendTransaction(database) {
        val previous = ownershipOf(id) ?: return@suspendTransaction null
        requireActiveOwner(ownership)
        Contracts.update({ (Contracts.id eq id) and Contracts.active() }) {
            it[ownerTeamId] = ownership.teamId
            it[ownerUserId] = ownership.userId
            it[updatedAt] = nowMillis()
        }
        previous
    }

    /**
     * Soft delete — refused (409) while any version is PUBLISHED (ACTIVE/DEPRECATED): retire it
     * first. Soft-deletes the versions with it so their identities are freed as one unit.
     */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        val versions = ContractVersionService.ContractVersions
        val published = versions.selectAll()
            .where {
                (versions.contractId eq id) and (versions.markedAsDeleted eq false) and
                    (versions.lifecycle inList Lifecycle.entries.filter { it.published }.map { it.name })
            }
            .count()
        if (published > 0) throw ConflictException("The contract still has active or deprecated versions — retire them first")
        val stamp = nowMillis()
        val rows = Contracts.update({ (Contracts.id eq id) and Contracts.active() }) {
            it[markedAsDeleted] = true
            it[latestVersionId] = null
            it[updatedAt] = stamp
        }
        if (rows > 0) {
            versions.update({ (versions.contractId eq id) and (versions.markedAsDeleted eq false) }) {
                it[versions.markedAsDeleted] = true
                it[versions.updatedAt] = stamp
            }
        }
        rows
    }

    /** The full export: the contract plus every active version's verbatim text, highest first. */
    suspend fun export(id: UInt, viewer: Viewer): ContractExportResponse? {
        val contract = read(id, viewer) ?: return null
        val versions = suspendTransaction(database) {
            val v = ContractVersionService.ContractVersions
            v.selectAll().where { (v.contractId eq id) and (v.markedAsDeleted eq false) }
                .orderBy(
                    v.semverMajor to SortOrder.DESC,
                    v.semverMinor to SortOrder.DESC,
                    v.semverPatch to SortOrder.DESC,
                    v.semverPrerelease to SortOrder.DESC_NULLS_FIRST,
                )
                .map {
                    ExportedVersion(
                        it[v.version],
                        Lifecycle.valueOf(it[v.lifecycle]),
                        ch.nokillswit.contracts.checks.DocumentFormat.valueOf(it[v.format]),
                        it[v.content],
                    )
                }
                .toList()
        }
        return ContractExportResponse(contract, versions)
    }

    /** Whether a contract with this (system, name) is active — the import's existence probe (by id). */
    suspend fun findActiveId(systemId: UInt, name: String): UInt? = suspendTransaction(database) {
        Contracts.select(Contracts.id)
            .where { (Contracts.systemId eq systemId) and (Contracts.name.lowerCase() eq name.lowercase()) and Contracts.active() }
            .map { it[Contracts.id].value }.toList().singleOrNull()
    }

    suspend fun typeOf(id: UInt): ContractType? = suspendTransaction(database) {
        Contracts.select(
            Contracts.type,
        ).where { (Contracts.id eq id) and Contracts.active() }.map { ContractType.valueOf(it[Contracts.type]) }.toList().singleOrNull()
    }

    /** The facet counts behind the list/tree filters — six group-bys over the shared predicate, one transaction. */
    suspend fun facets(filter: ContractListFilter): FacetsResponse = suspendTransaction(database) {
        val v = ContractVersionService.ContractVersions
        val n = Contracts.id.count()
        fun rows(dimension: FacetDimension, vararg groups: Expression<*>) =
            joined().select(listOf(*groups, n)).where { buildPredicate(filter.lifting(dimension)) and Contracts.active() }.groupBy(*groups)
        val type = rows(FacetDimension.TYPE, Contracts.type).map { FacetCount(it[Contracts.type], it[n]) }.toList()
        val lifecycle = rows(FacetDimension.LIFECYCLE, latest[v.lifecycle])
            .map { FacetCount(it.getOrNull(latest[v.lifecycle]) ?: NO_VERSION_FACET, it[n]) }.toList()
        val domains = DomainService.Domains
        val domain = rows(FacetDimension.DOMAIN, domains.id, domains.name)
            .map { NamedFacetCount(it[domains.id].value, it[domains.name], it[n]) }.toList()
        val systems = SystemService.Systems
        val system = rows(FacetDimension.SYSTEM, systems.id, systems.name)
            .map { NamedFacetCount(it[systems.id].value, it[systems.name], it[n]) }.toList()
        val teamId = ownerTeams[TeamService.Teams.id]
        val teamName = ownerTeams[TeamService.Teams.name]
        val ownerTeam = joined().select(teamId, teamName, n)
            .where {
                buildPredicate(filter.lifting(FacetDimension.OWNER_TEAM)) and Contracts.active() and Contracts.ownerTeamId.isNotNull()
            }
            .groupBy(teamId, teamName)
            .map { NamedFacetCount(it[teamId].value, it[teamName], it[n]) }.toList()
        val errors = latest[v.checkErrors]
        val lifted = buildPredicate(filter.lifting(FacetDimension.HAS_ERRORS)) and Contracts.active()
        val withErrors = joined().selectAll().where { lifted and (errors greater 0) }.count()
        val clean = joined().selectAll().where { lifted and ((errors eq 0) or errors.isNull()) }.count()
        FacetsResponse(
            type = type.sortedBy { it.value },
            lifecycle = lifecycle.sortedBy { it.value },
            domain = domain.sortedBy { it.name.lowercase() },
            system = system.sortedBy { it.name.lowercase() },
            ownerTeam = ownerTeam.sortedBy { it.name.lowercase() },
            hasErrors = ErrorFacets(withErrors, clean),
        )
    }

    /** Domain → System → Contract, for the hierarchy page — registry-scale, unpaged; the filters narrow the contracts. */
    suspend fun tree(filter: ContractListFilter, viewer: Viewer): TreeResponse = suspendTransaction(database) {
        val domains = DomainService.Domains
        val systems = SystemService.Systems
        val domainRows = domains.selectAll().where { domains.markedAsDeleted eq false }
            .orderBy(domains.name.lowerCase() to SortOrder.ASC, domains.id to SortOrder.ASC).toList()
        val systemRows = systems.selectAll().where { systems.markedAsDeleted eq false }
            .orderBy(systems.name.lowerCase() to SortOrder.ASC, systems.id to SortOrder.ASC).toList()
        val predicate = buildPredicate(filter) and Contracts.active()
        val contractRows = joined().selectAll().where { predicate }
            .orderBy(Contracts.name.lowerCase() to SortOrder.ASC, Contracts.id to SortOrder.ASC).toList()
        val facts = facts(contractRows.map { it[Contracts.id].value }, viewer)
        val bySystem = contractRows.groupBy({ it[Contracts.systemId].value }, { it.toTreeContract(viewer, facts) })
        val systemsByDomain = systemRows.groupBy { it[systems.domainId].value }
        TreeResponse(
            domains = domainRows.map { d ->
                val id = d[domains.id].value
                val sys = systemsByDomain[id].orEmpty().map { s ->
                    val sid = s[systems.id].value
                    TreeSystem(sid, s[systems.name], bySystem[sid].orEmpty())
                }
                TreeDomain(id, d[domains.name], sys)
            },
        )
    }

    // ---- internals -----------------------------------------------------------------------

    private suspend fun ownershipOf(id: UInt): Ownership? =
        Contracts.select(Contracts.ownerTeamId, Contracts.ownerUserId)
            .where { (Contracts.id eq id) and Contracts.active() }
            .map { Ownership(it[Contracts.ownerTeamId]?.value, it[Contracts.ownerUserId]?.value) }
            .toList().singleOrNull()

    private suspend fun requireActiveSystem(systemId: UInt) = SystemService.Systems.requireActive(systemId, "system")

    private suspend fun requireActiveOwner(ownership: Ownership) {
        ownership.teamId?.let { TeamService.Teams.requireActive(it, "team") }
        ownership.userId?.let { UserService.Users.requireActive(it, "user") }
    }

    /** The contract's name for a notification's params — deleted rows included (the deletion itself notifies). */
    suspend fun nameOf(id: UInt): String? = suspendTransaction(database) {
        Contracts.select(Contracts.name).where { Contracts.id eq id }.map { it[Contracts.name] }.toList().singleOrNull()
    }

    /** The per-row aggregates one read gathers beside the join: version counts, follower counts, the caller's follows (V13). */
    private data class RowFacts(val versionCounts: Map<UInt, Int>, val subscriberCounts: Map<UInt, Int>, val subscribed: Set<UInt>)

    private suspend fun facts(ids: List<UInt>, viewer: Viewer): RowFacts {
        if (ids.isEmpty()) return RowFacts(emptyMap(), emptyMap(), emptySet())
        val s = ContractSubscriptionService.ContractSubscriptions
        val count = s.userId.count()
        val subscriberCounts = s.select(s.contractId, count).where { s.contractId inList ids }.groupBy(s.contractId)
            .map { it[s.contractId].value to it[count].toInt() }.toList().toMap()
        val subscribed = s.select(s.contractId).where { (s.contractId inList ids) and (s.userId eq viewer.caller.userId) }
            .map { it[s.contractId].value }.toList().toSet()
        return RowFacts(versionCounts(ids), subscriberCounts, subscribed)
    }

    private suspend fun versionCounts(ids: List<UInt>): Map<UInt, Int> =
        ContractVersionService.ContractVersions.activeCountsBy(ContractVersionService.ContractVersions.contractId, ids)

    private companion object {
        const val NO_VERSION_FACET = "NONE"
    }

    private fun buildPredicate(filter: ContractListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.domainId?.let { op = op and (SystemService.Systems.domainId eq it) }
        filter.systemId?.let { op = op and (Contracts.systemId eq it) }
        if (filter.types.isNotEmpty()) op = op and (Contracts.type inList filter.types.map { it.name })
        filter.ownerTeamId?.let { op = op and (Contracts.ownerTeamId eq it) }
        filter.ownerUserId?.let { op = op and (Contracts.ownerUserId eq it) }
        if (filter.lifecycles.isNotEmpty()) {
            op = op and (latest[ContractVersionService.ContractVersions.lifecycle] inList filter.lifecycles.map { it.name })
        }
        filter.q?.takeIf { it.isNotBlank() }?.let { q ->
            op = op and (Contracts.name.containsNormalized(q) or Contracts.description.containsNormalized(q))
        }
        filter.hasErrors?.let { wanted ->
            val errors = latest[ContractVersionService.ContractVersions.checkErrors]
            op = op and if (wanted) errors greater 0 else (errors eq 0) or errors.isNull()
        }
        return op
    }

    private fun ResultRow.ownership() = Ownership(this[Contracts.ownerTeamId]?.value, this[Contracts.ownerUserId]?.value)

    private fun ResultRow.owner(): OwnerRef {
        val teamId = this[Contracts.ownerTeamId]?.value
        return if (teamId != null) {
            OwnerRef(OwnerKind.TEAM, teamId, this[ownerTeams[TeamService.Teams.name]], this[ownerTeams[TeamService.Teams.markedAsDeleted]])
        } else {
            OwnerRef(
                OwnerKind.USER,
                this[Contracts.ownerUserId]!!.value,
                this[ownerUsers[UserService.Users.name]],
                this[ownerUsers[UserService.Users.markedAsDeleted]],
            )
        }
    }

    private fun ResultRow.latestVersion(): LatestVersionSummary? {
        val v = ContractVersionService.ContractVersions
        val id = this.getOrNull(latest[v.id])?.value ?: return null
        return LatestVersionSummary(
            id = id,
            version = this[latest[v.version]],
            lifecycle = Lifecycle.valueOf(this[latest[v.lifecycle]]),
            checkErrors = this[latest[v.checkErrors]],
            checkWarnings = this[latest[v.checkWarnings]],
            checkComplete = this[latest[v.checkComplete]],
        )
    }

    private fun ResultRow.toResponse(viewer: Viewer, facts: RowFacts): ContractResponse {
        val id = this[Contracts.id].value
        return ContractResponse(
            id = id,
            system = RefSummary(this[SystemService.Systems.id].value, this[SystemService.Systems.name]),
            domain = RefSummary(this[DomainService.Domains.id].value, this[DomainService.Domains.name]),
            type = ContractType.valueOf(this[Contracts.type]),
            name = this[Contracts.name],
            description = this[Contracts.description],
            owner = owner(),
            latestVersion = latestVersion(),
            versionCount = facts.versionCounts[id] ?: 0,
            canWrite = canWriteContract(viewer.caller, ownership(), viewer.teamIds),
            subscribed = id in facts.subscribed,
            subscriberCount = facts.subscriberCounts[id] ?: 0,
            createdBy = this[Contracts.createdBy].value,
            createdAt = this[Contracts.createdAt],
            updatedAt = this[Contracts.updatedAt],
        )
    }

    private fun ResultRow.toTreeContract(viewer: Viewer, facts: RowFacts): TreeContract {
        val id = this[Contracts.id].value
        return TreeContract(
            id = id,
            name = this[Contracts.name],
            type = ContractType.valueOf(this[Contracts.type]),
            owner = owner(),
            latestVersion = latestVersion(),
            versionCount = facts.versionCounts[id] ?: 0,
            canWrite = canWriteContract(viewer.caller, ownership(), viewer.teamIds),
            subscribed = id in facts.subscribed,
            subscriberCount = facts.subscriberCounts[id] ?: 0,
        )
    }
}
