package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/** Which schema vocabulary a document speaks — OpenAPI 3.0's dialect differs from JSON Schema proper in a few keywords. */
enum class SchemaDialect { OPENAPI_30, JSON_SCHEMA }

/**
 * JSON Schema → [SchemaNode], one path for OpenAPI 3.0 (`nullable`, boolean `exclusiveMinimum`,
 * `example`) and 2020-12 / OpenAPI 3.1 (type arrays, `examples`, `prefixItems`, `$ref` beside
 * siblings). `$ref` chains are followed in-document up to [MAX_REF_HOPS]; the CYCLE RULE: `path`
 * holds the refs on the CURRENT expansion stack — re-entering one yields a `CIRCULAR` marker, while
 * two siblings referencing the same schema both expand. Every step spends the [RenderBudget].
 */
class SchemaWalker(private val root: JsonNode, private val budget: RenderBudget, private val dialect: SchemaDialect) {

    fun node(schema: JsonNode, pointer: String, path: Set<String> = emptySet(), depth: Int = 0): SchemaNode {
        if (!budget.take(depth)) return marker(pointer, SchemaMarker.TRUNCATED)
        if (schema.isBoolean) return empty(pointer).copy(additionalPropertiesAllowed = schema.asBoolean())
        if (!schema.isObject) return empty(pointer).copy(raw = Nodes.pretty(schema))
        val ref = Nodes.text(schema, "\$ref") ?: return build(schema, pointer, null, path, depth)
        val target = JsonPointers.pointerOf(ref)
        val resolved = target?.let { JsonPointers.resolve(root, ref) }
        return when {
            resolved == null -> marker(pointer, SchemaMarker.UNRESOLVED).copy(ref = ref, title = Nodes.text(schema, "title"))
            target in path || path.size >= MAX_REF_HOPS ->
                marker(pointer, SchemaMarker.CIRCULAR).copy(ref = ref, title = Nodes.text(resolved, "title"))
            else -> {
                // The referenced schema, with the referencing node's 3.1 sibling keywords overlaid (description, title…).
                val merged = if (schema.size() > 1) overlay(resolved, schema) else resolved
                build(merged, target!!, ref, path + target, depth)
            }
        }
    }

    private fun build(schema: JsonNode, pointer: String, ref: String?, path: Set<String>, depth: Int): SchemaNode {
        val (types, nullableFromType) = types(schema)
        val nullable = nullableFromType || (dialect == SchemaDialect.OPENAPI_30 && Nodes.bool(schema, "nullable"))
        val properties = Nodes.fields(schema.path("properties"))
        val required = Nodes.strings(schema, "required").toSet()
        // The node's own pointer joins the stack: a child referencing it is a re-entry, not a sibling.
        val stack = path + pointer
        val child = { key: String, n: JsonNode -> node(n, "$pointer/$key", stack, depth + 1) }
        val additional = schema.path("additionalProperties")
        val items = schema.path("items")
        val tupleItems = if (items.isArray) items else schema.path("prefixItems")
        return SchemaNode(
            pointer = pointer,
            ref = ref,
            types = types,
            nullable = nullable,
            format = Nodes.text(schema, "format"),
            title = Nodes.text(schema, "title"),
            description = Nodes.text(schema, "description"),
            deprecated = Nodes.bool(schema, "deprecated"),
            readOnly = Nodes.bool(schema, "readOnly"),
            writeOnly = Nodes.bool(schema, "writeOnly"),
            properties = properties.map { (name, s) ->
                PropertyView(name, name in required, child("properties/${JsonPointers.escape(name)}", s))
            },
            additionalProperties = additional.takeIf { it.isObject }?.let { child("additionalProperties", it) },
            additionalPropertiesAllowed = when {
                additional.isBoolean -> additional.asBoolean()
                additional.isObject -> true
                else -> null
            },
            items = items.takeIf { it.isObject || it.isBoolean }?.let { child("items", it) },
            tupleItems = tupleItems.takeIf { it.isArray }?.mapIndexed { i, s ->
                child("${if (items.isArray) "items" else "prefixItems"}/$i", s)
            }.orEmpty(),
            allOf = Nodes.schemas(schema, "allOf").mapIndexed { i, s -> child("allOf/$i", s) },
            anyOf = Nodes.schemas(schema, "anyOf").mapIndexed { i, s -> child("anyOf/$i", s) },
            oneOf = Nodes.schemas(schema, "oneOf").mapIndexed { i, s -> child("oneOf/$i", s) },
            not = schema.path("not").takeIf { it.isObject }?.let { child("not", it) },
            discriminator = schema.path("discriminator").takeIf { it.isObject }?.let {
                DiscriminatorView(Nodes.text(it, "propertyName") ?: "", Nodes.keyValues(it.path("mapping")))
            },
            enumValues = schema.path("enum").takeIf { it.isArray }?.map { Nodes.stringify(it) }.orEmpty(),
            constValue = Nodes.stringifyOrNull(schema.path("const")),
            defaultValue = Nodes.stringifyOrNull(schema.path("default")),
            examples = examples(schema),
            constraints = constraints(schema),
        )
    }

