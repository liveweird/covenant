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
enum class ToadieCacheState { UNLINKED, NEVER_SYNCED, CURRENT, STALE, DISABLED, DISCONNECTED }

@Serializable
data class ToadieCacheStatus(
    val state: ToadieCacheState,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val refreshing: Boolean,
    val lastErrorCode: String?,
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

data class ToadieFetchConfig(
    val baseUrl: String,
    val apiKey: String,
    val mapping: ToadieMapping,
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
)

sealed interface ToadieFetchResult

@Serializable
data class ToadieSnapshot(
    val entities: List<ToadieEntitySnapshot>,
    val systemBlueprint: String?,
    val fetchedAt: Long,
    val revision: Long,
) : ToadieFetchResult

data class ToadieUnchanged(val revision: Long, val checkedAt: Long) : ToadieFetchResult

class ToadieFetchException(val code: String) : RuntimeException(code)

interface ToadieGraphqlClient {
    suspend fun fetch(config: ToadieFetchConfig): ToadieFetchResult
}

val ToadieGraphqlClientKey = AttributeKey<ToadieGraphqlClient>("ToadieGraphqlClient")
