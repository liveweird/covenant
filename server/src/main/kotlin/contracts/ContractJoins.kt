package ch.nokillswit.contracts

import ch.nokillswit.domains.DomainService
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.systems.SystemService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserService
import org.jetbrains.exposed.v1.core.*

/**
 * The contract spine — contracts ⋈ systems ⋈ domains ⋈ the two owner OUTER joins — and the
 * contract-scoped filter clauses every contract-family read shares (domain/system/type/owner/q),
 * factored out of [ContractService] so [ContractErrorService] (the Errors report) can join the
 * SAME spine and filter it the SAME way without a second copy drifting. `ContractService` layers
 * its own `latest`-version alias plus the lifecycle/hasErrors clauses on top (those read the
 * LATEST version, a notion the errors read replaces with the row's own version and lifecycle —
 * see [ContractErrorService]).
 */
internal object ContractJoins {
    val ownerTeams = TeamService.Teams.alias("owner_teams")
    val ownerUsers = UserService.Users.alias("owner_users")

    fun spine() = ContractService.Contracts
        .innerJoin(SystemService.Systems)
        .innerJoin(DomainService.Domains)
        .join(ownerTeams, JoinType.LEFT, onColumn = ContractService.Contracts.ownerTeamId, otherColumn = ownerTeams[TeamService.Teams.id])
        .join(ownerUsers, JoinType.LEFT, onColumn = ContractService.Contracts.ownerUserId, otherColumn = ownerUsers[UserService.Users.id])

    /** The owner ref every contract-shaped row carries — the OUTER-joined display name + soft-delete flag. */
    fun ResultRow.ownerRef(): OwnerRef {
        val teamId = this[ContractService.Contracts.ownerTeamId]?.value
        return if (teamId != null) {
            OwnerRef(OwnerKind.TEAM, teamId, this[ownerTeams[TeamService.Teams.name]], this[ownerTeams[TeamService.Teams.markedAsDeleted]])
        } else {
            OwnerRef(
                OwnerKind.USER,
                this[ContractService.Contracts.ownerUserId]!!.value,
                this[ownerUsers[UserService.Users.name]],
                this[ownerUsers[UserService.Users.markedAsDeleted]],
            )
        }
    }

    /** Domain/system/type/owner/free-text — shared by every contract-family read; lifecycle/hasErrors stay the caller's own business. */
    fun contractScope(filter: ContractListFilter): Op<Boolean> {
        val contracts = ContractService.Contracts
        var op: Op<Boolean> = Op.TRUE
        filter.domainId?.let { op = op and (SystemService.Systems.domainId eq it) }
        filter.systemId?.let { op = op and (contracts.systemId eq it) }
        if (filter.types.isNotEmpty()) op = op and (contracts.type inList filter.types.map { it.name })
        filter.ownerTeamId?.let { op = op and (contracts.ownerTeamId eq it) }
        filter.ownerUserId?.let { op = op and (contracts.ownerUserId eq it) }
        filter.q?.takeIf { it.isNotBlank() }?.let { q ->
            op = op and (contracts.name.containsNormalized(q) or contracts.description.containsNormalized(q))
        }
        return op
    }
}
