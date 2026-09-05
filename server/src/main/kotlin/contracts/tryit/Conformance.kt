package ch.nokillswit.contracts.tryit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.checks.VendoredSchemas
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.OpenApi30
import com.networknt.schema.dialect.OpenApi31
import com.networknt.schema.resource.SchemaLoader
import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema as AvroSchema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory
import java.io.IOException

/**
 * Live conformance (milestone 3c): a try's observation measured against the document's OWN
 * schemas. The whole document is registered in an offline networknt registry under a synthetic
 * IRI, so a sub-schema is addressed by its JSON pointer and its internal `$ref`s
 * (`#/components/schemas/Pet`) resolve in-document — nothing is ever fetched. OpenAPI 3.0 and 3.1
 * documents get networknt's OpenAPI dialects (`nullable`, `discriminator`); AsyncAPI payload
 * schemas the 2020-12 dialect the checks use; Avro payloads go through Apache Avro's JSON decoder.
 */
class DocumentSchemas private constructor(private val registry: SchemaRegistry, private val root: JsonNode) {

    /** The schema at [pointer], or null when the node is absent. */
    fun schemaAt(pointer: String): Schema? {
        if (root.at(pointer).isMissingNode) return null
        return registry.getSchema(SchemaLocation.of("$DOCUMENT_IRI#$pointer"))
    }

    /** One networknt error → one CONFORMANCE finding; instance paths carry [instancePrefix] (`/body`, `/messages/0/payload`). */
    fun validate(schema: Schema, instance: JsonNode, code: String, instancePrefix: String): List<Finding> =
        schema.validate(instance).map { error ->
            Finding(
                severity = Severity.ERROR,
                source = FindingSource.CONFORMANCE,
                code = code,
                message = error.message.removePrefix("${error.instanceLocation}: ").trim(),
                path = instancePrefix + (VendoredSchemas.pointerOf(error.instanceLocation.toString()) ?: ""),
            )
        }

    companion object {
        const val DOCUMENT_IRI = "urn:covenant:try:document"
        private val mapper = ObjectMapper()

        fun of(type: ContractType, root: JsonNode): DocumentSchemas {
            // The document is served to the registry's loader under the synthetic IRI (a Map resource, no I/O),
            // so `#/components/...` refs inside any sub-schema resolve against it — the VendoredSchemas offline idiom.
            val resources = mapOf(DOCUMENT_IRI to mapper.writeValueAsString(root))
            val dialect: Dialect? = when {
                type != ContractType.OPENAPI -> null
                root.path("openapi").asText().startsWith("3.0") -> OpenApi30.getInstance()
                else -> OpenApi31.getInstance()
            }
            val registry = if (dialect != null) {
                SchemaRegistry.withDefaultDialect(dialect) { b -> b.schemaLoader { offline(it, resources) } }
            } else {
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { b -> b.schemaLoader { offline(it, resources) } }
            }
            return DocumentSchemas(registry, root)
        }

        private fun offline(loader: SchemaLoader.Builder, resources: Map<String, String>) {
            loader.fetchRemoteResources(false)
            loader.resourceLoaders { it.resources(resources) }
        }
    }
}

/** The shared JSON parsing, the finding constructors and the codes the three legs emit. */
object Conformance {
    const val STATUS_UNDECLARED = "STATUS_UNDECLARED"
    const val MEDIA_TYPE_UNDECLARED = "MEDIA_TYPE_UNDECLARED"
    const val RESPONSE_SCHEMA_MISMATCH = "RESPONSE_SCHEMA_MISMATCH"
    const val RESPONSE_NOT_JSON = "RESPONSE_NOT_JSON"
    const val BODY_NOT_VALIDATED = "BODY_NOT_VALIDATED"
    const val RESPONSE_HEADER_MISSING = "RESPONSE_HEADER_MISSING"
    const val REQUEST_SCHEMA_MISMATCH = "REQUEST_SCHEMA_MISMATCH"
    const val REQUEST_BODY_MISSING = "REQUEST_BODY_MISSING"
    const val PAYLOAD_SCHEMA_MISMATCH = "PAYLOAD_SCHEMA_MISMATCH"
    const val PAYLOAD_NOT_JSON = "PAYLOAD_NOT_JSON"
    const val PAYLOAD_NOT_VALIDATED = "PAYLOAD_NOT_VALIDATED"

