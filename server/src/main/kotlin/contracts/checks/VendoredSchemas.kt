package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/**
 * The official JSON Schemas Covenant validates against, loaded ONCE from the classpath
 * (`server/src/main/resources/schemas/` — see its README for versions and origins) into
 * networknt registries that never fetch anything remote: a `$ref` pointing outside a vendored
 * document resolves to nothing, never to the network. The 2020-12 meta-schema (payload
 * meta-validation) is the one networknt bundles.
 */
object VendoredSchemas {
    val ASYNCAPI_VERSIONS: Set<String> = setOf("2.6.0", "3.0.0", "3.1.0")
    val ODCS_VERSIONS: Set<String> = setOf("v3.0.0", "v3.0.1", "v3.0.2", "v3.1.0")

    private fun offline(version: SpecificationVersion): SchemaRegistry =
        SchemaRegistry.withDefaultDialect(version) { builder ->
            builder.schemaLoader { loader -> loader.fetchRemoteResources(false) }
        }

    // AsyncAPI's schemas declare draft-07; ODCS's declare 2019-09 — each registry defaults to
    // the dialect its documents were written in (the `$schema` keyword still wins where present).
    private val draft7 = offline(SpecificationVersion.DRAFT_7)
    private val draft201909 = offline(SpecificationVersion.DRAFT_2019_09)
    private val draft202012 = offline(SpecificationVersion.DRAFT_2020_12)

    private val asyncapi: Map<String, Schema> by lazy {
        ASYNCAPI_VERSIONS.associateWith { load(draft7, "/schemas/asyncapi/$it.json") }
    }
    private val odcs: Map<String, Schema> by lazy {
        ODCS_VERSIONS.associateWith { load(draft201909, "/schemas/odcs/odcs-json-schema-$it.json") }
    }

    /** The JSON Schema 2020-12 meta-schema — "is this document a valid schema?" for AsyncAPI payloads. */
    val jsonSchema202012: Schema by lazy {
        draft202012.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
    }

    fun asyncApi(version: String): Schema? = asyncapi[version]

    fun odcs(apiVersion: String): Schema? = odcs[apiVersion]

    private fun load(registry: SchemaRegistry, resource: String): Schema {
        val stream = VendoredSchemas::class.java.getResourceAsStream(resource)
            ?: error("Vendored schema missing from the classpath: $resource")
        return stream.use { registry.getSchema(it) }
    }

    /** One networknt error → one SCHEMA finding; the instance location (`$.a.b[0]`) becomes a JSON pointer. */
    fun toFinding(error: Error, code: String): Finding = Finding(
        severity = Severity.ERROR,
        source = FindingSource.SCHEMA,
        code = code,
        message = error.message.removePrefix("${error.instanceLocation}: ").trim(),
        path = pointerOf(error.instanceLocation.toString()),
        line = null,
        column = null,
    )

    /**
     * networknt 2.x prints an instance location as a JSON pointer (`/channels/foo/2`) — passed
     * through; the 1.x dotted form (`$.channels.foo[2].bar`) is still converted, and a bare root
     * (`` / `$`) is null.
     */
    internal fun pointerOf(instanceLocation: String): String? {
        val raw = instanceLocation.trim()
        if (raw.isEmpty() || raw == "$") return null
        if (raw.startsWith("/")) return raw
        val dotted = raw.removePrefix("$").replace(Regex("\\[(\\d+)]"), ".$1").replace(Regex("\\['([^']*)']"), ".$1")
        return dotted.split('.').filter { it.isNotEmpty() }
            .joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }
            .ifEmpty { null }
    }

    fun validate(schema: Schema, node: JsonNode, code: String): List<Finding> =
        schema.validate(node).map { toFinding(it, code) }
}
