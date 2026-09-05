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
    /**
     * The https URL of the repo copy this text was pulled from, when it was (the import page's
     * URL fetch): stored as the version's source reference AND stamped as synced — the text IS
     * the repo copy at this moment.
     */
    val sourceUrl: String? = null,
)

@Serializable
data class VersionContentRequest(val content: String)

/** `PUT …/{vid}/source` — set or clear (null/blank) the version's repo reference; no fetch happens. */
@Serializable
data class VersionSourceRequest(val sourceUrl: String? = null)

/** `GET …/{vid}/sync` — the reference plus the baseline the SPA attributes a difference with. */
@Serializable
data class SyncStateResponse(
    val sourceUrl: String?,
    /** Epoch millis; 0 = never synced. */
    val lastSyncedAt: Long,
    /** The text as pulled at the last sync — the Covenant-vs-repo comparison baseline; null = never. */
    val syncedContent: String?,
)

/** `POST …/{vid}/sync` — the repo copy, fetched client-side through `POST /contracts/fetch`. */
@Serializable
data class SyncRequest(val content: String)

@Serializable
data class TransitionRequest(val to: Lifecycle)

/** The live-check body (`POST /contracts/versions/check`): a document of a type, nothing stored. */
@Serializable
data class DocumentCheckRequest(
    val type: ContractType,
    val content: String,
    /** When known, the SemVer the document is (to be) stored as — drives the version cross-check. */
    val version: String? = null,
    /** When known, the contract the document belongs to — its highest ACTIVE version below `version` is the breaking baseline. */
    val contractId: UInt? = null,
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
    /** The repo copy's https URL; null = no reference. */
    val sourceUrl: String? = null,
    /** Epoch millis of the last repo → Covenant sync; 0 = never. `updatedAt > lastSyncedAt` = edited here since. */
    val lastSyncedAt: Long = 0,
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
    val sourceUrl: String? = null,
    val lastSyncedAt: Long = 0,
)

typealias VersionPageResponse = PageResponse<VersionListItem>

data class VersionListFilter(val lifecycles: List<Lifecycle> = emptyList())

data class VersionListResult(val items: List<VersionListItem>, val total: Long)
