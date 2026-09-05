package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The contract (V9): the logical identity of an integration contract inside a System — its
 * type, its owner (a team XOR a user) and a name unique within the system. The documents are
 * its VERSIONS (`ContractVersion.kt`). Everyone authenticated reads every contract; writing one
 * (and its versions) takes the owning team's membership, the owning user, or ADMIN
 * (`ContractAccess.kt`); ownership transfer is ADMIN only.
 */
const val MAX_CONTRACT_NAME_LENGTH = 100
const val MAX_CONTRACT_DESCRIPTION_LENGTH = 2000

@Serializable
enum class OwnerKind { TEAM, USER }

/** Who owns a contract, with the display name joined at read time. */
@Serializable
data class OwnerRef(val kind: OwnerKind, val id: UInt, val name: String, val deleted: Boolean = false)

@Serializable
data class RefSummary(val id: UInt, val name: String)

/** The contract list's glimpse of the highest version. */
@Serializable
data class LatestVersionSummary(
    val id: UInt,
    val version: String,
    val lifecycle: Lifecycle,
    val checkErrors: Int,
    val checkWarnings: Int,
    val checkComplete: Boolean,
)

@Serializable
data class ContractCreateRequest(
    val systemId: UInt,
    val type: ContractType,
    val name: String,
    val description: String? = null,
    val ownerTeamId: UInt? = null,
    val ownerUserId: UInt? = null,
)

@Serializable
data class ContractUpdateRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class OwnerUpdateRequest(
    val ownerTeamId: UInt? = null,
    val ownerUserId: UInt? = null,
)

@Serializable
data class ContractResponse(
    val id: UInt,
    val system: RefSummary,
    val domain: RefSummary,
    val type: ContractType,
    val name: String,
    val description: String?,
    val owner: OwnerRef,
    val latestVersion: LatestVersionSummary?,
    val versionCount: Int,
    /** Server-computed for the caller — the SPA never reimplements the writer rule. */
    val canWrite: Boolean,
    /** Whether the CALLER follows the contract (V13); every event on it then reaches their bell. */
    val subscribed: Boolean,
    val subscriberCount: Int,
    val createdBy: UInt,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias ContractPageResponse = PageResponse<ContractResponse>

/** One facet bucket — a value (an enum name, or `NONE` for "no version yet") and how many contracts land in it. */
@Serializable
data class FacetCount(val value: String, val count: Long)

@Serializable
data class NamedFacetCount(val id: UInt, val name: String, val count: Long)

@Serializable
data class ErrorFacets(val withErrors: Long, val clean: Long)

/**
 * Facet counts for the contracts list/tree (milestone 3). Classic faceting: each dimension is
 * counted with every OTHER filter applied and its own dimension lifted, so a count answers
 * "what would I get by picking this value".
 */
@Serializable
data class FacetsResponse(
    val type: List<FacetCount>,
    /** Of the latest version; `NONE` = contracts without a version. */
    val lifecycle: List<FacetCount>,
    val domain: List<NamedFacetCount>,
    val system: List<NamedFacetCount>,
    val ownerTeam: List<NamedFacetCount>,
    val hasErrors: ErrorFacets,
)

enum class FacetDimension { TYPE, LIFECYCLE, DOMAIN, SYSTEM, OWNER_TEAM, HAS_ERRORS }

data class ContractListFilter(
    val domainId: UInt? = null,
    val systemId: UInt? = null,
    val types: List<ContractType> = emptyList(),
    val ownerTeamId: UInt? = null,
    val ownerUserId: UInt? = null,
    /** Of the LATEST version (a contract with no version yet never matches a lifecycle filter). */
    val lifecycles: List<Lifecycle> = emptyList(),
    /** Substring over name OR description (case- and accent-insensitive). */
    val q: String? = null,
    val hasErrors: Boolean? = null,
) {
    /** The same filter with one dimension lifted — the facet of that dimension counts across all its values. */
    fun lifting(dimension: FacetDimension): ContractListFilter = when (dimension) {
        FacetDimension.TYPE -> copy(types = emptyList())
        FacetDimension.LIFECYCLE -> copy(lifecycles = emptyList())
        FacetDimension.DOMAIN -> copy(domainId = null)
        FacetDimension.SYSTEM -> copy(systemId = null)
        FacetDimension.OWNER_TEAM -> copy(ownerTeamId = null)
        FacetDimension.HAS_ERRORS -> copy(hasErrors = null)
    }
}

data class ContractListResult(val items: List<ContractResponse>, val total: Long)

/** The ownership pair as stored — exactly one side set (the V9 CHECK). */
data class Ownership(val teamId: UInt?, val userId: UInt?) {
    init {
        require((teamId == null) != (userId == null)) { "exactly one owner side must be set" }
    }
}

fun sanitizedContractCreate(request: ContractCreateRequest) = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

fun sanitizedContractUpdate(request: ContractUpdateRequest) = ContractUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

fun validateContractNameAndDescription(name: String, description: String?) {
    requireNameAndDescription(name, description, MAX_CONTRACT_NAME_LENGTH, MAX_CONTRACT_DESCRIPTION_LENGTH)
}

/** Exactly one owner side — the wire twin of the V9 CHECK. */
fun ownershipOf(ownerTeamId: UInt?, ownerUserId: UInt?): Ownership {
    if ((ownerTeamId == null) == (ownerUserId == null)) {
        throw BadRequestException("Exactly one of ownerTeamId and ownerUserId must be set")
    }
    return Ownership(ownerTeamId, ownerUserId)
}

/** The create rules — enforced by the route AND re-checked by the service (owner assignability is the guard's). */
fun validateContractCreate(request: ContractCreateRequest): Ownership {
    validateContractNameAndDescription(request.name, request.description)
    return ownershipOf(request.ownerTeamId, request.ownerUserId)
}

fun validateContractUpdate(request: ContractUpdateRequest) = validateContractNameAndDescription(request.name, request.description)

@Serializable
data class ContractExportResponse(
    val contract: ContractResponse,
    val versions: List<ExportedVersion>,
)

@Serializable
data class ExportedVersion(
    val version: String,
    val lifecycle: Lifecycle,
    val format: ch.nokillswit.contracts.checks.DocumentFormat,
    val content: String,
)
