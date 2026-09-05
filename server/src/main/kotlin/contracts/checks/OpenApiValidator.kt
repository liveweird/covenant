package ch.nokillswit.contracts.checks

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * The OpenAPI 3.0/3.1 structural+semantic pass: swagger-parser reads the text WITHOUT
 * resolving external `$ref`s (never a fetch — `setResolve(false)` is load-bearing) and validates
 * internal ones; every message it produces is a SOFT `SEMANTIC` error (Spectral's `oas3-schema`
 * in the checker covers the JSON-Schema-level structure). An external `$ref` is reported as an
 * INFO the SPA can point at — Covenant checks self-contained documents.
 */
object OpenApiValidator {
    const val CODE_PARSE = "OAS_PARSE"
    const val CODE_EXTERNAL_REF = "EXTERNAL_REF_UNRESOLVED"

    private val options = ParseOptions().apply {
        isResolve = false
        isValidateInternalRefs = true
        isValidateExternalRefs = false
    }

    fun validate(content: String): List<Finding> {
        val result = OpenAPIV3Parser().readContents(content, null, options)
        return result.messages.orEmpty().filter { it.isNotBlank() }.distinct().map { message ->
            Finding(
                severity = Severity.ERROR,
                source = FindingSource.SEMANTIC,
                code = CODE_PARSE,
                message = message.trim(),
                path = pathFromMessage(message),
            )
        }
    }

    /** swagger-parser messages read "attribute paths.'/pets'(get).responses is missing" — best-effort pointer. */
    private fun pathFromMessage(message: String): String? {
        val m = Regex("attribute ([A-Za-z0-9_.'/\\-()]+)").find(message) ?: return null
        val dotted = m.groupValues[1].trimEnd('.')
        val segments = dotted.split('.').flatMap { seg ->
            // "'/pets'(get)" → ["/pets", "get"]
            Regex("'([^']*)'|\\(([^)]*)\\)|([^'()]+)").findAll(seg).map { g -> g.groupValues.drop(1).first { it.isNotEmpty() } }.toList()
        }
        if (segments.isEmpty()) return null
        return "/" + segments.joinToString("/") { it.replace("~", "~0").replace("/", "~1") }
    }
}
