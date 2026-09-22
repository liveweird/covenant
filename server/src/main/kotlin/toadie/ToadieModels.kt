package ch.nokillswit.toadie

import ch.nokillswit.infra.paging.PageResponse
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

const val MAX_TOADIE_CONNECTIONS = 10
const val MAX_TOADIE_LINKS = 100
const val MAX_TOADIE_NAME_LENGTH = 100
const val MAX_TOADIE_URL_LENGTH = 2048
const val MAX_MIGRATION_REPORT_BYTES = 8 * 1024 * 1024
internal const val MIGRATION_REPORT_ENVELOPE_RESERVE_BYTES = 256 * 1024

@Serializable
data class ToadieMapping(
    val serviceBlueprint: String = "service",
    val apiBlueprint: String = "api",
    val providesRelation: String = "provides_apis",
    val consumesRelation: String = "consumes_apis",
    val systemRelation: String = "system",
)

@Serializable
data class ToadieRegistryMapping(
    val domainBlueprint: String = "domain",
    val systemDomainRelation: String? = "domain",
    val domainParentRelation: String? = "parent_domain",
    val flattenDomains: Boolean,
    val domainDescriptionProperty: String? = null,
    val systemDescriptionProperty: String? = null,
    val teamDescriptionProperty: String? = null,
)

@Serializable
enum class ToadieAdoptionKind { API_MAJOR_LINE, DATASET_CONTRACT_VERSION }

@Serializable
data class ToadieAdoptionMapping(
    val blueprint: String = "api_adoption",
    val kind: ToadieAdoptionKind = ToadieAdoptionKind.API_MAJOR_LINE,
    val consumerRelation: String = "consumer",
    val targetRelation: String = "api",
    val environmentRelation: String? = "environment",
    val valueProperty: String = "major_line",
    val statusProperty: String? = "status",
    val declaredByProperty: String? = "declared_by",
    val verifiedAtProperty: String? = "verified_at",
    val notesProperty: String? = "notes",
)

@Serializable
enum class ToadieAdoptionAvailability { NOT_CONFIGURED, NOT_SCANNED, BLUEPRINT_MISSING, AVAILABLE }

@Serializable
enum class ToadieAdoptionEnvironmentScope { ALL, SPECIFIC, UNKNOWN }

@Serializable
enum class ToadieCacheState { UNLINKED, NEVER_SYNCED, CURRENT, STALE, DISABLED, DISCONNECTED }

@Serializable
enum class ToadieRegistryKind { DOMAIN, SYSTEM, TEAM }

@Serializable
enum class ToadieRegistrySourceStatus { AVAILABLE, MISSING, CONFLICT, DISCONNECTED }

@Serializable
enum class ToadieRegistryAction { IMPORT, LINK, UPDATE }

@Serializable
data class ToadieCacheStatus(
    val state: ToadieCacheState,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val refreshing: Boolean,
    val lastErrorCode: String?,
)

@Serializable
data class ToadieRegistrySource(
    val connectionId: UInt,
    val connectionName: String,
    val entityId: String,
    val identifier: String,
    val title: String,
    val url: String?,
    val status: ToadieRegistrySourceStatus,
    val lastSyncedAt: Long?,
    val lastErrorCode: String?,
    val descriptionSynced: Boolean,
    val fallbackDomainId: UInt? = null,
    val cache: ToadieCacheStatus,
)

@Serializable
data class ToadieRegistryCandidate(
    val entityId: String,
    val identifier: String,
    val title: String,
    val description: String?,
    val remoteUpdatedAt: Long,
    val parentEntityId: String?,
    val parentIdentifier: String?,
    val parentTitle: String?,
    val linkedLocalId: UInt?,
    val linkedLocalName: String?,
    val fallbackDomainId: UInt? = null,
    val issues: List<String>,
)

@Serializable
data class ToadieRegistryCandidatePageResponse(
    val items: List<ToadieRegistryCandidate>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val cache: ToadieCacheStatus,
)

@Serializable
data class ToadieRegistrySelection(
    val entityId: String,
    val localId: UInt? = null,
    val fallbackDomainId: UInt? = null,
)

@Serializable
data class ToadieRegistryPreviewRequest(
    val kind: ToadieRegistryKind,
    val items: List<ToadieRegistrySelection>,
)

@Serializable
data class ToadieRegistryApplyRequest(
    val kind: ToadieRegistryKind,
    val items: List<ToadieRegistrySelection>,
    val expectedPlanToken: String,
)

@Serializable
data class ToadieRegistryLocalState(
    val name: String,
    val description: String?,
    val domainId: UInt?,
)

@Serializable
data class ToadieRegistryPreviewItem(
    val entityId: String,
    val localId: UInt?,
    val action: ToadieRegistryAction,
    val before: ToadieRegistryLocalState?,
    val after: ToadieRegistryLocalState,
    val issues: List<String>,
)

@Serializable
data class ToadieRegistryPreviewResponse(
    val kind: ToadieRegistryKind,
    val cache: ToadieCacheStatus,
    val planToken: String,
    val canApply: Boolean,
    val items: List<ToadieRegistryPreviewItem>,
)

@Serializable
data class ToadieRegistryApplyItem(
    val entityId: String,
    val localId: UInt,
    val action: ToadieRegistryAction,
)