    /** `type` as a list minus `"null"` (→ nullable); inferred `object`/`array` from the keywords when absent. */
    private fun types(schema: JsonNode): Pair<List<String>, Boolean> {
        val declared = schema.path("type")
        val list = when {
            declared.isTextual -> listOf(declared.asText())
            declared.isArray -> declared.map { it.asText() }
            else -> emptyList()
        }
        val nullable = "null" in list
        val types = list.filter { it != "null" }
        val inferred = when {
            types.isNotEmpty() -> types
            schema.has("properties") || schema.has("additionalProperties") -> listOf("object")
            schema.has("items") || schema.has("prefixItems") -> listOf("array")
            else -> emptyList()
        }
        return inferred to nullable
    }

    private fun examples(schema: JsonNode): List<String> {
        val many = schema.path("examples").takeIf { it.isArray }?.map { Nodes.stringify(it) }.orEmpty()
        val one = schema.path("example").takeIf { !it.isMissingNode }?.let { listOf(Nodes.stringify(it)) }.orEmpty()
        return one + many
    }

    private fun constraints(schema: JsonNode): List<Constraint> {
        val out = mutableListOf<Constraint>()
        CONSTRAINT_KEYWORDS.forEach { key ->
            val v = schema.path(key)
            if (v.isMissingNode || v.isNull) return@forEach
            // OpenAPI 3.0: boolean exclusiveMinimum/Maximum qualifies minimum/maximum instead of carrying a number.
            if (v.isBoolean && key.startsWith("exclusive")) {
                val bound = key.removePrefix("exclusive").replaceFirstChar { it.lowercase() }
                if (v.asBoolean()) out += Constraint(key, Nodes.stringify(schema.path(bound)))
                return@forEach
            }
            out += Constraint(key, Nodes.stringify(v))
        }
        return out
    }

    private fun overlay(target: JsonNode, referencing: JsonNode): JsonNode {
        val merged = target.deepCopy<JsonNode>() as? com.fasterxml.jackson.databind.node.ObjectNode ?: return target
        Nodes.fields(referencing).forEach { (k, v) -> if (k != "\$ref") merged.set<JsonNode>(k, v) }
        return merged
    }

    private fun marker(pointer: String, marker: SchemaMarker) = empty(pointer).copy(marker = marker)

    companion object {
        const val MAX_REF_HOPS = 8
        private val CONSTRAINT_KEYWORDS = listOf(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern",
            "minItems", "maxItems", "uniqueItems", "minContains", "maxContains",
            "minProperties", "maxProperties",
        )

        fun empty(pointer: String) = SchemaNode(
            pointer = pointer, types = emptyList(), nullable = false, deprecated = false, readOnly = false, writeOnly = false,
            properties = emptyList(), tupleItems = emptyList(), allOf = emptyList(), anyOf = emptyList(), oneOf = emptyList(),
            enumValues = emptyList(), examples = emptyList(), constraints = emptyList(),
        )
    }
}