    private val json = ObjectMapper()

    fun parseJson(text: String): JsonNode? = try {
        json.readTree(text)
    } catch (_: JacksonException) {
        null
    }

    fun isJsonMediaType(mediaType: String?): Boolean {
        val base = mediaType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return base == "application/json" || base.endsWith("+json") || base == "text/json"
    }

    fun error(code: String, message: String, path: String? = null) = Finding(Severity.ERROR, FindingSource.CONFORMANCE, code, message, path)
    fun warn(code: String, message: String, path: String? = null) = Finding(Severity.WARN, FindingSource.CONFORMANCE, code, message, path)
    fun info(code: String, message: String, path: String? = null) = Finding(Severity.INFO, FindingSource.CONFORMANCE, code, message, path)

    /** Follows a `$ref` one hop when the node is one — `(pointer, node)` of what to read. */
    internal fun follow(root: JsonNode, pointer: String): Pair<String, JsonNode> {
        val node = root.at(pointer)
        val ref = node.path("\$ref").takeIf { it.isTextual }?.asText()
        if (ref == null || !ref.startsWith("#/")) return pointer to node
        val target = ref.drop(1)
        return target to root.at(target)
    }
}

/** The OpenAPI leg: the response (status, media type, schema, declared headers) and the request body before it is sent. */
object HttpConformance {
    fun assessResponse(
        root: JsonNode,
        schemas: DocumentSchemas,
        method: String,
        pathTemplate: String,
        status: Int,
        contentType: String?,
        body: String?,
        headers: Map<String, String>,
    ): ConformanceReport {
        val opPointer = "/paths/${TryCatalog.esc(pathTemplate)}/${method.lowercase()}"
        val responses = root.at("$opPointer/responses")
        val key = listOf(status.toString(), "${status / 100}XX", "default").firstOrNull { responses.has(it) }
        if (key == null) {
            val message = "Response status $status is not declared for $method $pathTemplate"
            return ConformanceReport.of(listOf(Conformance.error(Conformance.STATUS_UNDECLARED, message, "$opPointer/responses")))
        }
        val (pointer, response) = Conformance.follow(root, "$opPointer/responses/${TryCatalog.esc(key)}")
        val findings = mutableListOf<Finding>()
        TryCatalog.fields(response.path("headers")).forEach { (name, _) ->
            if (headers.keys.none { it.equals(name, ignoreCase = true) }) {
                val where = "$pointer/headers/${TryCatalog.esc(name)}"
                findings += Conformance.warn(Conformance.RESPONSE_HEADER_MISSING, "Declared response header '$name' is missing", where)
            }
        }
        val media = mediaSchema(root, pointer, contentType)
        if (response.path("content").isObject && media == null) {
            val message = "Response media type '${contentType ?: "none"}' is not declared for status $key"
            findings += Conformance.warn(Conformance.MEDIA_TYPE_UNDECLARED, message, "$pointer/content")
        } else if (media?.schemaPointer != null) {
            findings += bodyFindings(schemas, media.schemaPointer, contentType, body, BodyCodes.RESPONSE)
        }
        return ConformanceReport.of(findings, media?.schemaPointer)
    }

    /** The request body against the operation's request schema — WARNs (the request is still sent; the point is to observe). */
    fun assessRequest(
        root: JsonNode,
        schemas: DocumentSchemas,
        method: String,
        pathTemplate: String,
        contentType: String?,
        body: String?,
    ): List<Finding> {
        val opPointer = "/paths/${TryCatalog.esc(pathTemplate)}/${method.lowercase()}"
        if (root.at("$opPointer/requestBody").isMissingNode) return emptyList()
        val (pointer, requestBody) = Conformance.follow(root, "$opPointer/requestBody")
        if (body.isNullOrBlank()) {
            val required = requestBody.path("required").asBoolean(false)
            if (!required) return emptyList()
            return listOf(Conformance.warn(Conformance.REQUEST_BODY_MISSING, "The operation declares a required request body", pointer))
        }
        val schemaPointer = mediaSchema(root, pointer, contentType)?.schemaPointer ?: return emptyList()
        return bodyFindings(schemas, schemaPointer, contentType, body, BodyCodes.REQUEST).map { it.copy(severity = Severity.WARN) }
    }

    private data class MediaMatch(val key: String, val schemaPointer: String?)

