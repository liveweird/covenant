package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode

/** Total accessors over any tree shape — the renderer never throws on a malformed document. */
internal object Nodes {
    const val MAX_EXAMPLE_CHARS = 4_000
    const val MAX_RAW_CHARS = 16_384

    fun text(node: JsonNode, field: String): String? = node.path(field).takeIf { it.isValueNode && !it.isNull }?.asText()

    fun bool(node: JsonNode, field: String, default: Boolean = false): Boolean =
        node.path(field).takeIf { it.isBoolean }?.asBoolean() ?: default

    fun int(node: JsonNode, field: String): Int? = node.path(field).takeIf { it.isIntegralNumber }?.asInt()

    fun strings(node: JsonNode, field: String): List<String> = node.path(field).takeIf { it.isArray }?.map { it.asText() }.orEmpty()

    fun objects(node: JsonNode, field: String): List<JsonNode> = node.path(field).takeIf { it.isArray }?.filter { it.isObject }.orEmpty()

    /** Array members that are schemas — objects or the boolean schemas `true`/`false`. */
    fun schemas(node: JsonNode, field: String): List<JsonNode> =
        node.path(field).takeIf { it.isArray }?.filter { it.isObject || it.isBoolean }.orEmpty()

    fun fields(node: JsonNode): List<Pair<String, JsonNode>> =
        node.takeIf { it.isObject }?.fields()?.asSequence()?.map { it.key to it.value }?.toList().orEmpty()

    /** Any JSON value as compact text — strings verbatim, everything else serialized. */
    fun stringify(node: JsonNode, cap: Int = MAX_EXAMPLE_CHARS): String {
        val s = if (node.isTextual) node.asText() else node.toString()
        return if (s.length > cap) s.take(cap) + "…" else s
    }

    fun stringifyOrNull(node: JsonNode): String? = node.takeIf { !it.isMissingNode && !it.isNull }?.let { stringify(it) }

    /** Like [stringifyOrNull] but a PRESENT JSON `null` is the text `null` (an Avro default of null is meaningful). */
    fun stringifyPresent(node: JsonNode): String? = node.takeIf { !it.isMissingNode }?.let { stringify(it) }

    /** `customProperties: [{property, value}]` (ODCS) or a plain object → key/value rows. */
    fun keyValues(node: JsonNode): List<KeyValue> = when {
        node.isArray -> node.filter { it.isObject }.map {
            KeyValue(text(it, "property") ?: text(it, "key") ?: "", stringifyOrNull(it.path("value")) ?: "")
        }
        node.isObject -> fields(node).map { (k, v) -> KeyValue(k, stringify(v)) }
        else -> emptyList()
    }

    fun pretty(node: JsonNode): String = stringify(TextNode(node.toPrettyString()), MAX_RAW_CHARS)
}
