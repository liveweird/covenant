package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val VersionReviewServiceKey = AttributeKey<VersionReviewService>("VersionReviewService")

val VERSION_REVIEW_SORT_FIELDS = setOf("id", "requestedAt", "closedAt")
val VERSION_REVIEW_ENTRY_SORT_FIELDS = setOf("id", "createdAt")

class VersionReviewService(
    private val database: R2dbcDatabase,
    private val contracts: ContractService,
) {
    object Reviews : UIntIdTable("version_reviews") {
        val versionId = reference("version_id", ContractVersionService.ContractVersions)
        val contentRevision = long("content_revision")
        val contentSha256 = char("content_sha256", 64)
        val requestedBy = reference("requested_by", UserService.Users)
        val requestedAt = long("requested_at")
        val closedAt = long("closed_at").nullable()
        val closeReason = varchar("close_reason", 32).nullable()
    }

    object Entries : UIntIdTable("version_review_entries") {
        val reviewId = reference("review_id", Reviews)
        val kind = varchar("kind", 24)
        val body = varchar("body", MAX_BODY_LENGTH).nullable()
        val authorId = reference("author_id", UserService.Users)
        val createdAt = long("created_at")
    }

    private val reviewSort: Map<String, Column<*>> = mapOf(
        "id" to Reviews.id,
        "requestedAt" to Reviews.requestedAt,
        "closedAt" to Reviews.closedAt,
    )
    private val entrySort: Map<String, Column<*>> = mapOf(
        "id" to Entries.id,
        "createdAt" to Entries.createdAt,
    )

    suspend fun list(
        contractId: UInt,
        versionId: UInt,
        caller: CallerPrincipal,
        canWrite: Boolean,
        paging: PageRequest,
    ): VersionReviewListResult = suspendTransaction(database) {
        val version = activeVersion(contractId, versionId) ?: throw NotFoundException("Version not found")
        val predicate = Reviews.versionId eq versionId
        val total = Reviews.selectAll().where { predicate }.count()
        val rows = reviewRows(predicate).applyPaging(paging, reviewSort).toList()
        val hasOpen = Reviews.selectAll().where { predicate and Reviews.closedAt.isNull() }.count() > 0
        val activeCaller = isActiveUser(caller.userId)
        VersionReviewListResult(
            items = responses(rows, caller.userId, version),
            total = total,
            canRequest = activeCaller && canWrite &&
                Lifecycle.valueOf(version[ContractVersionService.ContractVersions.lifecycle]) == Lifecycle.PROPOSED &&
                !hasOpen,
            currentContentRevision = version[ContractVersionService.ContractVersions.contentRevision],
        )
    }

    suspend fun read(reviewId: UInt, caller: CallerPrincipal): VersionReviewResponse = suspendTransaction(database) {
        val row = activeReviewRow(reviewId) ?: throw NotFoundException("Version review not found")
        val version = versionFrom(row)
        responses(listOf(row), caller.userId, version).single()
    }

    suspend fun request(
        contractId: UInt,
        versionId: UInt,
        caller: CallerPrincipal,
        expectedContentRevision: Long,
    ): VersionReviewResponse = suspendTransaction(database) {
        contracts.requireCurrentWriter(caller, contractId)
        requireActiveCaller(caller.userId)
        requirePositiveRevision(expectedContentRevision)
        val version = activeVersion(contractId, versionId) ?: throw NotFoundException("Version not found")
        val revision = version[ContractVersionService.ContractVersions.contentRevision]
        if (expectedContentRevision != revision) staleRevision()
        if (Lifecycle.valueOf(version[ContractVersionService.ContractVersions.lifecycle]) != Lifecycle.PROPOSED) {
            throw ConflictException("Only a PROPOSED version can be sent for review")
        }
        if (Reviews.selectAll().where { (Reviews.versionId eq versionId) and Reviews.closedAt.isNull() }.count() > 0) {
            throw ConflictException("This version already has an open review")
        }
        val id = Reviews.insert {
            it[Reviews.versionId] = versionId
            it[contentRevision] = revision
            it[contentSha256] = version[ContractVersionService.ContractVersions.contentSha256]
            it[requestedBy] = caller.userId
            it[requestedAt] = nowMillis()
        }[Reviews.id].value
        val row = activeReviewRow(id) ?: error("Created version review vanished")
        responses(listOf(row), caller.userId, version).single()
    }

    suspend fun listEntries(reviewId: UInt, paging: PageRequest): VersionReviewEntryListResult = suspendTransaction(database) {
        activeReviewRow(reviewId) ?: throw NotFoundException("Version review not found")
        val predicate = Entries.reviewId eq reviewId
        val total = Entries.selectAll().where { predicate }.count()
        val rows = entryRows(predicate).applyPaging(paging, entrySort).toList()
        VersionReviewEntryListResult(rows.map { it.toEntryResponse() }, total)
    }

    suspend fun addEntry(
        reviewId: UInt,
        caller: CallerPrincipal,
        request: CreateVersionReviewEntryRequest,
    ): Pair<VersionReviewEntryResponse, VersionReviewResponse> = suspendTransaction(database) {
        val contractId = contractIdForActiveReview(reviewId) ?: throw NotFoundException("Version review not found")
        lockActiveContract(contractId)
        requireActiveCaller(caller.userId)
        val review = activeReviewRow(reviewId) ?: throw NotFoundException("Version review not found")
        if (request.kind != VersionReviewEntryKind.COMMENT && review[Reviews.requestedBy].value == caller.userId) {
            throw ForbiddenException("The review requester cannot approve or request changes")
        }
        requirePositiveRevision(request.expectedContentRevision)
        val version = versionFrom(review)
        val revision = version[ContractVersionService.ContractVersions.contentRevision]
        if (request.expectedContentRevision != revision ||
            review[Reviews.contentRevision] != revision || review[Reviews.closedAt] != null
        ) {
            staleRevision()
        }
        val body = validatedBody(request.kind, request.body)
        val id = Entries.insert {
            it[Entries.reviewId] = reviewId
            it[kind] = request.kind.name
            it[Entries.body] = body
            it[authorId] = caller.userId
            it[createdAt] = nowMillis()
        }[Entries.id].value
        val entry = entryRows(Entries.id eq id).toList().single().toEntryResponse()
        entry to responses(listOf(review), caller.userId, version).single()
    }

    suspend fun requireEntryPreamble(reviewId: UInt, caller: CallerPrincipal) = suspendTransaction(database) {
        requireActiveCaller(caller.userId)
        val review = activeReviewRow(reviewId) ?: throw NotFoundException("Version review not found")
        val version = versionFrom(review)
        if (review[Reviews.closedAt] != null ||
            review[Reviews.contentRevision] != version[ContractVersionService.ContractVersions.contentRevision] ||
            review[Reviews.contentSha256] != version[ContractVersionService.ContractVersions.contentSha256]
        ) staleRevision()
    }

    suspend fun requireActiveParticipant(caller: CallerPrincipal) = suspendTransaction(database) {
        requireActiveCaller(caller.userId)
    }

    suspend fun readEntry(reviewId: UInt, entryId: UInt): VersionReviewEntryResponse = suspendTransaction(database) {
        activeReviewRow(reviewId) ?: throw NotFoundException("Version review not found")
        entryRows((Entries.id eq entryId) and (Entries.reviewId eq reviewId))
            .toList().singleOrNull()?.toEntryResponse() ?: throw NotFoundException("Version review entry not found")
    }

    private suspend fun responses(
        rows: List<ResultRow>,
        callerId: UInt,
        version: ResultRow,
    ): List<VersionReviewResponse> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[Reviews.id].value }
        val facts = versionReviewSummaryFacts(ids)
        val activeCaller = isActiveUser(callerId)
        return rows.map { row ->
            val id = row[Reviews.id].value
            val latestDecisions = facts.decisions[id].orEmpty()
            val currentRevision = version[ContractVersionService.ContractVersions.contentRevision]
            val currentHash = version[ContractVersionService.ContractVersions.contentSha256]
            val current = row[Reviews.contentRevision] == currentRevision && row[Reviews.contentSha256] == currentHash
            val closed = row[Reviews.closedAt] != null
            val open = current && !closed
            val requesterId = row[Reviews.requestedBy].value
            VersionReviewResponse(
                id = id,
                contractId = version[ContractVersionService.ContractVersions.contractId].value,
                versionId = version[ContractVersionService.ContractVersions.id].value,
                contentRevision = row[Reviews.contentRevision],
                contentSha256 = row[Reviews.contentSha256],
                requestedBy = ReviewUserResponse(requesterId, row[UserService.Users.name], row[UserService.Users.markedAsDeleted]),
                requestedAt = row[Reviews.requestedAt],
                closedAt = row[Reviews.closedAt],
                closeReason = row[Reviews.closeReason]?.let(VersionReviewCloseReason::valueOf),
                status = when {
                    !current -> VersionReviewStatus.OUTDATED
                    closed -> VersionReviewStatus.CLOSED
                    else -> VersionReviewStatus.OPEN
                },
                isCurrentContent = current,
                approvalCount = latestDecisions.values.count { it == VersionReviewEntryKind.APPROVED.name }.toLong(),
                changesRequestedCount = latestDecisions.values.count { it == VersionReviewEntryKind.CHANGES_REQUESTED.name }.toLong(),
                entryCount = facts.entryCounts[id] ?: 0,
                myDecision = when (latestDecisions[callerId]) {
                    VersionReviewEntryKind.APPROVED.name -> VersionReviewDecision.APPROVED
                    VersionReviewEntryKind.CHANGES_REQUESTED.name -> VersionReviewDecision.CHANGES_REQUESTED
                    else -> null
                },
                canComment = activeCaller && open,
                canDecide = activeCaller && open && requesterId != callerId,
            )
        }
    }

    private fun reviewRows(predicate: Op<Boolean>) = Reviews
        .join(ContractVersionService.ContractVersions, JoinType.INNER, Reviews.versionId, ContractVersionService.ContractVersions.id)
        .join(ContractService.Contracts, JoinType.INNER, ContractVersionService.ContractVersions.contractId, ContractService.Contracts.id)
        .join(UserService.Users, JoinType.INNER, Reviews.requestedBy, UserService.Users.id)
        .selectAll()
        .where {
            predicate and ContractVersionService.ContractVersions.active() and ContractService.Contracts.active()
        }

    private fun entryRows(predicate: Op<Boolean>) = Entries
        .join(UserService.Users, JoinType.INNER, Entries.authorId, UserService.Users.id)
        .selectAll().where { predicate }

    private suspend fun activeReviewRow(id: UInt): ResultRow? = reviewRows(Reviews.id eq id).toList().singleOrNull()

    private suspend fun contractIdForActiveReview(id: UInt): UInt? = reviewRows(Reviews.id eq id)
        .map { it[ContractService.Contracts.id].value }.toList().singleOrNull()

    private suspend fun activeVersion(contractId: UInt, versionId: UInt): ResultRow? = ContractVersionService.ContractVersions
        .innerJoin(ContractService.Contracts)
        .selectAll()
        .where {
            (ContractVersionService.ContractVersions.id eq versionId) and
                (ContractVersionService.ContractVersions.contractId eq contractId) and
                ContractVersionService.ContractVersions.active() and ContractService.Contracts.active()
        }.toList().singleOrNull()

    private fun versionFrom(review: ResultRow): ResultRow = review

    private suspend fun lockActiveContract(contractId: UInt) {
        val exists = ContractService.Contracts.select(ContractService.Contracts.id)
            .where { (ContractService.Contracts.id eq contractId) and ContractService.Contracts.active() }
            .forUpdate().toList().isNotEmpty()
        if (!exists) throw NotFoundException("Contract not found")
    }

    private suspend fun isActiveUser(userId: UInt): Boolean = UserService.Users.select(UserService.Users.id)
        .where { (UserService.Users.id eq userId) and UserService.Users.active() }.count() > 0

    private suspend fun requireActiveCaller(userId: UInt) {
        if (!isActiveUser(userId)) throw ForbiddenException("Only an active user may participate in a review")
    }

    private fun ResultRow.toEntryResponse() = VersionReviewEntryResponse(
        id = this[Entries.id].value,
        reviewId = this[Entries.reviewId].value,
        kind = VersionReviewEntryKind.valueOf(this[Entries.kind]),
        body = this[Entries.body],
        author = ReviewUserResponse(
            this[Entries.authorId].value,
            this[UserService.Users.name],
            this[UserService.Users.markedAsDeleted],
        ),
        createdAt = this[Entries.createdAt],
    )

    private fun validatedBody(kind: VersionReviewEntryKind, raw: String?): String? {
        val body = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (body != null && body.length > MAX_BODY_LENGTH) {
            throw BadRequestException("body must be at most $MAX_BODY_LENGTH characters")
        }
        if (kind != VersionReviewEntryKind.APPROVED && body == null) {
            throw BadRequestException("body must not be blank for $kind")
        }
        return body
    }

    private fun staleRevision(): Nothing = throw ConflictException("The review content changed underneath you — reload and retry")

    private fun requirePositiveRevision(revision: Long) {
        if (revision < 1) throw BadRequestException("expectedContentRevision must be at least 1")
    }

    private companion object {
        const val MAX_BODY_LENGTH = 4000
    }
}

