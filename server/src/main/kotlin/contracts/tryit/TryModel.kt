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

/** `POST …/try/http`: one request against an OpenAPI operation through the environment's base URL. */
@Serializable
data class TryHttpRequest(
    val environmentId: UInt,
    /** Upper- or lower-case; must be an operation the document declares on [path]. */
    val method: String,
    /** The path TEMPLATE exactly as declared (`/pets/{id}`); every `{param}` comes from [pathParams]. */
    val path: String,
    val pathParams: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    /** Per-call headers — the user MAY type credentials here; they are never stored, logged or audited. */
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val body: String? = null,
)

@Serializable
data class TryHttpResponse(
    /** The URL called WITHOUT its query string (a query may carry a token the user typed). */
    val url: String,
    val status: Int,
    /** Response headers minus cookies and hop-by-hop headers; multi-valued ones joined with `, `. */
    val headers: Map<String, String>,
    val body: String?,
    val bodyTruncated: Boolean,
    val durationMs: Long,
    val conformance: ConformanceReport,
)

/** `POST …/try/sql`: a bounded read-only sample of an ODCS dataset through the environment's PostgreSQL target. */
@Serializable
data class TrySqlRequest(
    val environmentId: UInt,
    /** A dataset the document declares — its `name` or `physicalName`, optionally `schema.name`. */
    val dataset: String,
    val limit: Int = SqlTry.DEFAULT_LIMIT,
)

@Serializable
data class SqlColumn(
    val name: String,
    /** PostgreSQL's type name (`int4`, `text`, `_text` for arrays, `jsonb`, …). */
    val dbType: String,
    val nullable: Boolean,
    /** The ODCS `logicalType` of the declared property this column matched, when one did. */
    val declaredLogicalType: String?,
)

@Serializable
data class TrySqlResponse(
    /** The statement as executed (the LIMIT inlined) — never anything the caller wrote. */
    val statement: String,
    val columns: List<SqlColumn>,
    /** Cells as text (`bytea` as base64); NULL stays null; a cell is cut at 4 KiB, the whole sample at 1 MiB. */
    val rows: List<List<String?>>,
    val truncated: Boolean,
    val durationMs: Long,
    val conformance: ConformanceReport,
)

/** `POST …/try/kafka/publish` (contract writers only): one record onto the channel's topic through the environment's cluster. */
@Serializable
data class TryKafkaPublishRequest(
    val environmentId: UInt,
    /** The channel as the catalog names it (the 3.x key, the 2.x address). */
    val channel: String,
    /** The message name to validate against — required when the channel declares several. */
    val message: String? = null,
    val key: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** The payload text — JSON for JSON-Schema and Avro payloads (Avro is validated, then sent as JSON bytes verbatim). */
    val payload: String,
)

@Serializable
data class TryKafkaPublishResponse(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
    val durationMs: Long,
    val conformance: ConformanceReport,
)

/** `POST …/try/kafka/read`: the newest records of the channel's topic — bounded, never committed. */
@Serializable
data class TryKafkaReadRequest(
    val environmentId: UInt,
    val channel: String,
    val message: String? = null,
    val limit: Int = KafkaTry.DEFAULT_READ_LIMIT,
)

@Serializable
data class KafkaRecordView(
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
    val key: String?,
    val headers: Map<String, String>,
    /** UTF-8 text, or base64 when the bytes are not valid UTF-8 (see [encoding]); cut at 64 KiB. */
    val payload: String?,
    /** `utf8` or `base64`. */
    val encoding: String,
    val truncated: Boolean,
)

@Serializable
data class TryKafkaReadResponse(
    val topic: String,
    /** Newest first. */
    val messages: List<KafkaRecordView>,
    /** True when every partition was read up to its end offset at the time of the read. */
    val reachedEnd: Boolean,
    val durationMs: Long,
    /** Per-record findings carry paths under `/messages/{i}/payload`. */
    val conformance: ConformanceReport,
)

internal const val NANOS_PER_MILLI = 1_000_000L

/** Milliseconds since a `System.nanoTime()` mark — every leg's `durationMs`. */
internal fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
