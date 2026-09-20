package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

@Serializable
enum class VersionReviewCloseReason { CONTENT_CHANGED, WITHDRAWN, PUBLISHED }

@Serializable
enum class VersionReviewStatus { OPEN, OUTDATED, CLOSED }

@Serializable
enum class VersionReviewEntryKind { COMMENT, APPROVED, CHANGES_REQUESTED }

@Serializable
enum class VersionReviewDecision { APPROVED, CHANGES_REQUESTED }

@Serializable
data class ReviewUserResponse(val id: UInt, val name: String, val deleted: Boolean)

@Serializable
data class CreateVersionReviewRequest(val expectedContentRevision: Long)

@Serializable
data class CreateVersionReviewEntryRequest(
    val expectedContentRevision: Long,
    val kind: VersionReviewEntryKind,
    val body: String? = null,
)

@Serializable
data class VersionReviewResponse(
    val id: UInt,
    val contractId: UInt,
    val versionId: UInt,
    val contentRevision: Long,
    val contentSha256: String,
    val requestedBy: ReviewUserResponse,
    val requestedAt: Long,
    val closedAt: Long?,
    val closeReason: VersionReviewCloseReason?,
    val status: VersionReviewStatus,
    val isCurrentContent: Boolean,
    val approvalCount: Long,
    val changesRequestedCount: Long,
    val entryCount: Long,
    val myDecision: VersionReviewDecision?,
    val canComment: Boolean,
    val canDecide: Boolean,
)

@Serializable
data class VersionReviewEntryResponse(
    val id: UInt,
    val reviewId: UInt,
    val kind: VersionReviewEntryKind,
    val body: String?,
    val author: ReviewUserResponse,
    val createdAt: Long,
)

@Serializable
data class VersionReviewPageResponse(
    val items: List<VersionReviewResponse>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val canRequest: Boolean,
    val currentContentRevision: Long,
)

typealias VersionReviewEntryPageResponse = PageResponse<VersionReviewEntryResponse>

data class VersionReviewListResult(
    val items: List<VersionReviewResponse>,
    val total: Long,
    val canRequest: Boolean,
    val currentContentRevision: Long,
)

data class VersionReviewEntryListResult(val items: List<VersionReviewEntryResponse>, val total: Long)
