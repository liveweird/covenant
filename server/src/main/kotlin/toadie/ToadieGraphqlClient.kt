package ch.nokillswit.toadie

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Read-only, fixed GraphQL operations against an ADMIN-curated endpoint. No unmapped properties,
 * credentials, arbitrary query text, or upstream error messages escape into the local cache.
 * A refresh is all-or-nothing. Remote numbered pages are observations, not a transaction snapshot.
 */
class HttpToadieGraphqlClient(
    private val timeoutMs: Long = 30_000,
    private val pageSize: Int = 100,
    private val maxEntities: Int = 5_000,
    private val maxResponseBytes: Int = 4 * 1024 * 1024,
) : ToadieGraphqlClient, AutoCloseable {
    private val mapper = ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(262_144).maxNumberLength(20).build(),
    ).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val client = HttpClient(Java) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = minOf(timeoutMs, 5_000)
        }
    }

    override fun close() = client.close()

    override suspend fun fetch(config: ToadieFetchConfig): ToadieFetchResult = try {
        withTimeoutOrNull(timeoutMs) {
            val budget = ReadBudget()
            config.knownRevision?.let { known ->
                if (revisionProbe(config, budget) == known) {
                    return@withTimeoutOrNull ToadieUnchanged(known, System.currentTimeMillis())
                }
            }
            readConsistentSnapshot(config, budget, restartAvailable = true)
        } ?: fail("TIMEOUT")
    } catch (error: CancellationException) {
        throw error
    } catch (error: ToadieFetchException) {
        throw error
    } catch (_: Exception) {
        throw ToadieFetchException("UPSTREAM_UNAVAILABLE")
    }

    private suspend fun readConsistentSnapshot(
        config: ToadieFetchConfig,
        budget: ReadBudget,
        restartAvailable: Boolean,
    ): ToadieSnapshot = try {
        readSnapshot(config, budget)
    } catch (_: RevisionChanged) {
        if (!restartAvailable) fail("SOURCE_CHANGED")
        readConsistentSnapshot(config, budget, restartAvailable = false)
    }

    private suspend fun readSnapshot(config: ToadieFetchConfig, budget: ReadBudget): ToadieSnapshot {
        val revision = RevisionTracker()
        val blueprintFields = blueprintFields(config)
        val blueprints = pages(config, "blueprints", blueprintFields, null, budget, revision)
        val mapping = config.mapping
        fun blueprint(identifier: String): JsonNode = blueprints.singleOrNull {
            text(it, "identifier").equals(identifier, ignoreCase = true)
        } ?: fail("MAPPING_INVALID")
        val service = blueprint(mapping.serviceBlueprint)
        val api = blueprint(mapping.apiBlueprint)
        val apiName = text(api, "identifier")
        val serviceName = text(service, "identifier")
        val definitions = service.path("relations")
        val provides = relationTarget(definitions, mapping.providesRelation, many = true)
        val consumes = relationTarget(definitions, mapping.consumesRelation, many = true)
        if (provides != apiName || consumes != apiName) fail("MAPPING_INVALID")
        val systemName = relationTarget(definitions, mapping.systemRelation, many = false)
        if (text(blueprint(systemName), "identifier") != systemName) fail("MAPPING_INVALID")
        blueprint("_team")
        val registry = config.registryMapping?.let {
            ToadieRegistryProjection(
                it, blueprints, systemName,
                setOf(apiName, serviceName, systemName, "_team") + listOfNotNull(config.adoptionMapping?.blueprint),
            )
        }
        val adoption = config.adoptionMapping?.let { adoptionMapping ->
            val matches = blueprints.filter {
                text(it, "identifier").equals(adoptionMapping.blueprint, ignoreCase = true)
            }
            if (matches.size > 1) fail("MAPPING_INVALID")
            matches.singleOrNull()?.let {
                adoptionBlueprint(it, adoptionMapping, serviceName, apiName, systemName, registry?.domainName, blueprints)
            }
        }
        val adoptionAvailability = when {
            config.adoptionMapping == null -> ToadieAdoptionAvailability.NOT_CONFIGURED
            adoption == null -> ToadieAdoptionAvailability.BLUEPRINT_MISSING
            else -> ToadieAdoptionAvailability.AVAILABLE
        }
        val relationKeys = setOf(mapping.providesRelation, mapping.consumesRelation, mapping.systemRelation)
        val names = linkedSetOf(apiName, serviceName, systemName, "_team").apply {
            registry?.let { add(it.domainName) }
            adoption?.let { projection ->
                add(projection.blueprint)
                projection.environmentBlueprint?.let(::add)
            }
        }
        val entities = names.flatMap { name ->
            val keys = (if (name == serviceName) relationKeys else emptySet()) + registry?.relationKeys(name).orEmpty() +
                adoption?.relationKeys(name).orEmpty()
            val readsProperties = registry?.readsDescription(name) == true || adoption?.readsProperties(name) == true
            val fields = ENTITY_FIELDS + if (readsProperties) " properties" else ""
            pages(config, "entities", fields, name, budget, revision).map {
                val entity = parseEntity(it, name, keys)
                val registryEntity = registry?.project(it, entity) ?: entity
                adoption?.project(it, registryEntity) ?: registryEntity
            }
        }
        if (entities.size > maxEntities || entities.map { it.id }.toSet().size != entities.size) fail("LIMIT_EXCEEDED")
        revision.observe(revisionProbe(config, budget))
        validateReferences(entities, serviceName, apiName, systemName, mapping)
        registry?.validateReferences(entities)
        adoption?.validateReferences(entities)
        return ToadieSnapshot(
            entities, systemName, System.currentTimeMillis(), checkNotNull(revision.value),
            adoptionAvailability, adoption?.environmentBlueprint,
        )
    }

    private fun adoptionBlueprint(
        blueprint: JsonNode,
        mapping: ToadieAdoptionMapping,
        serviceName: String,
        apiName: String,
        systemName: String,
        registryDomainName: String?,
        blueprints: List<JsonNode>,
    ): AdoptionProjection {
        val blueprintName = text(blueprint, "identifier")
        val architectureRoles = setOf(serviceName, apiName, systemName, "_team") + listOfNotNull(registryDomainName)
        if (architectureRoles.any { it.equals(blueprintName, ignoreCase = true) }) fail("MAPPING_INVALID")
        val relationNames = listOfNotNull(
            mapping.consumerRelation, mapping.targetRelation, mapping.environmentRelation,
        )
        if (relationNames.distinct().size != relationNames.size) fail("MAPPING_INVALID")
        val relations = blueprint.path("relations")
        val consumerTarget = relationTarget(relations, mapping.consumerRelation, many = false)
        val targetTarget = relationTarget(relations, mapping.targetRelation, many = false)
        if (consumerTarget != serviceName || targetTarget != apiName) fail("MAPPING_INVALID")
        val environmentBlueprint = mapping.environmentRelation?.let { relation ->
            relationTarget(relations, relation, many = false).let { target ->
                val matches = blueprints.filter { text(it, "identifier").equals(target, ignoreCase = true) }
                if (matches.size != 1) fail("MAPPING_INVALID")
                val canonicalTarget = text(matches.single(), "identifier")
                val reservedEnvironmentRoles = architectureRoles + blueprintName
                if (reservedEnvironmentRoles.any { it.equals(canonicalTarget, ignoreCase = true) }) fail("MAPPING_INVALID")
                canonicalTarget
            }
        }
        val properties = blueprint.path("schema").path("properties")
        if (!properties.isObject) fail("MAPPING_INVALID")
        mapping.propertyNames().forEach { property ->
            val definition = properties.path(property)
            if (!definition.isObject || definition.path("type").textValue() != "string") fail("MAPPING_INVALID")
        }
        return AdoptionProjection(mapping, blueprintName, serviceName, apiName, environmentBlueprint)
    }

    private inner class AdoptionProjection(
        private val mapping: ToadieAdoptionMapping,
        val blueprint: String,
        private val serviceBlueprint: String,
        private val targetBlueprint: String,
        val environmentBlueprint: String?,
    ) {
        fun relationKeys(name: String): Set<String> = if (name == blueprint) {
            setOfNotNull(mapping.consumerRelation, mapping.targetRelation, mapping.environmentRelation)
        } else emptySet()

        fun readsProperties(name: String) = name == blueprint

        fun project(node: JsonNode, entity: ToadieEntitySnapshot): ToadieEntitySnapshot {
            if (entity.blueprint != blueprint) return entity
            val properties = node.path("properties")
            if (!properties.isObject) fail("INVALID_RESPONSE")
            val selected = mapping.propertyNames().associateWith { property -> scalar(properties.path(property)) }
            mapping.verifiedAtProperty?.let { selected[it]?.let(::parseVerifiedAt) }
            return entity.copy(scalarProperties = selected)
        }

        fun validateReferences(entities: List<ToadieEntitySnapshot>) {
            val identifiers = entities.groupBy { it.blueprint }.mapValues { (_, rows) -> rows.map { it.identifier }.toSet() }
            entities.filter { it.blueprint == blueprint }.forEach { adoption ->
                val consumers = adoption.relations[mapping.consumerRelation].orEmpty()
                val targets = adoption.relations[mapping.targetRelation].orEmpty()
                val environments = mapping.environmentRelation?.let { adoption.relations[it].orEmpty() }.orEmpty()
                val invalidCardinality = consumers.size != 1 || targets.size != 1 || environments.size > 1
                if (invalidCardinality) fail("INVALID_RESPONSE")
                val danglingConsumer = consumers.single() !in identifiers[serviceBlueprint].orEmpty()
                val danglingTarget = targets.single() !in identifiers[targetBlueprint].orEmpty()
                val danglingEnvironment = environments.any { it !in identifiers[environmentBlueprint].orEmpty() }
                if (danglingConsumer || danglingTarget || danglingEnvironment) fail("INVALID_RESPONSE")
            }
        }
    }

    private fun validateReferences(
        entities: List<ToadieEntitySnapshot>,
        serviceName: String,
        apiName: String,
        systemName: String,
        mapping: ToadieMapping,
    ) {
        val identifiers = entities.groupBy { it.blueprint }.mapValues { (_, rows) -> rows.map { it.identifier }.toSet() }
        if (identifiers.values.sumOf { it.size } != entities.size) fail("INVALID_RESPONSE")
        entities.filter { it.blueprint == serviceName }.forEach { service ->
            val apiTargets = service.relations[mapping.providesRelation].orEmpty() +
                service.relations[mapping.consumesRelation].orEmpty()
            if (apiTargets.any { it !in identifiers[apiName].orEmpty() } ||
                service.relations[mapping.systemRelation].orEmpty().any { it !in identifiers[systemName].orEmpty() } ||
                service.teamIdentifiers.any { it !in identifiers["_team"].orEmpty() }
            ) fail("INVALID_RESPONSE")
            if (service.relations[mapping.systemRelation].orEmpty().size > 1) fail("INVALID_RESPONSE")
        }
    }

    private fun relationTarget(definitions: JsonNode, key: String, many: Boolean): String {
        val definition = definitions.path(key)
        if (!definition.isObject || !definition.path("many").isBoolean || definition.path("many").booleanValue() != many) {
            fail("MAPPING_INVALID")
        }
        return definition.path("target").takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
            ?: fail("MAPPING_INVALID")
    }

    private fun parseEntity(node: JsonNode, blueprint: String, relationKeys: Set<String>): ToadieEntitySnapshot {
        if (text(node, "blueprint") != blueprint || !node.path("relations").isObject) fail("INVALID_RESPONSE")
        val updatedAt = node.path("updatedAt")
        if (!updatedAt.isIntegralNumber || !updatedAt.canConvertToLong() || updatedAt.longValue() < 0) fail("INVALID_RESPONSE")
        return ToadieEntitySnapshot(
            id(node), blueprint, text(node, "identifier"), text(node, "title"), strings(node.path("team")),
            relationKeys.associateWith { strings(node.path("relations").path(it)) }, updatedAt.longValue(),
        )
    }

    private fun strings(node: JsonNode): List<String> {
        if (node.isMissingNode || node.isNull) return emptyList()
        val values = if (node.isTextual) listOf(node) else if (node.isArray) node.toList() else fail("INVALID_RESPONSE")
        if (values.size > maxEntities) fail("LIMIT_EXCEEDED")
        return values.map {
            if (!it.isTextual || it.textValue().isBlank() || it.textValue().length > 200) fail("INVALID_RESPONSE")
            it.textValue()
        }.also { if (it.distinct().size != it.size) fail("INVALID_RESPONSE") }
    }

    private fun scalar(node: JsonNode): String? {
        if (node.isMissingNode || node.isNull) return null
        if (!node.isTextual || node.textValue().length > MAX_ADOPTION_SCALAR_LENGTH) fail("INVALID_RESPONSE")
        return node.textValue()
    }

    private suspend fun pages(
        config: ToadieFetchConfig,
        field: String,
        selection: String,
        blueprint: String?,
        budget: ReadBudget,
        revision: RevisionTracker,
    ): List<JsonNode> {
        val rows = mutableListOf<JsonNode>()
        var expectedTotal: Int? = null
        var page = 1
        var previousId = 0u
        do {
            val result = request(
                config, field, "items { $selection } page pageSize total revision", blueprint, page, pageSize, budget,
            )
            val total = result.path("total")
            if (!total.isIntegralNumber || !total.canConvertToInt() || total.intValue() !in 0..maxEntities) fail("LIMIT_EXCEEDED")
            if (result.path("page").asInt(-1) != page || result.path("pageSize").asInt(-1) != pageSize) fail("INVALID_RESPONSE")
            val items = result.path("items")
            if (!items.isArray) fail("INVALID_RESPONSE")
            budget.entities += items.size()
            if (budget.entities > maxEntities) fail("LIMIT_EXCEEDED")
            revision.observe(revision(result))
            if (expectedTotal != null && total.intValue() != expectedTotal) fail("SOURCE_CHANGED")
            expectedTotal = total.intValue()
            val expectedSize = minOf(pageSize, expectedTotal - rows.size)
            if (items.size() != expectedSize) fail("INVALID_RESPONSE")
            items.forEach {
                val nextId = id(it).toUInt()
                if (nextId <= previousId) fail("SOURCE_CHANGED")
                previousId = nextId
                rows.add(it)
            }
            page++
        } while (rows.size < expectedTotal)
        return rows
    }

    private suspend fun revisionProbe(config: ToadieFetchConfig, budget: ReadBudget): Long = revision(
        request(config, "blueprints", "revision", null, 1, 1, budget),
    )

    private fun blueprintFields(config: ToadieFetchConfig): String =
        BLUEPRINT_FIELDS + if (config.registryMapping == null && config.adoptionMapping == null) "" else " schema"

    private suspend fun request(
        config: ToadieFetchConfig,
        field: String,
        selection: String,
        blueprint: String?,
        page: Int,
        requestedPageSize: Int,
        budget: ReadBudget,
    ): JsonNode {
        if (++budget.requests > 64) fail("LIMIT_EXCEEDED")
        val variableDefinition = if (blueprint == null) "" else ", \$blueprint: String!"
        val argument = if (blueprint == null) "" else ", blueprint: \$blueprint"
        val query = "query CovenantUsage(\$page: Int!, \$pageSize: Int!$variableDefinition) { " +
            "$field(page: \$page, pageSize: \$pageSize$argument) { $selection } }"
        val variables = mapOf("page" to page, "pageSize" to requestedPageSize) +
            (blueprint?.let { mapOf("blueprint" to it) } ?: emptyMap())
        val body = mapper.writeValueAsString(mapOf("query" to query, "operationName" to "CovenantUsage", "variables" to variables))
        return client.preparePost("${config.baseUrl.trimEnd('/')}/integration/graphql") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            when (response.status.value) {
                200 -> Unit
                401, 403 -> fail("AUTHENTICATION_FAILED")
                429 -> fail("RATE_LIMITED")
                else -> fail("UPSTREAM_UNAVAILABLE")
            }
            val stream = ByteArrayOutputStream()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                budget.bytes += count
                if (stream.size() + count > maxResponseBytes || budget.bytes > 16 * 1024 * 1024) fail("LIMIT_EXCEEDED")
                stream.write(buffer, 0, count)
            }
            parseResponse(stream.toByteArray(), field)
        }
    }

    private fun parseResponse(bytes: ByteArray, field: String): JsonNode {
        val root = try { mapper.readTree(bytes) } catch (_: Exception) { fail("INVALID_RESPONSE") }
        if (root == null || !root.isObject) fail("INVALID_RESPONSE")
        if (root.has("errors") && (!root.path("errors").isArray || !root.path("errors").isEmpty)) fail("GRAPHQL_ERROR")
        return root.path("data").path(field).takeIf { it.isObject } ?: fail("INVALID_RESPONSE")
    }

    private fun text(node: JsonNode, field: String): String = node.path(field).let {
        if (!it.isTextual || it.textValue().isBlank() || it.textValue().length > 200) fail("INVALID_RESPONSE")
        it.textValue()
    }

    private fun id(node: JsonNode): String = text(node, "id").also {
        if (it.toUIntOrNull() == null || it.toUInt() == 0u || it != it.toUInt().toString()) fail("INVALID_RESPONSE")
    }

    private fun revision(node: JsonNode): Long {
        val raw = node.path("revision").takeIf { it.isTextual }?.textValue() ?: fail("INVALID_RESPONSE")
        val value = raw.toLongOrNull()?.takeIf { it >= 0 } ?: fail("INVALID_RESPONSE")
        if (raw != value.toString()) fail("INVALID_RESPONSE")
        return value
    }

    private class ReadBudget(var requests: Int = 0, var entities: Int = 0, var bytes: Int = 0)
    private class RevisionChanged : RuntimeException()
    private class RevisionTracker {
        var value: Long? = null
            private set

        fun observe(next: Long) {
            if (value != null && value != next) throw RevisionChanged()
            value = next
        }
    }

    private companion object {
        const val BLUEPRINT_FIELDS = "id identifier relations"
        const val ENTITY_FIELDS = "id blueprint identifier title team relations updatedAt"
        const val MAX_ADOPTION_SCALAR_LENGTH = 2000
        fun fail(code: String): Nothing = throw ToadieFetchException(code)
    }
}

private fun ToadieAdoptionMapping.propertyNames(): Set<String> = setOfNotNull(
    valueProperty, statusProperty, declaredByProperty, verifiedAtProperty, notesProperty,
)

internal fun parseVerifiedAt(value: String): Long {
    if (!value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*"))) throw ToadieFetchException("INVALID_RESPONSE")
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrElse { throw ToadieFetchException("INVALID_RESPONSE") }
}
