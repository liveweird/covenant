package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode

/**
 * The ODCS pass: the document against the vendored Open Data Contract Standard schema for its
 * declared `apiVersion` (v3.0.0 – v3.1.0 vendored; an older `v2.x` is an unsupported-version
 * SCHEMA error). The checker runs nothing for ODCS in milestone 1 — this IS the gate.
 */
object OdcsValidator {
    const val CODE_SCHEMA = "ODCS_SCHEMA"
    const val CODE_UNSUPPORTED = "UNSUPPORTED_SPEC_VERSION"

    fun validate(root: JsonNode): List<Finding> {
        val version = root.path("apiVersion").asText()
        val schema = VendoredSchemas.odcs(version)
            ?: return listOf(
                Finding(
                    Severity.ERROR, FindingSource.SCHEMA, CODE_UNSUPPORTED,
                    "Unsupported ODCS apiVersion '$version' — ${VendoredSchemas.ODCS_VERSIONS.sorted().joinToString(", ")} are supported",
                    "/apiVersion",
                ),
            )
        return VendoredSchemas.validate(schema, root, CODE_SCHEMA)
    }
}
