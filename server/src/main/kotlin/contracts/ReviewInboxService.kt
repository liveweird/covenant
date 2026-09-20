package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.contracts.ContractJoins.ownerRef
import ch.nokillswit.contracts.ContractService.Contracts
import ch.nokillswit.contracts.ContractSubscriptionService.ContractSubscriptions
import ch.nokillswit.contracts.ContractVersionService.ContractVersions
import ch.nokillswit.contracts.VersionReviewService.Entries
import ch.nokillswit.contracts.VersionReviewService.Reviews
import ch.nokillswit.domains.DomainService.Domains
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.systems.SystemService.Systems
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamService.TeamMembers
import ch.nokillswit.teams.TeamService.Teams
import ch.nokillswit.users.UserService.Users
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ReviewInboxServiceKey = AttributeKey<ReviewInboxService>("ReviewInboxService")
val REVIEW_INBOX_SORT_FIELDS = setOf("id", "contractName", "requestedAt")

class ReviewInboxService(
    private val database: R2dbcDatabase,
    private val teams: TeamService,
) {
    private val requester = Users.alias("review_inbox_requester")
    private val newerReview = Reviews.alias("review_inbox_newer_review")

    private fun joined() = ContractJoins.spine()
        .join(ContractVersions, JoinType.INNER, Contracts.id, ContractVersions.contractId)
        .join(Reviews, JoinType.INNER, ContractVersions.id, Reviews.versionId)
        .join(requester, JoinType.INNER, Reviews.requestedBy, requester[Users.id])

    suspend fun list(
        filter: ReviewInboxFilter,
        paging: PageRequest,
        caller: CallerPrincipal,
    ): ReviewInboxResult = suspendTransaction(database) {
        val rules = Rules(caller)
        val predicate = rules.predicate(filter)
        val total = joined().selectAll().where { predicate }.count()
        val rows = joined().select(selectedColumns(rules)).where { predicate }
            .applyPaging(paging, sortableColumns).toList()
        val facts = versionReviewSummaryFacts(rows.map { it[Reviews.id].value })
        val memberships = teams.activeTeamIdsOf(caller.userId)
        val activeCaller = Users.select(Users.id).where { (Users.id eq caller.userId) and Users.active() }.count() > 0
        ReviewInboxResult(rows.map { it.toInboxRow(caller, memberships, activeCaller, facts, rules) }, total)
    }

    suspend fun summary(filter: ReviewInboxFilter, caller: CallerPrincipal): ReviewInboxSummary = suspendTransaction(database) {
        val rules = Rules(caller)
        val base = rules.predicate(filter.copy(attention = null))
        suspend fun count(extra: Op<Boolean> = Op.TRUE) = joined().selectAll().where { base and extra }.count()
        ReviewInboxSummary(
            total = count(),
            awaitingMyReview = count(rules.awaitingMyReview),
            changesRequested = count(rules.changesRequested),
            needsNewReview = count(rules.needsNewReview),
        )
    }

    private val sortableColumns: Map<String, Column<*>> = mapOf(
        "id" to Reviews.id,
        "contractName" to Contracts.name,
        "requestedAt" to Reviews.requestedAt,
    )

    private fun selectedColumns(rules: Rules): List<Expression<*>> = listOf(
        Reviews.id,
        Reviews.contentRevision,
        Reviews.contentSha256,
        Reviews.requestedAt,
        Reviews.closedAt,
        Reviews.closeReason,
        Reviews.requestedBy,
        requester[Users.name],
        requester[Users.markedAsDeleted],
        ContractVersions.id,
        ContractVersions.version,
        ContractVersions.contentRevision,
        ContractVersions.contentSha256,
        Contracts.id,
        Contracts.name,
        Contracts.type,
        Contracts.ownerTeamId,
        Contracts.ownerUserId,
        ContractJoins.ownerTeams[Teams.id],
        ContractJoins.ownerTeams[Teams.name],
        ContractJoins.ownerTeams[Teams.markedAsDeleted],
        ContractJoins.ownerUsers[Users.id],
        ContractJoins.ownerUsers[Users.name],
        ContractJoins.ownerUsers[Users.markedAsDeleted],
        Systems.id,
        Systems.name,
        Domains.id,
        Domains.name,
        rules.ownedFlag,
        rules.subscribedFlag,
        rules.awaitingFlag,
        rules.changesFlag,
        rules.needsFlag,
    )

    private inner class Rules(private val caller: CallerPrincipal) {
        private val latestReview = notExists(
            newerReview.select(newerReview[Reviews.id]).where {
                (newerReview[Reviews.versionId] eq Reviews.versionId) and
                    (newerReview[Reviews.id] greater Reviews.id)
            },
        )
        val owned: Op<Boolean> = (Contracts.ownerUserId eq caller.userId) or exists(
            TeamMembers.innerJoin(Teams).select(TeamMembers.teamId).where {
                (TeamMembers.userId eq caller.userId) and
                    (TeamMembers.teamId eq Contracts.ownerTeamId) and Teams.active()
            },
        )
        val subscribed: Op<Boolean> = exists(
            ContractSubscriptions.select(ContractSubscriptions.contractId).where {
                (ContractSubscriptions.userId eq caller.userId) and
                    (ContractSubscriptions.contractId eq Contracts.id)
            },
        )
        private val currentOpen = Reviews.closedAt.isNull() and
            (Reviews.contentRevision eq ContractVersions.contentRevision) and
            (Reviews.contentSha256 eq ContractVersions.contentSha256)
        private val myDecisionExists = latestDecisionExists(caller.userId, null)
        val awaitingMyReview: Op<Boolean> = currentOpen and (Reviews.requestedBy neq caller.userId) and not(myDecisionExists)
        val changesRequested: Op<Boolean> = currentOpen and latestDecisionExists(null, VersionReviewEntryKind.CHANGES_REQUESTED)
        val needsNewReview: Op<Boolean> = Reviews.closedAt.isNotNull()

        val ownedFlag = owned.asFlag("review_inbox_owned")
        val subscribedFlag = subscribed.asFlag("review_inbox_subscribed")
        val awaitingFlag = awaitingMyReview.asFlag("review_inbox_awaiting")
        val changesFlag = changesRequested.asFlag("review_inbox_changes")
        val needsFlag = needsNewReview.asFlag("review_inbox_needs")

        fun predicate(filter: ReviewInboxFilter): Op<Boolean> {
            var op = Contracts.active() and Systems.active() and Domains.active() and ContractVersions.active() and
                (ContractVersions.lifecycle eq Lifecycle.PROPOSED.name) and latestReview
            filter.q?.takeIf { it.isNotBlank() }?.let { query ->
                op = op and (Contracts.name.containsNormalized(query) or ContractVersions.version.containsNormalized(query))
            }
            op = op and when (filter.scope) {
                ReviewInboxScope.RELATED -> owned or subscribed
                ReviewInboxScope.OWNED -> owned
                ReviewInboxScope.FOLLOWED -> subscribed
                ReviewInboxScope.ALL -> Op.TRUE
            }
            filter.attention?.let { attention ->
                op = op and when (attention) {
                    ReviewInboxAttention.AWAITING_MY_REVIEW -> awaitingMyReview
                    ReviewInboxAttention.CHANGES_REQUESTED -> changesRequested
                    ReviewInboxAttention.NEEDS_NEW_REVIEW -> needsNewReview
                }
            }
            return op
        }

        private fun latestDecisionExists(authorId: UInt?, kind: VersionReviewEntryKind?): Op<Boolean> {
            val later = Entries.alias("review_inbox_later_decision_${authorId ?: 0u}_${kind?.name ?: "any"}")
            val laterDecision = later.select(later[Entries.id]).where {
                (later[Entries.reviewId] eq Entries.reviewId) and
                    (later[Entries.authorId] eq Entries.authorId) and
                    (later[Entries.kind] neq VersionReviewEntryKind.COMMENT.name) and
                    (later[Entries.id] greater Entries.id)
            }
            var predicate: Op<Boolean> = (Entries.reviewId eq Reviews.id) and
                (Entries.kind neq VersionReviewEntryKind.COMMENT.name) and notExists(laterDecision)
            authorId?.let { predicate = predicate and (Entries.authorId eq it) }
            kind?.let { predicate = predicate and (Entries.kind eq it.name) }
            return exists(Entries.select(Entries.id).where { predicate })
        }
    }

    private fun ResultRow.toInboxRow(
        caller: CallerPrincipal,
        memberships: Set<UInt>,
        activeCaller: Boolean,
        facts: VersionReviewSummaryFacts,
        rules: Rules,
    ): ReviewInboxRow {
        val reviewId = this[Reviews.id].value
        val decisions = facts.decisions[reviewId].orEmpty()
        val current = this[Reviews.contentRevision] == this[ContractVersions.contentRevision] &&
            this[Reviews.contentSha256] == this[ContractVersions.contentSha256]
        val closed = this[Reviews.closedAt] != null
        val owner = ownerRef()
        val ownership = Ownership(this[Contracts.ownerTeamId]?.value, this[Contracts.ownerUserId]?.value)
        val owned = this[rules.ownedFlag]
        val needsNewReview = this[rules.needsFlag]
        return ReviewInboxRow(
            id = reviewId,
            contract = ReviewInboxContractRef(
                id = this[Contracts.id].value,
                name = this[Contracts.name],
                type = ContractType.valueOf(this[Contracts.type]),
                system = RefSummary(this[Systems.id].value, this[Systems.name]),
                domain = RefSummary(this[Domains.id].value, this[Domains.name]),
                owner = owner,
                canWrite = canWriteContract(caller, ownership, memberships),
            ),
            version = ReviewInboxVersionRef(
                this[ContractVersions.id].value,
                this[ContractVersions.version],
                this[ContractVersions.contentRevision],
            ),
            review = ReviewInboxReview(
                status = when {
                    !current -> VersionReviewStatus.OUTDATED
                    closed -> VersionReviewStatus.CLOSED
                    else -> VersionReviewStatus.OPEN
                },
                contentRevision = this[Reviews.contentRevision],
                closeReason = this[Reviews.closeReason]?.let(VersionReviewCloseReason::valueOf),
                requestedBy = ReviewUserResponse(
                    this[Reviews.requestedBy].value,
                    this[requester[Users.name]],
                    this[requester[Users.markedAsDeleted]],
                ),
                requestedAt = this[Reviews.requestedAt],
                approvalCount = decisions.values.count { it == VersionReviewEntryKind.APPROVED.name }.toLong(),
                changesRequestedCount = decisions.values.count {
                    it == VersionReviewEntryKind.CHANGES_REQUESTED.name
                }.toLong(),
                myDecision = when (decisions[caller.userId]) {
                    VersionReviewEntryKind.APPROVED.name -> VersionReviewDecision.APPROVED
                    VersionReviewEntryKind.CHANGES_REQUESTED.name -> VersionReviewDecision.CHANGES_REQUESTED
                    else -> null
                },
            ),
            subscribed = this[rules.subscribedFlag],
            owned = owned,
            awaitingMyReview = this[rules.awaitingFlag],
            changesRequested = this[rules.changesFlag],
            needsNewReview = needsNewReview,
            canRequest = activeCaller && needsNewReview &&
                canWriteContract(caller, ownership, memberships),
        )
    }
}

private fun Op<Boolean>.asFlag(name: String) =
    Case().When(this, booleanLiteral(true)).Else(booleanLiteral(false)).alias(name)
