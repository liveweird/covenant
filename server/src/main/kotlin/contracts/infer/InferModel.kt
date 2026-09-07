package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.SemVer
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.tryit.KafkaTry
import ch.nokillswit.contracts.tryit.TryCatalog
import ch.nokillswit.infra.validation.sanitizeSingleLine
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import kotlinx.serialization.Serializable

/**
 * The inference engine's wire shape (release 0.8.0): hand Covenant real samples — HTTP
 * exchanges, AsyncAPI message batches, or a relation's columns/rows — and get a DRAFT document
 * back, computed per call and never stored (the try-it posture: nothing here reaches
 * `contract_events`, and the pure POST carries no audit either — no contract was touched).
 * Exactly the sample list matching `type` may be non-empty; the other two must stay empty.
 */
@Serializable
data class InferRequest(
    val type: ContractType,
    /** The document's title/name — OpenAPI/AsyncAPI `info.title`, ODCS `name`. */
    val name: String? = null,
    /** The document's own version — OpenAPI/AsyncAPI `info.version`, ODCS `version`; SemVer 2.0 when given. */
    val version: String? = null,
    val http: List<HttpExchangeSample> = emptyList(),
    val messages: List<MessageBatchSample> = emptyList(),
    val relations: List<RelationSample> = emptyList(),
)

/**
 * One HTTP request/response pair. Header NAMES only (case-insensitive) — VALUES never travel; of
 * the `Authorization` value only the scheme word (`bearer`/`basic`/`digest`/`other`) survives.
 */
@Serializable
data class HttpExchangeSample(
    val method: String,
    /** An absolute `http(s)://host/path` (its origin becomes a `servers[]` entry) or a bare `/path`. */
    val url: String,
    /** Values feed type inference (boolean/integer/number/string) only — never written into the document. */
    val query: Map<String, String> = emptyMap(),
    val requestHeaders: List<String> = emptyList(),
    val authorizationScheme: String? = null,
    val requestContentType: String? = null,
    val requestBody: String? = null,
    val status: Int,
    val responseHeaders: List<String> = emptyList(),
    val responseContentType: String? = null,
    val responseBody: String? = null,
)

/** A batch of payloads observed on one AsyncAPI channel (its topic/address). */
@Serializable
data class MessageBatchSample(val channel: String, val payloads: List<String> = emptyList())

/** One column as a database (or the caller) describes it. */
@Serializable
data class RelationColumn(val name: String, val dbType: String, val nullable: Boolean, val primaryKeyPosition: Int? = null)

/** One ODCS dataset, described by its columns, sampled by its rows, or both. */
@Serializable
data class RelationSample(
    val name: String,
    /** `table` or `view`; unset when unknown. */
    val physicalType: String? = null,
    val columns: List<RelationColumn> = emptyList(),
    /** JSON objects, one per observed row. */
    val rows: List<String> = emptyList(),
)

/** The draft — the same shape every editor screen already knows how to open. */
@Serializable
data class InferResponse(
    val content: String,
    val format: DocumentFormat,
    /** Every heuristic the builder applied — `FindingSource.INFERENCE`, never blocking. */
    val notes: List<Finding>,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
)

// ---- Observe: one live sample pulled through an Environment, in the pure endpoint's own shapes --------------------

/** `POST /contracts/infer/observe/http`: one request against a literal path through an environment's HTTP base URL. */
@Serializable
data class ObserveHttpRequest(
    val environmentId: UInt,
    val method: String,
    /** A concrete path, e.g. `/orders/42` — never a template. */
    val path: String,
    val query: Map<String, String> = emptyMap(),
    /** Per-call headers — the caller MAY type credentials here; never stored, logged or audited. */
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val body: String? = null,
)

@Serializable
data class ObserveHttpResponse(val sample: HttpExchangeSample, val notes: List<Finding>)

/** `POST /contracts/infer/observe/kafka`: a bounded tail read reduced to the payloads worth learning a schema from. */
@Serializable
data class ObserveKafkaRequest(val environmentId: UInt, val topic: String, val limit: Int = KafkaTry.DEFAULT_READ_LIMIT)

@Serializable
data class ObserveKafkaResponse(val sample: MessageBatchSample, val notes: List<Finding>, val reachedEnd: Boolean)

/** `POST /contracts/infer/observe/sql`: one relation described from PostgreSQL's own catalog — never `SELECT *`. */
@Serializable
data class ObserveSqlRequest(val environmentId: UInt, val relation: String)

@Serializable
data class ObserveSqlResponse(val sample: RelationSample, val notes: List<Finding>)

