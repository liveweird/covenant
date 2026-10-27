package ch.nokillswit.toadie

import ch.nokillswit.contracts.ContractService.Contracts
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ContractVersionService.ContractVersions
import ch.nokillswit.contracts.LatestVersionSummary
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.RefSummary
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService.Systems
import ch.nokillswit.toadie.ToadieService.Connections
import ch.nokillswit.toadie.ToadieService.Links
import ch.nokillswit.toadie.ToadieService.SnapshotEntities
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * The reverse of a contract's Toadie link read, in its own file beside the other read projections
 * (`ToadieOverview.kt`, `ToadieAdoptionProjection.kt`) rather than inside [ToadieService], which is
 * already at detekt's `LargeClass` ceiling. [ToadieService.resolveLinks] opens the transaction;
 * everything here runs inside it.
 */

/** The alias over the contract's denormalized highest-version pointer, mirroring `ContractService`. */
private val resolvedLatest = ContractVersions.alias("resolve_latest_versions")

/**
 * Every active contract that currently covers the Toadie entity [identifier] under [blueprint].
 * Behind `GET /api/v1/toadie-links`, which backs the deep link Toadie renders on its own
 * `api`/`dataset` entity pages — the URL shape Toadie depends on is pinned in
 * `.claude/docs/toadie-integration.md` ("Resolving a Toadie entity").
 *
 * Matching, deliberately:
 * - [blueprint] against the CONNECTION's `api_blueprint`, case-insensitively, exactly as every
 *   other snapshot read compares it (one connection serves one blueprint: `api`, or `dataset`
 *   under the ODCS preset). An `api` link must never answer a `dataset` lookup.
 * - [identifier] BYTE-EXACT: Port resolves relation targets byte-exactly and the incoming URL
 *   carries the entity's own identifier verbatim.
 * - against the LIVE snapshot identifier wherever the entity is still cached, falling back to the
 *   copy stored on the link row ONLY where it is not (a connection never scanned, or an entity
 *   since vanished). The stored copy alone would go stale the moment an entity is renamed
 *   upstream — and a rename is precisely when the incoming URL carries the new name.
 *
 * Soft-deleted contracts and connections drop out; a DISABLED connection does NOT — its links are
 * still the right answer, only its cache has stopped moving. No match is an empty page and never a
 * 404: "nothing is linked yet" is a legitimate answer the resolver page renders as such.
 */
internal suspend fun resolveToadieLinks(
    blueprint: String,
    identifier: String,
    paging: PageRequest,
): ToadieLinkMatchListResult {
    // The snapshot join is keyed on the SnapshotEntities primary key (connectionId, entityId), so it
    // matches at most one row per link and cannot multiply the result.
    val joined = Links
        .innerJoin(Connections, { Links.connectionId }, { Connections.id })
        .innerJoin(Contracts, { Links.contractId }, { Contracts.id })
        .innerJoin(Systems, { Contracts.systemId }, { Systems.id })
        .join(
            SnapshotEntities, JoinType.LEFT,
            additionalConstraint = {
                (SnapshotEntities.connectionId eq Links.connectionId) and
                    (SnapshotEntities.entityId eq Links.apiEntityId) and
                    (SnapshotEntities.blueprint.lowerCase() eq Connections.apiBlueprint.lowerCase())
            },
        )
        .join(resolvedLatest, JoinType.LEFT, onColumn = Contracts.latestVersionId, otherColumn = resolvedLatest[ContractVersions.id])
    // A live entity of this connection already carrying the looked-up identifier makes every
    // stored-copy match on that connection obsolete: the entity was deleted and recreated under a
    // new Port id, or renamed and its old name taken over. Without this the chooser would also
    // offer the contract whose link is dead, and `ToadieLinkMatch` carries no status to tell them
    // apart. Correlated `exists` over the same tables as `uncertainToadieUsage` in ToadieOverview.kt.
    val identifierTakenLive = exists(
        SnapshotEntities.select(SnapshotEntities.entityId).where {
            (SnapshotEntities.connectionId eq Links.connectionId) and
                (SnapshotEntities.blueprint.lowerCase() eq Connections.apiBlueprint.lowerCase()) and
                (SnapshotEntities.identifier eq identifier)
        },
    )
    val predicate = Connections.active() and Contracts.active() and
        (Connections.apiBlueprint.lowerCase() eq blueprint.lowercase()) and
        (
            (SnapshotEntities.identifier eq identifier) or
                (
                    SnapshotEntities.entityId.isNull() and (Links.identifier eq identifier) and
                        not(identifierTakenLive)
                    )
            )
    val total = joined.selectAll().where { predicate }.count()
    val columns = mapOf<String, Column<*>>("id" to Links.id, "contractName" to Contracts.name)
    val items = joined.selectAll().where { predicate }.applyPaging(paging, columns).map { it.toLinkMatch() }.toList()
    return ToadieLinkMatchListResult(items, total)
}

/** One reverse-link row: the contract's catalog facts plus which Toadie the link came through. */
private fun ResultRow.toLinkMatch(): ToadieLinkMatch {
    // A left-joined snapshot row is absent exactly when its key column comes back SQL NULL.
    val live = getOrNull(SnapshotEntities.entityId) != null
    return ToadieLinkMatch(
        linkId = this[Links.id].value,
        contractId = this[Contracts.id].value,
        contractName = this[Contracts.name],
        type = ContractType.valueOf(this[Contracts.type]),
        system = RefSummary(this[Systems.id].value, this[Systems.name]),
        latestVersion = getOrNull(resolvedLatest[ContractVersions.id])?.let {
            LatestVersionSummary(
                id = it.value,
                version = this[resolvedLatest[ContractVersions.version]],
                lifecycle = Lifecycle.valueOf(this[resolvedLatest[ContractVersions.lifecycle]]),
                checkErrors = this[resolvedLatest[ContractVersions.checkErrors]],
                checkWarnings = this[resolvedLatest[ContractVersions.checkWarnings]],
                checkComplete = this[resolvedLatest[ContractVersions.checkComplete]],
            )
        },
        connection = ToadieConnectionRef(this[Connections.id].value, this[Connections.name], this[Connections.browserUrl]),
        blueprint = this[Connections.apiBlueprint],
        identifier = if (live) this[SnapshotEntities.identifier] else this[Links.identifier],
        title = if (live) this[SnapshotEntities.title] else this[Links.title],
    )
}
