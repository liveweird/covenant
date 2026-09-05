package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * An Avro schema (JSON) → [SchemaNode]: record → object (every field required unless its type is a
 * union with `"null"`, which becomes `nullable`), enum → string + enum values, array → items,
 * map → additionalProperties, union → anyOf (a two-branch `["null", T]` collapses to a nullable T),
 * fixed → string with a `fixed(size)` format, `logicalType` → format. A named type referenced by
 * name after its definition is a `CIRCULAR` marker carrying the name as its ref.
 */
class AvroSchemaMapper(private val budget: RenderBudget) {
    private val named = mutableMapOf<String, String>() // full name -> pointer of the definition

    fun node(schema: JsonNode, pointer: String, namespace: String? = null, depth: Int = 0): SchemaNode {
        if (!budget.take(depth)) return SchemaWalker.empty(pointer).copy(marker = SchemaMarker.TRUNCATED)
        return when {
            schema.isTextual -> byName(schema.asText(), pointer, namespace)
            schema.isArray -> union(schema, pointer, namespace, depth)
            schema.isObject -> complex(schema, pointer, namespace, depth)
            else -> SchemaWalker.empty(pointer).copy(raw = Nodes.pretty(schema))
        }
    }

    private fun byName(type: String, pointer: String, namespace: String?): SchemaNode {
        val base = SchemaWalker.empty(pointer)
        return when (type) {
            "null" -> base.copy(nullable = true)
            "boolean" -> base.copy(types = listOf("boolean"))
            "int" -> base.copy(types = listOf("integer"), format = "int32")
            "long" -> base.copy(types = listOf("integer"), format = "int64")
            "float" -> base.copy(types = listOf("number"), format = "float")
            "double" -> base.copy(types = listOf("number"), format = "double")
            "bytes" -> base.copy(types = listOf("string"), format = "bytes")
            "string" -> base.copy(types = listOf("string"))
            else -> {
                val full = if (type.contains('.') || namespace == null) type else "$namespace.$type"
                if (full in named) base.copy(ref = full, marker = SchemaMarker.CIRCULAR, title = full.substringAfterLast('.'))
                else base.copy(ref = type, marker = SchemaMarker.UNRESOLVED)
            }
        }
    }

    private fun union(branches: JsonNode, pointer: String, namespace: String?, depth: Int): SchemaNode {
        val nullable = branches.any { it.isTextual && it.asText() == "null" }
        val rest = branches.filterNot { it.isTextual && it.asText() == "null" }
        if (rest.size == 1) {
            val index = branches.indexOf(rest.single())
            return node(rest.single(), "$pointer/$index", namespace, depth + 1).copy(nullable = nullable)
        }
        val alternatives = branches.mapIndexedNotNull { i, b ->
            if (b.isTextual && b.asText() == "null") null else node(b, "$pointer/$i", namespace, depth + 1)
        }
        return SchemaWalker.empty(pointer).copy(nullable = nullable, anyOf = alternatives)
    }

    private fun complex(schema: JsonNode, pointer: String, namespace: String?, depth: Int): SchemaNode {
        val type = schema.path("type")
        if (!type.isTextual) return node(type, "$pointer/type", namespace, depth + 1)
        val ns = Nodes.text(schema, "namespace") ?: namespace
        val doc = Nodes.text(schema, "doc")
        val logical = Nodes.text(schema, "logicalType")
        val base = SchemaWalker.empty(pointer).copy(description = doc)
        return when (type.asText()) {
            "record", "error" -> {
                val name = Nodes.text(schema, "name") ?: "record"
                remember(name, ns, pointer)
                val fields = Nodes.objects(schema, "fields").mapIndexed { i, f ->
                    val fieldName = Nodes.text(f, "name") ?: "field$i"
                    val fieldNode = node(f.path("type"), "$pointer/fields/$i/type", ns, depth + 1)
                    val withDoc = fieldNode.copy(
                        description = Nodes.text(f, "doc") ?: fieldNode.description,
                        defaultValue = Nodes.stringifyPresent(f.path("default")),
                    )
                    PropertyView(fieldName, required = !fieldNode.nullable && !f.has("default"), schema = withDoc)
                }
                base.copy(types = listOf("object"), title = name, properties = fields)
            }
            "enum" -> {
                val name = Nodes.text(schema, "name") ?: "enum"
                remember(name, ns, pointer)
                val symbols = Nodes.strings(schema, "symbols")
                base.copy(types = listOf("string"), title = name, enumValues = symbols, defaultValue = Nodes.text(schema, "default"))
            }
            "array" -> base.copy(types = listOf("array"), items = node(schema.path("items"), "$pointer/items", ns, depth + 1))
            "map" -> base.copy(
                types = listOf("object"),
                additionalProperties = node(schema.path("values"), "$pointer/values", ns, depth + 1),
                additionalPropertiesAllowed = true,
            )
            "fixed" -> {
                val name = Nodes.text(schema, "name") ?: "fixed"
                remember(name, ns, pointer)
                base.copy(types = listOf("string"), title = name, format = logical ?: "fixed(${Nodes.int(schema, "size") ?: "?"})")
            }
            else -> byName(type.asText(), pointer, ns).let { it.copy(description = doc, format = logical ?: it.format) }
        }
    }

    private fun remember(name: String, namespace: String?, pointer: String) {
        val full = if (name.contains('.') || namespace == null) name else "$namespace.$name"
        named.putIfAbsent(full, pointer)
    }
}
