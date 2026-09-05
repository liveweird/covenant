package ch.nokillswit.systems

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Systems (V8): the application exposing contracts, inside exactly one Domain — the middle of
 * the Domain → System → Contract hierarchy, ADMIN-curated. A PUT may move a system to another
 * domain; a system holding active contracts cannot be deleted (409, with the contracts feature).
 */
const val MAX_SYSTEM_NAME_LENGTH = 100
const val MAX_SYSTEM_DESCRIPTION_LENGTH = 2000

@Serializable
data class SystemRequest(
    val domainId: UInt,
    val name: String,
    val description: String? = null,
)

@Serializable
data class SystemResponse(
    val id: UInt,
    val domainId: UInt,
    /** The domain's display name, joined at read time so lists never fan out per row. */
    val domainName: String,
    val name: String,
    val description: String?,
    /** Active contracts inside — 0 until the contracts feature lands. */
    val contractCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias SystemPageResponse = PageResponse<SystemResponse>

data class SystemListFilter(val name: String? = null, val domainId: UInt? = null)

data class SystemListResult(val items: List<SystemResponse>, val total: Long)

fun sanitizedSystemRequest(request: SystemRequest): SystemRequest = SystemRequest(
    domainId = request.domainId,
    name = sanitizeSingleLine(request.name, "Name"),
    description = request.description?.let { sanitizeSingleLine(it, "Description") }?.takeIf { it.isNotBlank() },
)

/** The system rules — enforced by the route AND re-checked by the service (the domain's existence is the service's). */
fun validateSystemRequest(request: SystemRequest) {
    if (request.name.isBlank() || request.name.length > MAX_SYSTEM_NAME_LENGTH) {
        throw BadRequestException("Name must be 1-$MAX_SYSTEM_NAME_LENGTH characters")
    }
    if (request.description != null && request.description.length > MAX_SYSTEM_DESCRIPTION_LENGTH) {
        throw BadRequestException("Description must be at most $MAX_SYSTEM_DESCRIPTION_LENGTH characters")
    }
}
