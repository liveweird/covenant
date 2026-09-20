package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewInboxScope { RELATED, OWNED, FOLLOWED, ALL }

@Serializable
enum class ReviewInboxAttention { AWAITING_MY_REVIEW, CHANGES_REQUESTED, NEEDS_NEW_REVIEW }

@Serializable
data class ReviewInboxContractRef(
    val id: UInt,
    val name: String,
    val type: ContractType,
    val system: RefSummary,
    val domain: RefSummary,
    val owner: OwnerRef,
    val canWrite: Boolean,
)

@Serializable
data class ReviewInboxVersionRef(
    val id: UInt,
    val version: String,
    val contentRevision: Long,
)

@Serializable
data class ReviewInboxReview(
    val status: VersionReviewStatus,
    val contentRevision: Long,
    val closeReason: VersionReviewCloseReason?,
    val requestedBy: ReviewUserResponse,
    val requestedAt: Long,
    val approvalCount: Long,
    val changesRequestedCount: Long,
    val myDecision: VersionReviewDecision?,
)

@Serializable
data class ReviewInboxRow(
    val id: UInt,
    val contract: ReviewInboxContractRef,
    val version: ReviewInboxVersionRef,
    val review: ReviewInboxReview,
    val subscribed: Boolean,
    val owned: Boolean,
    val awaitingMyReview: Boolean,
    val changesRequested: Boolean,
    val needsNewReview: Boolean,
    val canRequest: Boolean,
)

@Serializable
data class ReviewInboxSummary(
    val total: Long,
    val awaitingMyReview: Long,
    val changesRequested: Long,
    val needsNewReview: Long,
)

typealias ReviewInboxPage = PageResponse<ReviewInboxRow>

data class ReviewInboxResult(val items: List<ReviewInboxRow>, val total: Long)

data class ReviewInboxFilter(
    val q: String? = null,
    val scope: ReviewInboxScope = ReviewInboxScope.RELATED,
    val attention: ReviewInboxAttention? = null,
)
