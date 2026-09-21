package ch.nokillswit.toadie

import com.fasterxml.jackson.databind.JsonNode

/** Selects only ADMIN-mapped Port metadata; arbitrary properties never enter the snapshot. */
internal class ToadieRegistryProjection(
    private val mapping: ToadieRegistryMapping,
    private val blueprints: List<JsonNode>,
    private val systemName: String,
    reservedNames: Set<String>,
) {
    private fun blueprint(name: String): JsonNode = blueprints.singleOrNull {
        it.path("identifier").asText().equals(name, ignoreCase = true)
    } ?: invalidMapping()

    val domainName: String = blueprint(mapping.domainBlueprint).path("identifier").asText()
    private val relations = mapOf(
        domainName to listOfNotNull(mapping.domainParentRelation).toSet(),
        systemName to listOfNotNull(mapping.systemDomainRelation).toSet(),
        "_team" to setOf("parent"),
    )
    private val descriptions = mapOf(
        domainName to mapping.domainDescriptionProperty,
        systemName to mapping.systemDescriptionProperty,
        "_team" to mapping.teamDescriptionProperty,
    )

    init {
        if (!mapping.flattenDomains || reservedNames.any { it.equals(domainName, ignoreCase = true) } ||
            systemName == "_team"
        ) invalidMapping()
        validateRelation(blueprint(domainName), mapping.domainParentRelation, domainName)
        validateRelation(blueprint(systemName), mapping.systemDomainRelation, domainName)
        validateRelation(blueprint("_team"), "parent", "_team")
        descriptions.forEach { (name, property) ->
            if (property != null && blueprint(name).path("schema").path("properties").path(property)
                    .path("type").asText() != "string"
            ) invalidMapping()
        }
    }

    fun relationKeys(name: String): Set<String> = relations[name].orEmpty()
    fun readsDescription(name: String): Boolean = descriptions[name] != null

    fun project(node: JsonNode, entity: ToadieEntitySnapshot): ToadieEntitySnapshot {
        val property = descriptions[entity.blueprint] ?: return entity
        val properties = node.path("properties")
        if (!properties.isObject) return entity.copy(registryErrorCode = "INVALID_DESCRIPTION")
        val value = properties.path(property)
        if (value.isMissingNode || value.isNull) return entity
        return if (!value.isTextual || value.textValue().length > 2_000) {
            entity.copy(registryErrorCode = "INVALID_DESCRIPTION")
        } else entity.copy(registryDescription = value.textValue())
    }

    fun validateReferences(entities: List<ToadieEntitySnapshot>) {
        val identifiers = entities.groupBy { it.blueprint }.mapValues { (_, rows) -> rows.map { it.identifier }.toSet() }
        entities.forEach { entity ->
            relationKeys(entity.blueprint).forEach { key ->
                val targets = entity.relations[key].orEmpty()
                val targetBlueprint = if (entity.blueprint == "_team") "_team" else domainName
                if (targets.size > 1 || targets.any { it !in identifiers[targetBlueprint].orEmpty() }) {
                    throw ToadieFetchException("INVALID_RESPONSE")
                }
            }
        }
    }

    private fun validateRelation(blueprint: JsonNode, key: String?, target: String) {
        if (key == null) return
        val definition = blueprint.path("relations").path(key)
        if (!definition.isObject || !definition.path("many").isBoolean || definition.path("many").booleanValue() ||
            definition.path("target").asText() != target
        ) invalidMapping()
    }

    private fun invalidMapping(): Nothing = throw ToadieFetchException("MAPPING_INVALID")
}
