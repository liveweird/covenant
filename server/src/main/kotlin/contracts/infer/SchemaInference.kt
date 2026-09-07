package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.render.RenderBudget
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * JSON samples → a 2020-12 / OpenAPI-3.1-compatible JSON Schema, insertion-ordered. Every HTTP
 * body, AsyncAPI payload and ODCS row funnels through this ONE inferencer. Types merge across
 * every sample seen at one position; a `format` is asserted only when EVERY non-null string at
 * that position matches it. See `.claude/docs/contract-standards.md` "Inference" for the rules.
 */
object SchemaInference {
    private val NODES = JsonNodeFactory.instance

    private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$")
    private val UUID_FORMAT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val EMAIL_FORMAT = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val URI_FORMAT = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")
    private val FORMATS: List<Pair<String, Regex>> = listOf(
        "date-time" to DATE_TIME,
        "date" to DATE,
        "time" to TIME,
        "uuid" to UUID_FORMAT,
        "email" to EMAIL_FORMAT,
        "uri" to URI_FORMAT,
    )

    /** [pointer] is a JSON pointer into the document BEING GENERATED — where notes point back to. */
    fun infer(samples: List<JsonNode>, budget: RenderBudget, notes: Notes, pointer: String, depth: Int = 0): ObjectNode {
        val schema = NODES.objectNode()
        if (!budget.take(depth)) {
            notes.truncated(pointer)
            return schema
        }
        val types = typesOf(samples)
        applyType(schema, types, notes, pointer)
        val nonNull = types - "null"
        when {
            "object" in nonNull -> inferObject(schema, samples.filter { it.isObject }, budget, notes, pointer, depth)
            "array" in nonNull -> inferArray(schema, samples.filter { it.isArray }, budget, notes, pointer, depth)
            nonNull == setOf("string") -> inferFormat(schema, samples.filter { it.isTextual }.map { it.asText() })
        }
        return schema
    }

    private fun jsonType(node: JsonNode): String = when {
        node.isNull || node.isMissingNode -> "null"
        node.isObject -> "object"
        node.isArray -> "array"
        node.isBoolean -> "boolean"
        node.isFloatingPointNumber -> "number"
        node.isIntegralNumber -> "integer"
        node.isNumber -> "number"
        node.isTextual -> "string"
        else -> "string"
    }

    /** integer ∪ number merges to `number` — a mixed `1`/`1.0` position is a number, not mixed types. */
    private fun typesOf(samples: List<JsonNode>): Set<String> {
        val raw = samples.map { jsonType(it) }.toSet()
        return if ("integer" in raw && "number" in raw) raw - "integer" else raw
    }

    private fun applyType(schema: ObjectNode, types: Set<String>, notes: Notes, pointer: String) {
        if (types.isEmpty()) return
        val nonNull = (types - "null").sorted()
        val hasNull = "null" in types
        if (nonNull.size > 1) {
            notes.warn(
                "INFER_MIXED_TYPES",
                "Multiple JSON types were observed at $pointer (${nonNull.joinToString()}) — using a type array",
                pointer,
            )
        }
        val ordered = nonNull + (if (hasNull) listOf("null") else emptyList())
        if (ordered.size <= 1) {
            schema.put("type", ordered.singleOrNull() ?: "null")
        } else {
            val arr = schema.putArray("type")
            ordered.forEach { arr.add(it) }
        }
    }

    private fun inferObject(schema: ObjectNode, objects: List<JsonNode>, budget: RenderBudget, notes: Notes, pointer: String, depth: Int) {
        if (objects.isEmpty()) return
        val keys = LinkedHashSet<String>()
        objects.forEach { obj -> obj.fieldNames().forEach { keys += it } }
        val properties = schema.putObject("properties")
        val required = mutableListOf<String>()
        keys.forEach { key ->
            val values = objects.mapNotNull { it[key] }
            if (objects.all { it.has(key) && !it[key].isNull }) required += key
            properties.set<ObjectNode>(key, infer(values, budget, notes, "$pointer/properties/${esc(key)}", depth + 1))
        }
        if (required.isNotEmpty()) {
            val arr = schema.putArray("required")
            required.forEach { arr.add(it) }
        }
    }

    private fun inferArray(schema: ObjectNode, arrays: List<JsonNode>, budget: RenderBudget, notes: Notes, pointer: String, depth: Int) {
        val elements = arrays.flatMap { it.elements().asSequence().toList() }
        if (elements.isEmpty()) return // "all empty → type: array only" — no items key
        schema.set<ObjectNode>("items", infer(elements, budget, notes, "$pointer/items", depth + 1))
    }

    private fun inferFormat(schema: ObjectNode, values: List<String>) {
        if (values.isEmpty()) return
        val format = FORMATS.firstOrNull { (_, regex) -> values.all { regex.matches(it) } }?.first
        if (format != null) schema.put("format", format)
    }

    private fun esc(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
}
