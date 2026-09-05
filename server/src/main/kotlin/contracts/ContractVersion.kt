package ch.nokillswit.contracts

import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * A contract version (V10): one SemVer version = one standard document stored VERBATIM, with
 * its lifecycle, the metadata read out of it, and the check snapshot of THAT text. The document
 * is editable while DRAFT/PROPOSED; from ACTIVE on only the lifecycle moves.
 */
@Serializable
data class VersionCreateRequest(
    /** SemVer 2.0; unique per contract and greater than every existing version. */
    val version: String,
    /** The raw YAML or JSON text, at most `contracts.maxDocumentBytes` bytes. */
    val content: String,
)

@Serializable
data class VersionContentRequest(val content: String)

@Serializable
data class TransitionRequest(val to: Lifecycle)

/** The live-check body (`POST /contracts/versions/check`): a document of a type, nothing stored. */
@Serializable
data class DocumentCheckRequest(
    val type: ContractType,
    val content: String,
    /** When known, the SemVer the document is (to be) stored as — drives the version cross-check. */
    val version: String? = null,
)

@Serializable
data class VersionResponse(
    val id: UInt,
    val contractId: UInt,
    val version: String,
    val lifecycle: Lifecycle,
    val format: DocumentFormat,
    val content: String,
    val contentSha256: String,
    val docTitle: String?,
    val docDescription: String?,
    val specVersion: String?,
    val findings: List<Finding>,
    val checkErrors: Int,
    val checkWarnings: Int,
    val checkInfos: Int,
    val checkComplete: Boolean,
    val checkedAt: Long,
    val createdBy: UInt,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The list row — no content, no findings (the counts stand in). */
@Serializable
data class VersionListItem(
    val id: UInt,
    val contractId: UInt,
    val version: String,
    val lifecycle: Lifecycle,
    val format: DocumentFormat,
    val docTitle: String?,
    val specVersion: String?,
    val checkErrors: Int,
    val checkWarnings: Int,
    val checkInfos: Int,
    val checkComplete: Boolean,
    val createdBy: UInt,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias VersionPageResponse = PageResponse<VersionListItem>

data class VersionListFilter(val lifecycles: List<Lifecycle> = emptyList())

data class VersionListResult(val items: List<VersionListItem>, val total: Long)
