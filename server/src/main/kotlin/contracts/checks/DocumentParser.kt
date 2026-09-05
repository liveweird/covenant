package ch.nokillswit.contracts.checks

import ch.nokillswit.contracts.ContractType
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.yaml.snakeyaml.LoaderOptions

/** The parsed document, or the one SYNTAX finding that stopped the pipeline. */
sealed interface ParseOutcome {
    data class Parsed(val format: DocumentFormat, val root: JsonNode) : ParseOutcome
    data class Failed(val format: DocumentFormat, val finding: Finding) : ParseOutcome
}

/**
 * Step one of every check: detect the format from the first significant character (`{`/`[` →
 * JSON, else YAML — JSON is YAML anyway, the label only feeds the content type on download),
 * parse into a Jackson tree WITHOUT resolving anything, and turn a parser failure into the
 * single HARD finding with its line/column. The YAML loader runs with a code-point limit, and
 * Jackson's event-based YAML parser never EXPANDS aliases (an alias bomb stays linear in the
 * text size — pinned by DocumentParserTest), so a hostile document costs its own bytes, no more.
 */
object DocumentParser {
    /** Four times the 2 MiB document cap — room for multi-byte text, still bounded. */
    private const val YAML_CODE_POINT_LIMIT = 8 * 1024 * 1024

    private val yaml: ObjectMapper = YAMLMapper(
        YAMLFactory.builder().loaderOptions(LoaderOptions().apply { codePointLimit = YAML_CODE_POINT_LIMIT }).build(),
    )
    private val json: ObjectMapper = ObjectMapper()

    fun detectFormat(content: String): DocumentFormat {
        val first = content.firstOrNull { !it.isWhitespace() }
        return if (first == '{' || first == '[') DocumentFormat.json else DocumentFormat.yaml
    }

    fun parse(content: String): ParseOutcome {
        val format = detectFormat(content)
        if (content.isBlank()) {
            return ParseOutcome.Failed(format, syntax("The document is empty", null, null))
        }
        return try {
            val root = (if (format == DocumentFormat.json) json else yaml).readTree(content)
            if (root == null || root.isNull || root.isMissingNode) {
                ParseOutcome.Failed(format, syntax("The document is empty", null, null))
            } else if (!root.isObject) {
                ParseOutcome.Failed(
                    format,
                    syntax("The document root must be an object (a mapping), not a ${root.nodeType.name.lowercase()}", 1, 1),
                )
            } else {
                ParseOutcome.Parsed(format, root)
            }
        } catch (e: JsonProcessingException) {
            val loc = e.location
            ParseOutcome.Failed(format, syntax(cleanMessage(e), loc?.lineNr?.takeIf { it > 0 }, loc?.columnNr?.takeIf { it > 0 }))
        } catch (e: IllegalArgumentException) {
            // SnakeYAML's limit/alias exceptions surface as IAE from the YAML mapper.
            ParseOutcome.Failed(format, syntax(e.message ?: "The document could not be parsed", null, null))
        }
    }

    /**
     * The type gate: the document must declare the standard the contract is typed with — and
     * a Swagger 2.0 root is refused outright (Covenant stores OpenAPI 3.x only). HARD.
     */
    fun typeGate(type: ContractType, root: JsonNode): Finding? = when (type) {
        ContractType.OPENAPI -> when {
            root.has(
                "swagger",
            ) -> syntax("Swagger 2.0 documents are not supported — convert to OpenAPI 3.x", 1, 1, "UNSUPPORTED_SPEC_VERSION", "/swagger")
            !root.path("openapi").isTextual -> mismatch("an OpenAPI document declares a root `openapi: 3.x.y` field", "/openapi")
            !root["openapi"].asText().startsWith("3.") ->
                syntax(
                    "Unsupported OpenAPI version '${root["openapi"].asText()}' — 3.0.x or 3.1.x expected",
                    1,
                    1,
                    "UNSUPPORTED_SPEC_VERSION",
                    "/openapi",
                )
            else -> null
        }
        ContractType.ASYNCAPI -> when {
            !root.path(
                "asyncapi",
            ).isTextual -> mismatch("an AsyncAPI document declares a root `asyncapi: 2.6.0 | 3.x.y` field", "/asyncapi")
            else -> null
        }
        ContractType.ODCS -> when {
            root.path("kind").asText() != "DataContract" -> mismatch("an ODCS document declares `kind: DataContract`", "/kind")
            !root.path("apiVersion").isTextual -> mismatch("an ODCS document declares a root `apiVersion: v3.x.y` field", "/apiVersion")
            else -> null
        }
    }

    private fun mismatch(expectation: String, path: String) =
        syntax("The document does not match the contract type: $expectation", 1, 1, "TYPE_MISMATCH", path)

    private fun syntax(message: String, line: Int?, column: Int?, code: String = "SYNTAX_ERROR", path: String? = null) =
        Finding(Severity.ERROR, FindingSource.SYNTAX, code, message, path, line, column)

    /** Jackson appends "[Source: ...; line: N, column: M]" — the location travels as fields, not text. */
    private fun cleanMessage(e: JsonProcessingException): String =
        (e.originalMessage ?: e.message ?: "The document could not be parsed").lineSequence().first().trim()
}