@Serializable
data class ToadieRegistryApplyResponse(
    val kind: ToadieRegistryKind,
    val items: List<ToadieRegistryApplyItem>,
)

@Serializable
data class ToadieConnectionRequest(
    val name: String,
    val baseUrl: String,
    val browserUrl: String,
    val apiKey: String? = null,
    val enabled: Boolean,
    val refreshIntervalMinutes: Int,
    val mapping: ToadieMapping = ToadieMapping(),
    val registryMapping: ToadieRegistryMapping? = null,
    val adoptionMapping: ToadieAdoptionMapping? = null,
)

@Serializable
data class ToadieConnectionResponse(
    val id: UInt,
    val name: String,
    val baseUrl: String,
    val browserUrl: String,
    val enabled: Boolean,
    val refreshIntervalMinutes: Int,
    val mapping: ToadieMapping,
    val registryMapping: ToadieRegistryMapping?,
    val adoptionMapping: ToadieAdoptionMapping?,
    val hasApiKey: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val refreshing: Boolean,
    val lastErrorCode: String?,
    val stale: Boolean,
)

typealias ToadieConnectionPageResponse = PageResponse<ToadieConnectionResponse>

@Serializable
data class ToadieConnectionRef(val id: UInt, val name: String, val browserUrl: String)

@Serializable
data class ToadieEntityRef(val entityId: String, val identifier: String, val title: String, val url: String?)

@Serializable
enum class ToadieLinkStatus { AVAILABLE, MISSING, DISCONNECTED }

@Serializable
data class ToadieLinkResponse(
    val id: UInt,
    val connectionId: UInt,
    val apiEntityId: String,
    val identifier: String,
    val title: String,
    val url: String?,
    val status: ToadieLinkStatus,
)

@Serializable
data class ToadieLinksResponse(
    val contractId: UInt,
    val connection: ToadieConnectionRef?,
    val cache: ToadieCacheStatus,
    val items: List<ToadieLinkResponse>,
)

@Serializable
data class ToadieLinksRequest(val connectionId: UInt?, val apiEntityIds: List<String>)

@Serializable
enum class ToadieUsageRole { PROVIDER, CONSUMER }

@Serializable
data class ToadieUsageRow(
    val id: String,
    val identifier: String,
    val title: String,
    val url: String?,
    val roles: List<ToadieUsageRole>,
    val providedApiEntityIds: List<String>,
    val consumedApiEntityIds: List<String>,
    val systems: List<ToadieEntityRef>,
    val teams: List<ToadieEntityRef>,
    val version: String? = null,
    val releaseLine: String? = null,
)

@Serializable
data class ToadieUsageResponse(
    val items: List<ToadieUsageRow>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val connection: ToadieConnectionRef?,
    val cache: ToadieCacheStatus,
)

@Serializable
data class ToadieAdoptionRow(
    val id: String,
    val identifier: String,
    val title: String,
    val url: String?,
    val consumer: ToadieEntityRef,
    val target: ToadieEntityRef,
    val environment: ToadieEntityRef?,
    val environmentScope: ToadieAdoptionEnvironmentScope,
    val kind: ToadieAdoptionKind,
    val value: String?,
    val status: String?,
    val declaredBy: String?,
    val verifiedAt: Long?,
    val notes: String?,
    val matchesConsumption: Boolean,
)

@Serializable
data class ToadieAdoptionsProjection(
    val availability: ToadieAdoptionAvailability,
    val items: List<ToadieAdoptionRow>,
)

@Serializable
data class ToadieAdoptionResponse(
    val items: List<ToadieAdoptionRow>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val connection: ToadieConnectionRef?,
    val cache: ToadieCacheStatus,
    val availability: ToadieAdoptionAvailability,
)

data class ToadieFetchConfig(
    val baseUrl: String,
    val apiKey: String,
    val mapping: ToadieMapping,
    val registryMapping: ToadieRegistryMapping? = null,
    val adoptionMapping: ToadieAdoptionMapping? = null,
    val knownRevision: Long? = null,
)

@Serializable
data class ToadieEntitySnapshot(
    val id: String,
    val blueprint: String,
    val identifier: String,
    val title: String,
    val teamIdentifiers: List<String>,
    val relations: Map<String, List<String>>,
    val updatedAt: Long,
    val registryDescription: String? = null,
    val registryErrorCode: String? = null,
    val scalarProperties: Map<String, String?> = emptyMap(),
)

sealed interface ToadieFetchResult

@Serializable
data class ToadieSnapshot(
    val entities: List<ToadieEntitySnapshot>,
    val systemBlueprint: String?,
    val fetchedAt: Long,
    val revision: Long,
    val adoptionAvailability: ToadieAdoptionAvailability = ToadieAdoptionAvailability.NOT_CONFIGURED,
    val adoptionEnvironmentBlueprint: String? = null,
) : ToadieFetchResult

data class ToadieUnchanged(val revision: Long, val checkedAt: Long) : ToadieFetchResult

class ToadieFetchException(val code: String) : RuntimeException(code)

interface ToadieGraphqlClient {
    suspend fun fetch(config: ToadieFetchConfig): ToadieFetchResult
}

val ToadieGraphqlClientKey = AttributeKey<ToadieGraphqlClient>("ToadieGraphqlClient")
