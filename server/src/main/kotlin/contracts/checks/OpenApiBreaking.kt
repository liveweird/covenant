package ch.nokillswit.contracts.checks

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.openapitools.openapidiff.core.compare.OpenApiDiff
import org.openapitools.openapidiff.core.compare.OpenApiDiffOptions
import org.openapitools.openapidiff.core.model.ChangedContent
import org.openapitools.openapidiff.core.model.ChangedOperation
import org.openapitools.openapidiff.core.model.ChangedSchema
import org.slf4j.LoggerFactory

/**
 * OpenAPI breaking changes (milestone 2): the candidate document against the baseline (the
 * highest ACTIVE version below it) through openapi-diff-core, walked into one BREAKING finding
 * per incompatible element — a removed operation, a removed or newly required parameter, an
 * incompatible parameter/request body/response schema, a removed response, a changed security
 * requirement. Both trees are parsed WITHOUT resolving external `$ref`s (never a fetch); the
 * differ resolves internal ones against `components` itself. Severity is decided by the caller
 * (`BreakingChanges` — WARN, or INFO once the version carries the MAJOR bump).
 *
 * OpenAPI 3.1 documents are compared through the 3.0 MODEL: swagger-parser reads a 3.1 root into
 * generic `JsonSchema` objects, which openapi-diff-core 2.1 does not descend into (array items,
 * composed schemas), so a 3.1 document would compare as "nothing changed". Relabelling the root
 * `openapi: 3.1.x` as 3.0.3 for the comparison ONLY (the stored text is never touched) yields the
 * typed model the differ understands; 3.1-only constructs (type arrays, `webhooks`, `$schema`)
 * are invisible to the comparison — an accepted, documented limit of the engine.
 */
object OpenApiBreaking {
    const val CODE_REMOVED_OPERATION = "REMOVED_OPERATION"
    const val CODE_REMOVED_PARAMETER = "REMOVED_PARAMETER"
    const val CODE_NEW_REQUIRED_PARAMETER = "NEW_REQUIRED_PARAMETER"
    const val CODE_CHANGED_PARAMETER = "CHANGED_PARAMETER"
    const val CODE_CHANGED_REQUEST_BODY = "CHANGED_REQUEST_BODY"
    const val CODE_REMOVED_RESPONSE = "REMOVED_RESPONSE"
    const val CODE_CHANGED_RESPONSE = "CHANGED_RESPONSE"
    const val CODE_CHANGED_SECURITY = "CHANGED_SECURITY"
    const val CODE_INCOMPATIBLE_OPERATION = "INCOMPATIBLE_OPERATION"

    private val log = LoggerFactory.getLogger(OpenApiBreaking::class.java)

    private val options = ParseOptions().apply {
        isResolve = false
        isValidateInternalRefs = false
        isValidateExternalRefs = false
    }

    /** Null when either side cannot be read as OpenAPI (the caller reports the skip). */
    fun compare(oldContent: String, newContent: String): List<Finding>? {
        val old = parse(oldContent) ?: return null
        val new = parse(newContent) ?: return null
        val diff = try {
            OpenApiDiff.compare(old, new, OpenApiDiffOptions.builder().build())
        } catch (e: RuntimeException) {
            // openapi-diff throws on shapes it cannot compare (e.g. a `$ref` it cannot follow) —
            // the caller reports the skip rather than the failure; the other checks stand.
            log.info("openapi-diff could not compare the documents: {}", e.toString())
            return null
        }
        val out = mutableListOf<Finding>()
        diff.missingEndpoints.orEmpty().forEach { e ->
            out += breaking(CODE_REMOVED_OPERATION, "Operation ${e.method} ${e.pathUrl} was removed", operationPointer(e.pathUrl, e.method))
        }
        diff.changedOperations.orEmpty().filter { it.isIncompatible }.forEach { out += operation(it) }
        return out
    }

    private val root31 = Regex("""(?m)^(\s*"?openapi"?\s*:\s*['"]?)3\.1\.\d+""")

    private fun parse(content: String): OpenAPI? = try {
        OpenAPIV3Parser().readContents(root31.replaceFirst(content, "$" + "13.0.3"), null, options).openAPI
    } catch (e: RuntimeException) {
        log.info("swagger-parser could not read a document for comparison: {}", e.toString())
        null
    }

    private fun operation(op: ChangedOperation): List<Finding> {
        val where = "${op.httpMethod} ${op.pathUrl}"
        val base = operationPointer(op.pathUrl, op.httpMethod)
        val out = mutableListOf<Finding>()
        out += parameters(op, where, base)
        out += requestBody(op, where, base)
        out += responses(op, where, base)
        if (op.resultSecurityRequirements().isIncompatible) {
            out += breaking(CODE_CHANGED_SECURITY, "$where: the security requirements changed incompatibly", "$base/security")
        }
        if (out.isEmpty()) out += breaking(CODE_INCOMPATIBLE_OPERATION, "$where changed incompatibly", base)
        return out
    }

