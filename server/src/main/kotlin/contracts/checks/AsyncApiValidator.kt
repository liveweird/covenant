package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode
import org.apache.avro.Schema as AvroSchema

/**
 * The AsyncAPI pass: the document against the vendored schema for its declared version
 * (`asyncapi:` 2.6.0 / 3.0.0 / 3.1.0 — anything else is an unsupported-version SCHEMA error),
 * then the payload walk — every message payload is either an Avro schema (Apache Avro parses
 * it) or a JSON Schema (meta-validated against the 2020-12 meta-schema). Protobuf and other
 * formats are left unchecked with an INFO; a payload that is a `$ref` is skipped here (the
 * document-level pass already resolved internal refs, an external one is an INFO).
 */
object AsyncApiValidator {
    const val CODE_SCHEMA = "ASYNCAPI_SCHEMA"
    const val CODE_UNSUPPORTED = "UNSUPPORTED_SPEC_VERSION"
    const val CODE_PAYLOAD_SCHEMA = "PAYLOAD_SCHEMA_INVALID"
    const val CODE_AVRO = "AVRO_INVALID"
    const val CODE_PAYLOAD_UNCHECKED = "PAYLOAD_FORMAT_UNCHECKED"
    const val CODE_EXTERNAL_REF = "EXTERNAL_REF_UNRESOLVED"

    fun validate(root: JsonNode): List<Finding> {
        val version = root.path("asyncapi").asText()
        val schema = VendoredSchemas.asyncApi(version)
            ?: return listOf(
                Finding(
                    Severity.ERROR, FindingSource.SCHEMA, CODE_UNSUPPORTED,
                    "Unsupported AsyncAPI version '$version' — " +
                        "${VendoredSchemas.ASYNCAPI_VERSIONS.sorted().joinToString(", ")} are supported",
                    "/asyncapi",
                ),
            )
        val findings = VendoredSchemas.validate(schema, root, CODE_SCHEMA).toMutableList()
        findings += payloadFindings(root, version.startsWith("3."))
        return findings
    }

    /** Every `(pointer, payloadNode, schemaFormat)` triple a document declares. */
    internal fun payloads(root: JsonNode, v3: Boolean): List<Payload> {
        val out = mutableListOf<Payload>()
        fun message(pointer: String, msg: JsonNode) {
            val payload = msg.path("payload")
            if (payload.isMissingNode || payload.isNull) return
            // 3.x multi-format: payload = { schemaFormat, schema }; otherwise the payload IS the schema
            // and the format comes from the message (2.x) or defaults to JSON Schema.
            if (payload.isObject && payload.has("schemaFormat") && payload.has("schema")) {
                out += Payload("$pointer/payload/schema", payload["schema"], payload["schemaFormat"].asText())
            } else {
                out += Payload("$pointer/payload", payload, msg.path("schemaFormat").takeIf { it.isTextual }?.asText())
            }
        }
        root.path("components").path("messages").fields().forEach { (name, msg) -> message("/components/messages/${esc(name)}", msg) }
        root.path("channels").fields().forEach { (channelName, channel) ->
            val base = "/channels/${esc(channelName)}"
            if (v3) {
                channel.path("messages").fields().forEach { (name, msg) -> message("$base/messages/${esc(name)}", msg) }
            } else {
                operationMessages(channel, base).forEach { (pointer, msg) -> message(pointer, msg) }
            }
        }
        return out
    }

    /** 2.x: the message (or each `oneOf` alternative) under `publish`/`subscribe`. */
    private fun operationMessages(channel: JsonNode, base: String): List<Pair<String, JsonNode>> =
        listOf("publish", "subscribe").flatMap { op ->
            val msg = channel.path(op).path("message")
            when {
                msg.isMissingNode -> emptyList()
                msg.has("oneOf") -> msg["oneOf"].mapIndexed { i, m -> "$base/$op/message/oneOf/$i" to m }
                else -> listOf("$base/$op/message" to msg)
            }
        }

    internal data class Payload(val pointer: String, val schema: JsonNode, val format: String?)

    private fun payloadFindings(root: JsonNode, v3: Boolean): List<Finding> = payloads(root, v3).flatMap { p ->
        val ref = p.schema.path("\$ref")
        when {
            ref.isTextual && !ref.asText().startsWith("#") -> listOf(
                Finding(
                    Severity.INFO,
                    FindingSource.SEMANTIC,
                    CODE_EXTERNAL_REF,
                    "External \$ref '${ref.asText()}' is not resolved — Covenant checks self-contained documents",
                    p.pointer,
                ),
            )
            ref.isTextual -> emptyList() // an internal ref: the target is checked where it is declared
            p.format == null || p.format.contains("json", ignoreCase = true) || p.format.contains("asyncapi", ignoreCase = true) ->
                VendoredSchemas.validate(
                    VendoredSchemas.jsonSchema202012,
                    p.schema,
                    CODE_PAYLOAD_SCHEMA,
                ).map { it.copy(path = p.pointer + (it.path ?: "")) }
            p.format.contains("avro", ignoreCase = true) -> avro(p)
            else -> listOf(
                Finding(
                    Severity.INFO,
                    FindingSource.SEMANTIC,
                    CODE_PAYLOAD_UNCHECKED,
                    "Payload schema format '${p.format}' is not validated by Covenant",
                    p.pointer,
                ),
            )
        }
    }

    private fun avro(p: Payload): List<Finding> = try {
        AvroSchema.Parser().parse(p.schema.toString())
        emptyList()
    } catch (e: RuntimeException) {
        // Avro's SchemaParseException / AvroTypeException are RuntimeExceptions without a common Avro base.
        listOf(
            Finding(
                Severity.ERROR,
                FindingSource.SCHEMA,
                CODE_AVRO,
                "Invalid Avro schema: ${e.message?.lineSequence()?.first()?.trim() ?: "unparseable"}",
                p.pointer,
            ),
        )
    }

    private fun esc(segment: String) = segment.replace("~", "~0").replace("/", "~1")
}