internal data class VersionReviewSummaryFacts(
    val entryCounts: Map<UInt, Long>,
    val decisions: Map<UInt, Map<UInt, String>>,
)

/** Bounded summary projection: counts plus one latest decision row per reviewer; bodies stay in /entries. */
internal suspend fun versionReviewSummaryFacts(reviewIds: List<UInt>): VersionReviewSummaryFacts {
    if (reviewIds.isEmpty()) return VersionReviewSummaryFacts(emptyMap(), emptyMap())
    val entries = VersionReviewService.Entries
    val count = entries.id.count()
    val entryCounts = entries.select(entries.reviewId, count)
        .where { entries.reviewId inList reviewIds }
        .groupBy(entries.reviewId)
        .map { it[entries.reviewId].value to it[count] }.toList().toMap()
    val later = entries.alias("later_review_decisions")
    val laterDecision = later
        .select(later[entries.id])
        .where {
            (later[entries.reviewId] eq entries.reviewId) and
                (later[entries.authorId] eq entries.authorId) and
                (later[entries.kind] neq VersionReviewEntryKind.COMMENT.name) and
                (later[entries.id] greater entries.id)
        }
    val decisions = entries.select(entries.reviewId, entries.authorId, entries.kind)
        .where {
            (entries.reviewId inList reviewIds) and
                (entries.kind neq VersionReviewEntryKind.COMMENT.name) and notExists(laterDecision)
        }
        .map { Triple(it[entries.reviewId].value, it[entries.authorId].value, it[entries.kind]) }
        .toList().groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, values) -> values.toMap() }
    return VersionReviewSummaryFacts(entryCounts, decisions)
}

/** Must be called while the active parent contract is locked by the surrounding transaction. */
internal suspend fun closeOpenVersionReview(
    versionId: UInt,
    reason: VersionReviewCloseReason,
    stamp: Long = nowMillis(),
) {
    VersionReviewService.Reviews.update({
        (VersionReviewService.Reviews.versionId eq versionId) and VersionReviewService.Reviews.closedAt.isNull()
    }) {
        it[closedAt] = stamp
        it[closeReason] = reason.name
    }
}
