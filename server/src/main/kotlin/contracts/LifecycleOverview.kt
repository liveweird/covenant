package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.toadie.ToadieUsageSummary
import kotlinx.serialization.Serializable

@Serializable
enum class LifecycleDeadline { REACHED, NEXT_30_DAYS, NONE }
@Serializable
enum class LifecycleAttention { DEADLINE_SOON, SUPPORT_ENDED, MIGRATION_INCOMPLETE, USAGE_UNCERTAIN }

@Serializable
data class LifecycleContractRef(
    val id: UInt, val name: String, val type: ContractType,
    val system: RefSummary, val domain: RefSummary, val owner: OwnerRef, val canWrite: Boolean,
)

@Serializable
data class LifecycleOverviewRow(
    val id: UInt,
    val contract: LifecycleContractRef,
    val major: Int,
    val supportStatus: SupportStatus,
    val deprecatesOn: String?,
    val supportEndsOn: String?,
    val replacement: ReleaseLineReplacement?,
    val hasMigrationGuide: Boolean,
    val nextDeadline: String?,
    val deadlineSoon: Boolean,
    val supportEnded: Boolean,
    val migrationIncomplete: Boolean,
    val usageUncertain: Boolean,
    val usage: ToadieUsageSummary,
)

@Serializable
data class LifecycleOverviewSummary(
    val asOfDate: String,
    val total: Long,
    val deadlineSoon: Long,
    val supportEnded: Long,
    val migrationIncomplete: Long,
    val usageUncertain: Long,
    val ownerUser: List<NamedFacetCount>,
)

typealias LifecycleOverviewPage = PageResponse<LifecycleOverviewRow>
data class LifecycleOverviewResult(val items: List<LifecycleOverviewRow>, val total: Long)
data class LifecycleOverviewFilter(
    val contracts: ContractListFilter = ContractListFilter(),
    val supportStatuses: List<SupportStatus> = emptyList(),
    val deadline: LifecycleDeadline? = null,
    val attention: LifecycleAttention? = null,
)
