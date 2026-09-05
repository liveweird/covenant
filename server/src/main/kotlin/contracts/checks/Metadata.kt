package ch.nokillswit.contracts.checks

import ch.nokillswit.contracts.ContractType
import com.fasterxml.jackson.databind.JsonNode

/**
 * What Covenant reads out of a document for display (the version's `doc_title`,
 * `doc_description`, `spec_version` columns) — display only; the document stays the truth.
 * The mapping per standard is documented in `.claude/docs/contract-standards.md`.
 */
object Metadata {
    const val MAX_TITLE_LENGTH = 200
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_SPEC_VERSION_LENGTH = 20

    fun extract(type: ContractType, root: JsonNode): DocumentMetadata = when (type) {
        ContractType.OPENAPI -> DocumentMetadata(
            specVersion = text(root, "openapi")?.take(MAX_SPEC_VERSION_LENGTH),
            title = text(root.path("info"), "title")?.take(MAX_TITLE_LENGTH),
            description = text(root.path("info"), "description")?.take(MAX_DESCRIPTION_LENGTH),
        )
        ContractType.ASYNCAPI -> DocumentMetadata(
            specVersion = text(root, "asyncapi")?.take(MAX_SPEC_VERSION_LENGTH),
            title = text(root.path("info"), "title")?.take(MAX_TITLE_LENGTH),
            description = text(root.path("info"), "description")?.take(MAX_DESCRIPTION_LENGTH),
        )
        ContractType.ODCS -> DocumentMetadata(
            specVersion = text(root, "apiVersion")?.take(MAX_SPEC_VERSION_LENGTH),
            title = (text(root, "name") ?: text(root, "id"))?.take(MAX_TITLE_LENGTH),
            description = (text(root.path("description"), "purpose") ?: text(root.path("description"), "usage"))
                ?.take(MAX_DESCRIPTION_LENGTH),
        )
    }

    /** The version the DOCUMENT claims (`info.version` / ODCS `version`), compared to the stored SemVer. */
    fun declaredVersion(type: ContractType, root: JsonNode): String? = when (type) {
        ContractType.OPENAPI, ContractType.ASYNCAPI -> text(root.path("info"), "version")
        ContractType.ODCS -> text(root, "version")
    }

    /** ODCS declares its own lifecycle `status` — compared (INFO) to the row's lifecycle. */
    fun declaredStatus(type: ContractType, root: JsonNode): String? =
        if (type == ContractType.ODCS) text(root, "status") else null

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isValueNode && !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
}