    private data class BodyCodes(val mismatch: String, val notJson: String, val prefix: String) {
        companion object {
            val RESPONSE = BodyCodes(Conformance.RESPONSE_SCHEMA_MISMATCH, Conformance.RESPONSE_NOT_JSON, "/body")
            val REQUEST = BodyCodes(Conformance.REQUEST_SCHEMA_MISMATCH, Conformance.REQUEST_SCHEMA_MISMATCH, "/request")
        }
    }

    /** The content entry for the content type — exact, then `+json` → `application/json`, then the wildcard media type. */
    private fun mediaSchema(root: JsonNode, ownerPointer: String, contentType: String?): MediaMatch? {
        val content = root.at("$ownerPointer/content")
        if (!content.isObject) return null
        val base = contentType?.substringBefore(';')?.trim()?.lowercase()
        val fallbacks = if (Conformance.isJsonMediaType(base)) listOf("application/json", "*/*") else listOf("*/*")
        val declared = content.fieldNames().asSequence().toList()
        val key = (listOfNotNull(base) + fallbacks).firstNotNullOfOrNull { c -> declared.firstOrNull { it.equals(c, ignoreCase = true) } }
            ?: return null
        val schemaPointer = "$ownerPointer/content/${TryCatalog.esc(key)}/schema"
        return MediaMatch(key, schemaPointer.takeIf { !root.at(it).isMissingNode })
    }

    private fun bodyFindings(
        schemas: DocumentSchemas,
        schemaPointer: String,
        contentType: String?,
        body: String?,
        codes: BodyCodes,
    ): List<Finding> {
        if (!Conformance.isJsonMediaType(contentType)) {
            val message = "Only JSON bodies are validated against the schema ('${contentType ?: "none"}')"
            return listOf(Conformance.info(Conformance.BODY_NOT_VALIDATED, message, schemaPointer))
        }
        val instance = Conformance.parseJson(body.orEmpty())
            ?: return listOf(Conformance.error(codes.notJson, "The body is not valid JSON", codes.prefix))
        val schema = schemas.schemaAt(schemaPointer) ?: return emptyList()
        return schemas.validate(schema, instance, codes.mismatch, codes.prefix)
    }
}

/** The AsyncAPI leg: a message payload against its declared schema (JSON Schema by default, Avro when the format says so). */
object PayloadConformance {
    fun assess(
        root: JsonNode,
        schemas: DocumentSchemas,
        payloadPointer: String?,
        schemaFormat: String?,
        payload: String,
        prefix: String,
    ): List<Finding> {
        if (payloadPointer == null) {
            return listOf(Conformance.info(Conformance.PAYLOAD_NOT_VALIDATED, "The message declares no payload schema"))
        }
        val format = schemaFormat?.lowercase()
        return when {
            format != null && format.contains("avro") -> avro(root.at(payloadPointer), payload, prefix)
            format == null || JSON_FORMATS.any { format.contains(it) } -> {
                val instance = Conformance.parseJson(payload)
                    ?: return listOf(Conformance.error(Conformance.PAYLOAD_NOT_JSON, "The payload is not valid JSON", prefix))
                val schema = schemas.schemaAt(payloadPointer) ?: return emptyList()
                schemas.validate(schema, instance, Conformance.PAYLOAD_SCHEMA_MISMATCH, prefix)
            }
            else -> {
                val message = "Payload schema format '$schemaFormat' is not validated"
                listOf(Conformance.info(Conformance.PAYLOAD_NOT_VALIDATED, message, payloadPointer))
            }
        }
    }

    private val JSON_FORMATS = listOf("json", "asyncapi", "openapi")

    private fun avro(schemaNode: JsonNode, payload: String, prefix: String): List<Finding> = try {
        val schema = AvroSchema.Parser().parse(schemaNode.toString())
        GenericDatumReader<Any>(schema).read(null, DecoderFactory.get().jsonDecoder(schema, payload))
        emptyList()
    } catch (e: AvroRuntimeException) {
        listOf(Conformance.error(Conformance.PAYLOAD_SCHEMA_MISMATCH, "Avro: ${firstLine(e) ?: "no match"}", prefix))
    } catch (e: IOException) {
        listOf(Conformance.error(Conformance.PAYLOAD_NOT_JSON, "Avro JSON decoding failed: ${firstLine(e)}", prefix))
    }

    private fun firstLine(e: Exception) = e.message?.lineSequence()?.first()?.trim()
}
