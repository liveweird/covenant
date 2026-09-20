package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

@Serializable
enum class SupportStatus { UNSPECIFIED, SUPPORTED, MAINTENANCE, END_OF_LIFE }

@Serializable
data class ReleaseLineUpdateRequest(
    val supportStatus: SupportStatus,
    val supportEndsOn: String? = null,
    val supportPolicy: String? = null,
    val recommendedVersionId: UInt? = null,
    val deprecatesOn: String? = null,
    val replacementContractId: UInt? = null,
    val replacementMajor: Int? = null,
    val migrationGuide: String? = null,
)

@Serializable
data class ReleaseLineVersionSummary(val id: UInt, val version: String, val lifecycle: Lifecycle)

@Serializable
data class ReleaseLineReplacement(
    val contractId: UInt,
    val contractName: String?,
    val major: Int?,
    val available: Boolean,
)

@Serializable
data class ReleaseLineResponse(
    val id: UInt,
    val contractId: UInt,
    val major: Int,
    val supportStatus: SupportStatus,
    val supportEndsOn: String?,
    val supportPolicy: String?,
    val recommendedVersionId: UInt?,
    val deprecatesOn: String?,
    val replacement: ReleaseLineReplacement?,
    val migrationGuide: String?,
    val latestVersion: ReleaseLineVersionSummary?,
    val recommendedVersion: ReleaseLineVersionSummary?,
    val versionCount: Long,
    val updatedAt: Long,
)

typealias ReleaseLinePageResponse = PageResponse<ReleaseLineResponse>
data class ReleaseLineListResult(val items: List<ReleaseLineResponse>, val total: Long)

data class ReleaseLineChange(val changed: Boolean, val response: ReleaseLineResponse)