/** `POST /contracts/infer/observe/sql/relations`: what the environment's database offers to describe. */
@Serializable
data class ObserveRelationsRequest(val environmentId: UInt)

@Serializable
data class RelationSummary(val schema: String?, val name: String, val kind: String)

@Serializable
data class RelationListResponse(val relations: List<RelationSummary>)

private const val MAX_SAMPLES = 50
internal const val MAX_SAMPLE_BODY_BYTES = 1024 * 1024
private val HTTP_STATUS_RANGE = 100..599
private val RELATION_PHYSICAL_TYPES = setOf("table", "view")

/** The 400/413 vocabulary, checked before any inference work runs. */
fun validateInferRequest(request: InferRequest) {
    request.name?.let { sanitizeSingleLine(it, "Name") }
    val version = request.version?.let { sanitizeSingleLine(it, "Version") }?.takeIf { it.isNotEmpty() }
    if (version != null && SemVer.parseOrNull(version) == null) {
        throw BadRequestException("Version must be a valid SemVer 2.0 string (e.g. 1.2.0 or 2.0.0-rc.1)")
    }
    requireExclusiveSamples(request)
    when (request.type) {
        ContractType.OPENAPI -> validateHttpSamples(request.http)
        ContractType.ASYNCAPI -> validateMessageSamples(request.messages)
        ContractType.ODCS -> validateRelationSamples(request.relations)
    }
}

private fun requireExclusiveSamples(request: InferRequest) {
    val active = when (request.type) {
        ContractType.OPENAPI -> request.http.size
        ContractType.ASYNCAPI -> request.messages.size
        ContractType.ODCS -> request.relations.size
    }
    if (active == 0) throw BadRequestException("At least one sample matching '${request.type}' is required")
    val othersPresent = when (request.type) {
        ContractType.OPENAPI -> request.messages.isNotEmpty() || request.relations.isNotEmpty()
        ContractType.ASYNCAPI -> request.http.isNotEmpty() || request.relations.isNotEmpty()
        ContractType.ODCS -> request.http.isNotEmpty() || request.messages.isNotEmpty()
    }
    if (othersPresent) throw BadRequestException("Only the sample list matching 'type' may be non-empty")
}

private fun validateHttpSamples(http: List<HttpExchangeSample>) {
    if (http.size > MAX_SAMPLES) throw BadRequestException("At most $MAX_SAMPLES samples")
    http.forEachIndexed { i, sample ->
        val label = "Sample ${i + 1}"
        if (sample.method.lowercase() !in TryCatalog.METHODS) {
            throw BadRequestException("$label: method must be one of ${TryCatalog.METHODS.joinToString()}")
        }
        if (sample.status !in HTTP_STATUS_RANGE) throw BadRequestException("$label: status must be 100-599")
        if (!isPathOrAbsoluteUrl(sample.url)) throw BadRequestException("$label: url must be an absolute http(s) URL or a '/' path")
        validateBody(sample.requestBody, sample.requestContentType, label, "request")
        validateBody(sample.responseBody, sample.responseContentType, label, "response")
    }
}

private fun validateBody(body: String?, contentType: String?, label: String, which: String) {
    if (body.isNullOrEmpty()) return
    if (body.toByteArray().size > MAX_SAMPLE_BODY_BYTES) throw PayloadTooLargeException(MAX_SAMPLE_BODY_BYTES.toLong())
    if (isJsonMediaType(contentType) && parseJsonOrNull(body) == null) {
        throw BadRequestException("$label: the $which body is not JSON")
    }
}

private fun isPathOrAbsoluteUrl(url: String): Boolean {
    if (url.startsWith("/")) return true
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
}

private fun validateMessageSamples(messages: List<MessageBatchSample>) {
    if (messages.size > MAX_SAMPLES) throw BadRequestException("At most $MAX_SAMPLES samples")
    messages.forEachIndexed { i, batch ->
        val label = "Sample ${i + 1}"
        val channel = sanitizeSingleLine(batch.channel, "$label: channel")
        if (channel.isBlank()) throw BadRequestException("$label: channel is required")
        if (batch.payloads.size > MAX_SAMPLES) throw BadRequestException("$label: at most $MAX_SAMPLES payloads")
        batch.payloads.forEachIndexed { j, payload ->
            if (payload.toByteArray().size > MAX_SAMPLE_BODY_BYTES) throw PayloadTooLargeException(MAX_SAMPLE_BODY_BYTES.toLong())
            if (parseJsonOrNull(payload) == null) throw BadRequestException("$label: payload ${j + 1} is not valid JSON")
        }
    }
}

