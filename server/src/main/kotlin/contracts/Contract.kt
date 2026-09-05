package ch.nokillswit.contracts

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
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
    val createdBy: UInt,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias ContractPageResponse = PageResponse<ContractResponse>

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
)

data class ContractListResult(val items: List<ContractResponse>, val total: Long)

/** The ownership pair as stored — exactly one side set (the V9 CHECK). */
data class Ownership(val teamId: UInt?, val userId: UInt?) {
    init {
        require((teamId == null) != (userId == null)) { "exactly one owner side must be set" }
    }
}

fun sanitizedContractCreate(request: ContractCreateRequest) = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    description = request.description?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() },
)

fun sanitizedContractUpdate(request: ContractUpdateRequest) = ContractUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = request.description?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() },
)

fun validateContractNameAndDescription(name: String, description: String?) {
    if (name.isBlank() || name.length > MAX_CONTRACT_NAME_LENGTH) {
        throw BadRequestException("Name must be 1-$MAX_CONTRACT_NAME_LENGTH characters")
    }
    if (description != null && description.length > MAX_CONTRACT_DESCRIPTION_LENGTH) {
        throw BadRequestException("Description must be at most $MAX_CONTRACT_DESCRIPTION_LENGTH characters")
    }
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