    private fun parameters(op: ChangedOperation, where: String, base: String): List<Finding> {
        val params = op.parameters ?: return emptyList()
        val out = mutableListOf<Finding>()
        params.missing.orEmpty().forEach { p ->
            out += breaking(CODE_REMOVED_PARAMETER, "$where: parameter '${p.name}' (${p.`in`}) was removed", "$base/parameters")
        }
        params.increased.orEmpty().filter { it.required == true }.forEach { p ->
            out += breaking(CODE_NEW_REQUIRED_PARAMETER, "$where: new required parameter '${p.name}' (${p.`in`})", "$base/parameters")
        }
        params.changed.orEmpty().filter { it.isIncompatible }.forEach { p ->
            val detail = buildList {
                if (p.isChangeRequired && p.newParameter?.required == true) add("is now required")
                p.schema?.let { addAll(schemaDetails(it)) }
            }.ifEmpty { listOf("changed incompatibly") }
            val what = "$where: parameter '${p.name}' (${p.`in`}) ${detail.joinToString(", ")}"
            out += breaking(CODE_CHANGED_PARAMETER, what, "$base/parameters")
        }
        return out
    }

    private fun requestBody(op: ChangedOperation, where: String, base: String): List<Finding> {
        val body = op.requestBody ?: return emptyList()
        if (!body.isIncompatible) return emptyList()
        val detail = buildList {
            if (body.isChangeRequired && body.newRequestBody?.required == true) add("is now required")
            addAll(contentDetails(body.content))
        }.ifEmpty { listOf("changed incompatibly") }
        return listOf(breaking(CODE_CHANGED_REQUEST_BODY, "$where: the request body ${detail.joinToString(", ")}", "$base/requestBody"))
    }

    private fun responses(op: ChangedOperation, where: String, base: String): List<Finding> {
        val responses = op.apiResponses ?: return emptyList()
        val out = mutableListOf<Finding>()
        responses.missing.orEmpty().keys.forEach { code ->
            out += breaking(CODE_REMOVED_RESPONSE, "$where: response $code was removed", "$base/responses")
        }
        responses.changed.orEmpty().filter { it.value.isIncompatible }.forEach { (code, changed) ->
            val detail = buildList {
                addAll(contentDetails(changed.content))
                if (changed.headers?.isIncompatible == true) add("headers changed incompatibly")
            }.ifEmpty { listOf("changed incompatibly") }
            out += breaking(CODE_CHANGED_RESPONSE, "$where: response $code ${detail.joinToString(", ")}", "$base/responses/${esc(code)}")
        }
        return out
    }

    /** Removed media types and the incompatible schema facts per media type. */
    private fun contentDetails(content: ChangedContent?): List<String> = buildList {
        content?.missing.orEmpty().keys.forEach { add("media type '$it' was removed") }
        content?.changed.orEmpty().forEach { (media, c) -> c.schema?.let { s -> addAll(schemaDetails(s).map { "$media: $it" }) } }
    }

    /** The incompatible facts of one schema change, a few levels of nested properties deep. */
    private fun schemaDetails(s: ChangedSchema, prefix: String = "", depth: Int = 0): List<String> {
        val out = mutableListOf<String>()
        fun name(p: String) = if (prefix.isEmpty()) "'$p'" else "'$prefix.$p'"
        if (s.isChangedType) out += "${if (prefix.isEmpty()) "the type" else "type of '$prefix'"} changed"
        s.missingProperties.orEmpty().keys.forEach { out += "property ${name(it)} was removed" }
        s.required?.increased.orEmpty().forEach { out += "property ${name(it)} is now required" }
        if (s.enumeration?.isIncompatible == true) out += "${if (prefix.isEmpty()) "the enum" else "enum of '$prefix'"} was narrowed"
        if (depth < MAX_SCHEMA_DEPTH) {
            s.changedProperties.orEmpty().filter { it.value.isIncompatible }.forEach { (p, c) ->
                out += schemaDetails(c, if (prefix.isEmpty()) p else "$prefix.$p", depth + 1)
            }
            s.items?.takeIf { it.isIncompatible }?.let { items ->
                out += schemaDetails(items, if (prefix.isEmpty()) "items" else "$prefix.items", depth + 1)
            }
        }
        return out.ifEmpty { listOf("${if (prefix.isEmpty()) "the schema" else "'$prefix'"} changed incompatibly") }
    }

    private fun breaking(code: String, message: String, path: String) = Finding(Severity.WARN, FindingSource.BREAKING, code, message, path)

    private fun operationPointer(path: String, method: PathItem.HttpMethod) = "/paths/${esc(path)}/${method.name.lowercase()}"

    private fun esc(segment: String) = segment.replace("~", "~0").replace("/", "~1")

    private const val MAX_SCHEMA_DEPTH = 3
}
