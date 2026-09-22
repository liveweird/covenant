package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.toadie.ToadieCacheStatus
import ch.nokillswit.toadie.ToadieConnectionRef
import ch.nokillswit.toadie.ToadieLinkResponse
import ch.nokillswit.toadie.ToadieUsageRow
import ch.nokillswit.toadie.ToadieAdoptionsProjection
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

@Serializable
enum class MigrationUsageScope { CONTRACT }

@Serializable
enum class MigrationVersionAdoption { UNKNOWN }

@Serializable
data class ReleaseLineMigrationReportResponse(
    val generatedAt: Long,
    val contractId: UInt,
    val contractName: String,
    val contractType: ContractType,
    val major: Int,
    val supportStatus: SupportStatus,
    val deprecatesOn: String?,
    val supportEndsOn: String?,
    val supportPolicy: String?,
    val migrationGuide: String?,
    val replacement: ReleaseLineReplacement?,
    val recommendedVersion: ReleaseLineVersionSummary?,
    val planUpdatedAt: Long,
    val usageScope: MigrationUsageScope,
    val versionAdoption: MigrationVersionAdoption,
    val connection: ToadieConnectionRef?,
    val cache: ToadieCacheStatus,
    val linkedApis: List<ToadieLinkResponse>,
    val services: List<ToadieUsageRow>,
    val adoptions: ToadieAdoptionsProjection,
)

typealias ReleaseLinePageResponse = PageResponse<ReleaseLineResponse>
data class ReleaseLineListResult(val items: List<ReleaseLineResponse>, val total: Long)

data class ReleaseLineChange(val changed: Boolean, val response: ReleaseLineResponse)
