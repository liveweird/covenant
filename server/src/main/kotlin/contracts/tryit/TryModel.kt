package ch.nokillswit.contracts.tryit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.Severity
import kotlinx.serialization.Serializable

/**
 * The try-it wire shapes (milestone 3c). `GET …/{vid}/try` answers the CATALOG — what the
 * document offers to try, derived server-side from the parsed tree because the SPA parses no
 * YAML; the try POSTs answer an observation plus a [ConformanceReport]: the live response,
 * message or table measured against the document's own schema, as `CONFORMANCE` findings.
 */
@Serializable
data class TryCatalogResponse(
    val type: ContractType,
    val http: List<HttpOperationSummary> = emptyList(),
    val kafka: List<KafkaChannelSummary> = emptyList(),
    val sql: List<SqlDatasetSummary> = emptyList(),
)

@Serializable
data class HttpParameterSummary(val name: String, val location: String, val required: Boolean, val type: String? = null)

@Serializable
data class HttpRequestBodySummary(val required: Boolean, val mediaTypes: List<String>)

@Serializable
data class HttpOperationSummary(
    val operationId: String?,
    /** Upper-case. */
    val method: String,
    /** The path TEMPLATE as declared (`/pets/{id}`); the try substitutes the parameters. */
    val path: String,
    val summary: String?,
    val parameters: List<HttpParameterSummary>,
    val requestBody: HttpRequestBodySummary?,
    /** The declared response keys — `"200"`, `"2XX"`, `"default"`. */
    val responses: List<String>,
    /** Header names the document's security schemes expect (`Authorization`, an API-key header). */
    val securityHeaders: List<String>,
)

@Serializable
data class KafkaMessageSummary(
    val name: String,
    val contentType: String?,
    val schemaFormat: String?,
    /** The payload schema's JSON pointer — the conformance check validates against it. */
    val payloadPointer: String?,
)

@Serializable
data class KafkaChannelSummary(
    /** The channel's key in the document (3.x) or its address (2.x). */
    val channel: String,
    /** The topic — 3.x `address` (the key when absent), 2.x the key. */
    val address: String,
    /** 3.x `send`/`receive`, 2.x `publish`/`subscribe`. */
    val actions: List<String>,
    val messages: List<KafkaMessageSummary>,
)

@Serializable
data class SqlPropertySummary(
    val name: String,
    val physicalName: String?,
    val logicalType: String?,
    val physicalType: String?,
    val required: Boolean,
)

@Serializable
data class SqlDatasetSummary(
    val name: String,
    val physicalName: String?,
    val physicalType: String?,
    val properties: List<SqlPropertySummary>,
)

@Serializable
data class ConformanceReport(
    val findings: List<Finding>,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    /** The JSON pointer of the schema the observation was validated against, when one applied. */
    val validatedAgainst: String? = null,
) {
    companion object {
        fun of(findings: List<Finding>, validatedAgainst: String? = null) = ConformanceReport(
            findings = findings,
            errors = findings.count { it.severity == Severity.ERROR },
            warnings = findings.count { it.severity == Severity.WARN },
            infos = findings.count { it.severity == Severity.INFO },
            validatedAgainst = validatedAgainst,
        )
    }
}