private fun validateRelationSamples(relations: List<RelationSample>) {
    if (relations.size > MAX_SAMPLES) throw BadRequestException("At most $MAX_SAMPLES samples")
    relations.forEachIndexed { i, relation ->
        val label = "Sample ${i + 1}"
        sanitizeSingleLine(relation.name, "$label: name")
        if (relation.name.isBlank()) throw BadRequestException("$label: name is required")
        relation.physicalType?.let {
            if (it.lowercase() !in RELATION_PHYSICAL_TYPES) throw BadRequestException("$label: physicalType must be 'table' or 'view'")
        }
        if (relation.columns.isEmpty() && relation.rows.isEmpty()) {
            throw BadRequestException("$label: relation '${relation.name}' needs columns or rows")
        }
        if (relation.rows.size > MAX_SAMPLES) throw BadRequestException("$label: at most $MAX_SAMPLES rows")
        validateRelationColumns(relation.columns, label)
        validateRelationRows(relation.rows, label)
    }
}

private fun validateRelationColumns(columns: List<RelationColumn>, label: String) {
    if (columns.size > MAX_SAMPLES) throw BadRequestException("$label: at most $MAX_SAMPLES columns")
    columns.forEach { column ->
        val name = sanitizeSingleLine(column.name, "$label: column name")
        val dbType = sanitizeSingleLine(column.dbType, "$label: column dbType")
        if (name.isBlank() || dbType.isBlank()) throw BadRequestException("$label: every column needs a name and a dbType")
        if (column.primaryKeyPosition != null && column.primaryKeyPosition < 1) {
            throw BadRequestException("$label: primaryKeyPosition must be at least 1")
        }
    }
}

private fun validateRelationRows(rows: List<String>, label: String) {
    rows.forEachIndexed { j, row ->
        if (row.toByteArray().size > MAX_SAMPLE_BODY_BYTES) throw PayloadTooLargeException(MAX_SAMPLE_BODY_BYTES.toLong())
        val node = parseJsonOrNull(row)
        if (node == null || !node.isObject) throw BadRequestException("$label: row ${j + 1} is not a JSON object")
    }
}

// ---- shared JSON / media-type helpers, reused by the per-type builders -----------------------

private val JSON_MAPPER = ObjectMapper()

internal fun parseJsonOrNull(text: String): JsonNode? = try {
    JSON_MAPPER.readTree(text)
} catch (_: JsonProcessingException) {
    null
}

/** Null (unset — the default assumption) or any `…json…` media type counts as JSON-like. */
internal fun isJsonMediaType(contentType: String?): Boolean =
    contentType.isNullOrBlank() || contentType.contains("json", ignoreCase = true)

/** Accumulates the `INFERENCE` notes a build run produces — WARN for TRUNCATED/MIXED_TYPES, INFO otherwise. */
class Notes {
    private val findings = mutableListOf<Finding>()
    private var truncated = false

    val all: List<Finding> get() = findings

    fun info(code: String, message: String, path: String? = null) {
        findings += Finding(Severity.INFO, FindingSource.INFERENCE, code, message, path)
    }

    fun warn(code: String, message: String, path: String? = null) {
        findings += Finding(Severity.WARN, FindingSource.INFERENCE, code, message, path)
    }

    /** `INFER_TRUNCATED` fires at most once per build — the budget is a document-wide ceiling, not a per-node one. */
    fun truncated(pointer: String) {
        if (truncated) return
        truncated = true
        warn("INFER_TRUNCATED", "The document exceeded the inference size budget — truncated at $pointer", pointer)
    }
}

/** The pure dispatcher: one builder per type, then the YAML write and the document cap. */
object Inference {
    fun build(request: InferRequest, maxDocumentBytes: Long): InferResponse {
        val budget = RenderBudget()
        val notes = Notes()
        val root = when (request.type) {
            ContractType.OPENAPI -> OpenApiInference.build(request, budget, notes)
            ContractType.ASYNCAPI -> AsyncApiInference.build(request, budget, notes)
            ContractType.ODCS -> OdcsInference.build(request, budget, notes)
        }
        val yaml = DocumentWriter.yaml(root)
        if (yaml.toByteArray().size > maxDocumentBytes) {
            throw BadRequestException("The inferred document exceeds the 2 MiB document cap — provide fewer or smaller samples")
        }
        val sorted = notes.all.sortedWith(compareBy({ it.severity.ordinal }, { it.code }))
        return InferResponse(
            content = yaml,
            format = DocumentFormat.yaml,
            notes = sorted,
            errors = sorted.count { it.severity == Severity.ERROR },
            warnings = sorted.count { it.severity == Severity.WARN },
            infos = sorted.count { it.severity == Severity.INFO },
        )
    }
}
