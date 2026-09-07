package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.tryit.OdcsTypes
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

private val ODCS_LOGICAL_TYPES = setOf("string", "date", "number", "integer", "object", "array", "boolean")

/**
 * A relation's columns, rows, or both → ODCS 3.0.2. One dataset per [RelationSample]; a
 * schema-qualified name (`public.users`) keeps only the bare relation name — ODCS datasets carry
 * no schema, so `SqlTry.IDENTIFIER`-style qualifiers are not representable. Columns win for
 * type/required; rows contribute properties columns did not declare.
 */
object OdcsInference {
    private val NODES = JsonNodeFactory.instance

    fun build(request: InferRequest, budget: RenderBudget, notes: Notes): ObjectNode {
        val root = NODES.objectNode()
        val title = request.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.relations.firstOrNull()?.name?.substringAfterLast('.')
            ?: "Inferred data"
        root.put("apiVersion", "v3.0.2")
        root.put("kind", "DataContract")
        root.put("id", slug(title))
        root.put("name", title)
        root.put("version", request.version?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0")
        root.put("status", "draft")
        root.putObject("description").put("purpose", "")
        val schema = root.putArray("schema")
        request.relations.forEachIndexed { i, relation -> buildDataset(schema, relation, budget, notes, i) }
        notes.info("INFER_SAMPLES_MERGED", "${request.relations.size} relation sample(s) were merged into this document", "/schema")
        return root
    }

    private fun buildDataset(schemaArr: ArrayNode, relation: RelationSample, budget: RenderBudget, notes: Notes, index: Int) {
        val bare = relation.name.substringAfterLast('.')
        if (bare != relation.name) {
            notes.info(
                "INFER_SCHEMA_QUALIFIED",
                "Relation '${relation.name}' is schema-qualified — ODCS datasets carry no schema, only '$bare' is kept",
                "/schema/$index",
            )
        }
        val dataset = schemaArr.addObject()
        dataset.put("name", bare)
        dataset.put("physicalName", bare)
        relation.physicalType?.let { dataset.put("physicalType", it.lowercase()) }
        dataset.put("logicalType", "object")
        val isView = relation.physicalType.equals("view", ignoreCase = true)
        if (isView) {
            notes.info(
                "INFER_VIEW_NULLABILITY",
                "Relation '$bare' is a view — nullability is not in its catalog, so 'required' is omitted",
                "/schema/$index",
            )
        }
        val columnProps = LinkedHashMap<String, ObjectNode>()
        relation.columns.forEachIndexed { i, column ->
            columnProps[column.name] = columnProperty(column, isView, notes, "/schema/$index/properties/$i")
        }
        val rowProps = if (relation.rows.isNotEmpty()) rowProperties(relation.rows, isView, budget, notes, "/schema/$index") else emptyMap()
        val merged = LinkedHashMap<String, ObjectNode>(columnProps)
        rowProps.forEach { (name, node) -> merged.putIfAbsent(name, node) }
        val propsArr = dataset.putArray("properties")
        merged.values.forEach { propsArr.add(it) }
    }

    private fun columnProperty(column: RelationColumn, isView: Boolean, notes: Notes, pointer: String): ObjectNode {
        val prop = NODES.objectNode()
        prop.put("name", column.name)
        prop.put("physicalName", column.name)
        prop.put("physicalType", column.dbType)
        val family = OdcsTypes.logicalTypeOf(column.dbType)
        if (family != null) {
            prop.put("logicalType", family)
        } else {
            prop.put("logicalType", "string")
            notes.info("INFER_TYPE_UNKNOWN", "Column '${column.name}' has database type '${column.dbType}' — mapped to 'string'", pointer)
        }
        if (family == "array") prop.putObject("items").put("logicalType", arrayItemFamily(column.dbType))
        if (!isView) prop.put("required", !column.nullable)
        column.primaryKeyPosition?.let {
            prop.put("primaryKey", true)
            prop.put("primaryKeyPosition", it)
        }
        return prop
    }

    private fun arrayItemFamily(dbType: String): String {
        val t = dbType.lowercase().trim()
        val base = when {
            t.startsWith("_") -> t.removePrefix("_")
            t.endsWith("[]") -> t.removeSuffix("[]")
            else -> t
        }
        return OdcsTypes.logicalTypeOf(base) ?: "string"
    }

    private fun rowProperties(
        rows: List<String>,
        isView: Boolean,
        budget: RenderBudget,
        notes: Notes,
        pointer: String,
    ): LinkedHashMap<String, ObjectNode> {
        val nodes = rows.mapNotNull { parseJsonOrNull(it) }
        val schema = SchemaInference.infer(nodes, budget, notes, "$pointer/rows")
        return schemaToProperties(schema, isView)
    }

    private fun schemaToProperties(schema: JsonNode, isView: Boolean): LinkedHashMap<String, ObjectNode> {
        val required = schema.path("required").map { it.asText() }.toSet()
        val out = LinkedHashMap<String, ObjectNode>()
        schema.path("properties").fields().forEach { (name, propSchema) ->
            out[name] = jsonSchemaProperty(name, propSchema, name in required, isView)
        }
        return out
    }

    private fun jsonSchemaProperty(name: String, schema: JsonNode, required: Boolean, isView: Boolean): ObjectNode {
        val prop = NODES.objectNode()
        prop.put("name", name)
        prop.put("physicalName", name)
        val logical = odcsLogicalType(schema)
        prop.put("logicalType", logical)
        if (!isView) prop.put("required", required)
        if (logical == "object" && schema.path("properties").isObject) {
            val nested = prop.putArray("properties")
            schemaToProperties(schema, isView).values.forEach { nested.add(it) }
        }
        if (logical == "array" && !schema.path("items").isMissingNode) {
            prop.set<ObjectNode>("items", arrayItemProperty(schema.path("items")))
        }
        return prop
    }

    private fun arrayItemProperty(itemSchema: JsonNode): ObjectNode {
        val items = NODES.objectNode()
        val itemType = schemaTypes(itemSchema).firstOrNull { it != "null" } ?: "string"
        items.put("logicalType", if (itemType in ODCS_LOGICAL_TYPES) itemType else "string")
        return items
    }

    private fun odcsLogicalType(schema: JsonNode): String = when (schemaTypes(schema).firstOrNull { it != "null" }) {
        "string" -> if (schema.path("format").asText("") in setOf("date", "date-time")) "date" else "string"
        "integer" -> "integer"
        "number" -> "number"
        "boolean" -> "boolean"
        "object" -> "object"
        "array" -> "array"
        else -> "string"
    }

    private fun schemaTypes(schema: JsonNode): List<String> {
        val t = schema.path("type")
        return when {
            t.isTextual -> listOf(t.asText())
            t.isArray -> t.map { it.asText() }
            else -> emptyList()
        }
    }

    private fun slug(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "contract" }
}
