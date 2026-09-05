package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode

/**
 * ODCS breaking changes (milestone 2), hand-written — no library covers the Open Data Contract
 * Standard. MAJOR per `.claude/docs/contract-standards.md`: a removed dataset (`schema[]` entry)
 * or property, a property newly `required`, a changed `logicalType`/`physicalType`, a removed
 * server. Datasets, properties and servers are matched by NAME (positions may move freely).
 * Pointers address the CANDIDATE document (a removal points at the parent that lost the entry).
 */
object OdcsBreaking {
    const val CODE_REMOVED_DATASET = "REMOVED_DATASET"
    const val CODE_REMOVED_PROPERTY = "REMOVED_PROPERTY"
    const val CODE_PROPERTY_NOW_REQUIRED = "PROPERTY_NOW_REQUIRED"
    const val CODE_CHANGED_LOGICAL_TYPE = "CHANGED_LOGICAL_TYPE"
    const val CODE_CHANGED_PHYSICAL_TYPE = "CHANGED_PHYSICAL_TYPE"
    const val CODE_REMOVED_SERVER = "REMOVED_SERVER"

    fun compare(old: JsonNode, new: JsonNode): List<Finding> {
        val out = mutableListOf<Finding>()
        val oldDatasets = named(old.path("schema"))
        val newDatasets = named(new.path("schema"))
        oldDatasets.keys.filter { it !in newDatasets }.forEach { name ->
            out += breaking(CODE_REMOVED_DATASET, "Dataset '$name' was removed", "/schema")
        }
        newDatasets.forEach { (name, dataset) ->
            val before = oldDatasets[name] ?: return@forEach
            out += properties(before.node, dataset.node, "/schema/${dataset.index}", "dataset '$name'")
        }
        val oldServers = named(old.path("servers"), key = "server")
        val newServers = named(new.path("servers"), key = "server")
        oldServers.keys.filter { it !in newServers }.forEach { name ->
            out += breaking(CODE_REMOVED_SERVER, "Server '$name' was removed", "/servers")
        }
        return out
    }

    private fun properties(old: JsonNode, new: JsonNode, pointer: String, owner: String): List<Finding> {
        val out = mutableListOf<Finding>()
        val before = named(old.path("properties"))
        val after = named(new.path("properties"))
        before.keys.filter { it !in after }.forEach { name ->
            out += breaking(CODE_REMOVED_PROPERTY, "Property '$name' of $owner was removed", "$pointer/properties")
        }
        after.forEach { (name, entry) ->
            val prev = before[name] ?: return@forEach
            val here = "$pointer/properties/${entry.index}"
            out += property(prev.node, entry.node, here, "'$name' of $owner")
            out += properties(prev.node, entry.node, here, "'$name' of $owner")
        }
        return out
    }

    private fun property(old: JsonNode, new: JsonNode, pointer: String, label: String): List<Finding> {
        val out = mutableListOf<Finding>()
        if (!old.path("required").asBoolean(false) && new.path("required").asBoolean(false)) {
            out += breaking(CODE_PROPERTY_NOW_REQUIRED, "Property $label is now required", "$pointer/required")
        }
        changedText(old, new, "logicalType")?.let { (from, to) ->
            out += breaking(
                CODE_CHANGED_LOGICAL_TYPE, "Property $label changed its logical type from '$from' to '$to'", "$pointer/logicalType",
            )
        }
        changedText(old, new, "physicalType")?.let { (from, to) ->
            out += breaking(
                CODE_CHANGED_PHYSICAL_TYPE, "Property $label changed its physical type from '$from' to '$to'", "$pointer/physicalType",
            )
        }
        return out
    }

    /** `(old, new)` when both sides declare the field and the values differ. */
    private fun changedText(old: JsonNode, new: JsonNode, field: String): Pair<String, String>? {
        val a = old.path(field).takeIf { it.isValueNode }?.asText() ?: return null
        val b = new.path(field).takeIf { it.isValueNode }?.asText() ?: return null
        return if (a == b) null else a to b
    }

    private data class Entry(val index: Int, val node: JsonNode)

    /** Array entries keyed by their `name` (or another key); unnamed entries are ignored. */
    private fun named(array: JsonNode, key: String = "name"): Map<String, Entry> {
        if (!array.isArray) return emptyMap()
        return array.withIndex()
            .mapNotNull { (i, n) -> n.path(key).takeIf { it.isTextual }?.asText()?.let { it to Entry(i, n) } }
            .toMap()
    }

    private fun breaking(code: String, message: String, path: String) = Finding(Severity.WARN, FindingSource.BREAKING, code, message, path)
}
