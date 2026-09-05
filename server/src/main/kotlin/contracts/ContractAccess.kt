package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin

/**
 * THE contract writer rule (decided with the product owner): everyone authenticated reads
 * everything; a write to a contract or its versions takes ADMIN, the owning user, or a member
 * of the owning team. Pure — the membership set is loaded by the service INSIDE the write's
 * transaction (never from the JWT: a membership change must take effect immediately), and the
 * route runs the same check before the body decodes so 403 wins over 400.
 */
fun canWriteContract(caller: CallerPrincipal, ownership: Ownership, callerTeamIds: Set<UInt>): Boolean =
    caller.isAdmin() ||
        ownership.userId == caller.userId ||
        (ownership.teamId != null && ownership.teamId in callerTeamIds)

fun requireContractWriter(caller: CallerPrincipal, ownership: Ownership, callerTeamIds: Set<UInt>) {
    if (!canWriteContract(caller, ownership, callerTeamIds)) {
        throw ForbiddenException("Only the owning team's members, the owning user, or an administrator may modify this contract")
    }
}

/**
 * Who may CREATE a contract with a given owner: ADMIN assigns anyone; a regular user may own
 * it personally or hand it to a team they belong to — never to a stranger or a foreign team.
 */
fun requireOwnerAssignable(caller: CallerPrincipal, ownership: Ownership, callerTeamIds: Set<UInt>) {
    if (caller.isAdmin()) return
    val allowed = ownership.userId == caller.userId || (ownership.teamId != null && ownership.teamId in callerTeamIds)
    if (!allowed) throw ForbiddenException("You may only assign a contract to yourself or to a team you belong to")
}
