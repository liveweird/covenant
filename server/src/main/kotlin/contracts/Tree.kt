package ch.nokillswit.contracts

import kotlinx.serialization.Serializable

/**
 * `GET /contracts/tree`: Domain → System → Contract for the hierarchy page. Unpaged on purpose —
 * the registries are ADMIN-curated and small, and the contract level is narrowed by the same
 * filters as the list (type, lifecycle of the latest version, q); a system or domain with no
 * matching contract still appears (the tree's spine never hides a container).
 */
@Serializable
data class TreeContract(
    val id: UInt,
    val name: String,
    val type: ContractType,
    val owner: OwnerRef,
    val latestVersion: LatestVersionSummary?,
    val versionCount: Int,
    val canWrite: Boolean,
)

@Serializable
data class TreeSystem(val id: UInt, val name: String, val contracts: List<TreeContract>)

@Serializable
data class TreeDomain(val id: UInt, val name: String, val systems: List<TreeSystem>)

@Serializable
data class TreeResponse(val domains: List<TreeDomain>)
