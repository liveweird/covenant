package ch.nokillswit.domains

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import kotlinx.serialization.Serializable

/**
 * Domains (V7): the top of the Domain → System → Contract hierarchy — an ADMIN-curated
 * registry everyone reads (the hierarchy tree, the system pickers). A domain holding active
 * systems cannot be deleted (409): the tree never dangles.
 */
const val MAX_DOMAIN_NAME_LENGTH = 100
const val MAX_DOMAIN_DESCRIPTION_LENGTH = 2000

@Serializable
data class DomainRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class DomainResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    /** Active systems inside — the count the delete rule and the tree caption both read. */
    val systemCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias DomainPageResponse = PageResponse<DomainResponse>

data class DomainListFilter(val name: String? = null)

data class DomainListResult(val items: List<DomainResponse>, val total: Long)

fun sanitizedDomainRequest(request: DomainRequest): DomainRequest = DomainRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

/** The domain rules — enforced by the route AND re-checked by the service. */
fun validateDomainRequest(request: DomainRequest) {
    requireNameAndDescription(request.name, request.description, MAX_DOMAIN_NAME_LENGTH, MAX_DOMAIN_DESCRIPTION_LENGTH)
}
