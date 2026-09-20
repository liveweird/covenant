package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractJoins.ownerRef
import ch.nokillswit.contracts.ContractService.Contracts
import ch.nokillswit.contracts.ContractVersionService.ContractVersions
import ch.nokillswit.contracts.ReleaseLineService.ReleaseLines
import ch.nokillswit.domains.DomainService.Domains
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.systems.SystemService.Systems
import ch.nokillswit.teams.TeamService
import ch.nokillswit.toadie.ToadieUsageSummary
import ch.nokillswit.toadie.toadieUsageSummaries
import ch.nokillswit.toadie.uncertainToadieUsage
import ch.nokillswit.users.UserService.Users
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

val LifecycleOverviewServiceKey = AttributeKey<LifecycleOverviewService>("LifecycleOverviewService")
val LIFECYCLE_OVERVIEW_SORT_FIELDS = setOf("id", "name", "major", "nextDeadline", "supportStatus")

/** SQL-paged read model; no version bodies and no per-line service calls. */
class LifecycleOverviewService(
    private val database: R2dbcDatabase,
    private val teams: TeamService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val replacement = Contracts.alias("overview_replacement")
    private val replacementLine = ReleaseLines.alias("overview_replacement_line")

    private fun joined() = ContractJoins.spine()
        .join(ReleaseLines, JoinType.INNER, Contracts.id, ReleaseLines.contractId)
        .join(replacement, JoinType.LEFT, ReleaseLines.replacementContractId, replacement[Contracts.id])
        .join(replacementLine, JoinType.LEFT, additionalConstraint = {
            (replacementLine[ReleaseLines.contractId] eq ReleaseLines.replacementContractId) and
                (replacementLine[ReleaseLines.major] eq ReleaseLines.replacementMajor) and
                (replacementLine[ReleaseLines.markedAsDeleted] eq false)
        })

    suspend fun list(filter: LifecycleOverviewFilter, paging: PageRequest, caller: CallerPrincipal): LifecycleOverviewResult =
        suspendTransaction(database) {
            val rules = Rules(clock.millis())
            val predicate = rules.predicate(filter)
            val total = joined().selectAll().where { predicate }.count()
            val columns = mapOf<String, Expression<*>>(
                "id" to ReleaseLines.id, "name" to Contracts.name, "major" to ReleaseLines.major,
                "supportStatus" to ReleaseLines.supportStatus, "nextDeadline" to rules.nextDeadline,
            )
            val flags = rules.flags.mapValues { (key, op) ->
                Case().When(op, booleanLiteral(true)).Else(booleanLiteral(false)).alias("overview_${key.name.lowercase()}")
            }
            val selected = joined().select(joined().columns + flags.values + rules.nextDeadline).where { predicate }
                .orderBy(*paging.sort.map {
                    columns.getValue(it.name) to if (it.descending) SortOrder.DESC_NULLS_LAST else SortOrder.ASC_NULLS_LAST
                }.toTypedArray())
                .limit(paging.pageSize).offset((paging.page.toLong() - 1) * paging.pageSize).toList()
            val usage = toadieUsageSummaries(selected.map { it[Contracts.id].value }.toSet(), rules.now)
            val memberships = teams.activeTeamIdsOf(caller.userId)
            LifecycleOverviewResult(selected.map { row ->
                row.toOverview(caller, memberships, usage.getValue(row[Contracts.id].value),
                    row[rules.nextDeadline], flags.mapValues { row[it.value] })
            }, total)
        }

    suspend fun summary(filter: LifecycleOverviewFilter): LifecycleOverviewSummary = suspendTransaction(database) {
        val rules = Rules(clock.millis())
        val base = rules.predicate(filter.copy(attention = null))
        fun query(op: Op<Boolean>) = joined().selectAll().where { op }
        val counts = mutableMapOf<LifecycleAttention, Long>()
        for ((key, flag) in rules.flags) counts[key] = query(base and flag).count()
        val users = ContractJoins.ownerUsers
        val id = users[Users.id]
        val name = users[Users.name]
        val count = ReleaseLines.id.count()
        val ownerScope = filter.copy(attention = null, contracts = filter.contracts.copy(ownerTeamId = null, ownerUserId = null))
        val owners = joined().select(id, name, count).where { rules.predicate(ownerScope) and id.isNotNull() }
            .groupBy(id, name).orderBy(name to SortOrder.ASC, id to SortOrder.ASC).toList()
            .map { NamedFacetCount(it[id].value, it[name], it[count]) }
        LifecycleOverviewSummary(rules.today, query(base).count(), counts.getValue(LifecycleAttention.DEADLINE_SOON),
            counts.getValue(LifecycleAttention.SUPPORT_ENDED), counts.getValue(LifecycleAttention.MIGRATION_INCOMPLETE),
            counts.getValue(LifecycleAttention.USAGE_UNCERTAIN), owners)
    }

    private inner class Rules(val now: Long) {
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC).toString()
        private val horizon = LocalDate.parse(today).plusDays(30).toString()
        private val ongoing = ReleaseLines.supportStatus neq SupportStatus.END_OF_LIFE.name
        private val hasDeprecation = ReleaseLines.deprecatesOn.isNotNull()
        private val hasEnd = ReleaseLines.supportEndsOn.isNotNull()
        private val reached = ongoing and ((hasDeprecation and (ReleaseLines.deprecatesOn lessEq today)) or
            (hasEnd and (ReleaseLines.supportEndsOn lessEq today)))
        private val soon = ongoing and (
            (hasDeprecation and (ReleaseLines.deprecatesOn greater today) and (ReleaseLines.deprecatesOn lessEq horizon)) or
            (hasEnd and (ReleaseLines.supportEndsOn greater today) and (ReleaseLines.supportEndsOn lessEq horizon)))
        private val available = replacement[Contracts.id].isNotNull() and (replacement[Contracts.markedAsDeleted] eq false) and
            (ReleaseLines.replacementMajor.isNull() or replacementLine[ReleaseLines.id].isNotNull())
        private val intent = hasDeprecation or hasEnd or ReleaseLines.replacementContractId.isNotNull() or
            ReleaseLines.migrationGuide.isNotNull()
        val flags = mapOf(
            LifecycleAttention.DEADLINE_SOON to soon,
            LifecycleAttention.SUPPORT_ENDED to (ongoing and hasEnd and (ReleaseLines.supportEndsOn lessEq today)),
            LifecycleAttention.MIGRATION_INCOMPLETE to (ongoing and intent and (ReleaseLines.migrationGuide.isNull() or not(available))),
            LifecycleAttention.USAGE_UNCERTAIN to uncertainToadieUsage(now),
        )
        val nextDeadline = Case().When(ongoing, CustomFunction<String?>(
            "LEAST", TextColumnType().apply { nullable = true }, ReleaseLines.deprecatesOn, ReleaseLines.supportEndsOn,
        ))
            .Else(null, TextColumnType().apply { nullable = true }).alias("overview_next_deadline")

        fun predicate(filter: LifecycleOverviewFilter): Op<Boolean> {
            var op = Contracts.active() and Systems.active() and Domains.active() and ReleaseLines.active() and
                ContractJoins.contractScope(filter.contracts) and exists(ContractVersions.select(ContractVersions.id).where {
                    ContractVersions.active() and (ContractVersions.contractId eq Contracts.id) and
                        (ContractVersions.semverMajor eq ReleaseLines.major)
                })
            if (filter.supportStatuses.isNotEmpty()) op = op and (ReleaseLines.supportStatus inList filter.supportStatuses.map { it.name })
            op = op and when (filter.deadline) {
                LifecycleDeadline.REACHED -> reached
                LifecycleDeadline.NEXT_30_DAYS -> soon
                LifecycleDeadline.NONE -> ReleaseLines.deprecatesOn.isNull() and ReleaseLines.supportEndsOn.isNull()
                null -> Op.TRUE
            }
            filter.attention?.let { op = op and flags.getValue(it) }
            return op
        }
    }

    private fun ResultRow.toOverview(
        caller: CallerPrincipal, memberships: Set<UInt>, usage: ToadieUsageSummary,
        nextDeadline: String?, flags: Map<LifecycleAttention, Boolean>,
    ): LifecycleOverviewRow {
        val owner = ownerRef()
        val ownership = Ownership(this[Contracts.ownerTeamId]?.value, this[Contracts.ownerUserId]?.value)
        val targetId = this[ReleaseLines.replacementContractId]?.value
        val targetName = getOrNull(replacement[Contracts.name])
        val targetMajor = this[ReleaseLines.replacementMajor]
        val available = targetName != null && getOrNull(replacement[Contracts.markedAsDeleted]) == false &&
            (targetMajor == null || getOrNull(replacementLine[ReleaseLines.id]) != null)
        return LifecycleOverviewRow(
            this[ReleaseLines.id].value,
            LifecycleContractRef(this[Contracts.id].value, this[Contracts.name], ContractType.valueOf(this[Contracts.type]),
                RefSummary(this[Systems.id].value, this[Systems.name]), RefSummary(this[Domains.id].value, this[Domains.name]),
                owner, canWriteContract(caller, ownership, memberships)),
            this[ReleaseLines.major], SupportStatus.valueOf(this[ReleaseLines.supportStatus]),
            this[ReleaseLines.deprecatesOn], this[ReleaseLines.supportEndsOn],
            targetId?.let { ReleaseLineReplacement(it, targetName, targetMajor, available) },
            this[ReleaseLines.migrationGuide] != null, nextDeadline,
            flags.getValue(LifecycleAttention.DEADLINE_SOON), flags.getValue(LifecycleAttention.SUPPORT_ENDED),
            flags.getValue(LifecycleAttention.MIGRATION_INCOMPLETE), flags.getValue(LifecycleAttention.USAGE_UNCERTAIN), usage,
        )
    }
}
