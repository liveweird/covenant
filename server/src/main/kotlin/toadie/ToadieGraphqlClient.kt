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

/**
 * Read-only, fixed GraphQL operations against an ADMIN-curated endpoint. No result properties,
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

    override suspend fun fetch(config: ToadieFetchConfig): ToadieSnapshot = try {
        withTimeoutOrNull(timeoutMs) { readSnapshot(config) } ?: fail("TIMEOUT")
    } catch (error: CancellationException) {
        throw error
    } catch (error: ToadieFetchException) {
        throw error
    } catch (_: Exception) {
        throw ToadieFetchException("UPSTREAM_UNAVAILABLE")
    }

    private suspend fun readSnapshot(config: ToadieFetchConfig): ToadieSnapshot {
        val budget = ReadBudget()
        val blueprints = pages(config, "blueprints", BLUEPRINT_FIELDS, null, budget)
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
        val relationKeys = setOf(mapping.providesRelation, mapping.consumesRelation, mapping.systemRelation)
        val entities = linkedSetOf(apiName, serviceName, systemName, "_team").flatMap { name ->
            pages(config, "entities", ENTITY_FIELDS, name, budget).map {
                parseEntity(it, name, if (name == serviceName) relationKeys else emptySet())
            }
        }
        if (entities.size > maxEntities || entities.map { it.id }.toSet().size != entities.size) fail("LIMIT_EXCEEDED")
        validateReferences(entities, serviceName, apiName, systemName, mapping)
        return ToadieSnapshot(entities, systemName, System.currentTimeMillis())
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

    private suspend fun pages(
        config: ToadieFetchConfig,
        field: String,
        selection: String,
        blueprint: String?,
        budget: ReadBudget,
    ): List<JsonNode> {
        val rows = mutableListOf<JsonNode>()
        var expectedTotal: Int? = null
        var page = 1
        var previousId = 0u
        do {
            val result = request(config, field, selection, blueprint, page, budget)
            val total = result.path("total")
            if (!total.isIntegralNumber || !total.canConvertToInt() || total.intValue() !in 0..maxEntities) fail("LIMIT_EXCEEDED")
            if (expectedTotal != null && total.intValue() != expectedTotal) fail("SOURCE_CHANGED")
            expectedTotal = total.intValue()
            if (result.path("page").asInt(-1) != page || result.path("pageSize").asInt(-1) != pageSize) fail("INVALID_RESPONSE")
            val items = result.path("items")
            val expectedSize = minOf(pageSize, expectedTotal - rows.size)
            if (!items.isArray || items.size() != expectedSize) fail("INVALID_RESPONSE")
            items.forEach {
                val nextId = id(it).toUInt()
                if (nextId <= previousId) fail("SOURCE_CHANGED")
                previousId = nextId
                rows.add(it)
            }
            page++
        } while (rows.size < expectedTotal)
        budget.entities += rows.size
        if (budget.entities > maxEntities) fail("LIMIT_EXCEEDED")
        return rows
    }

    private suspend fun request(
        config: ToadieFetchConfig,
        field: String,
        selection: String,
        blueprint: String?,
        page: Int,
        budget: ReadBudget,
    ): JsonNode {
        if (++budget.requests > 64) fail("LIMIT_EXCEEDED")
        val variableDefinition = if (blueprint == null) "" else ", \$blueprint: String!"
        val argument = if (blueprint == null) "" else ", blueprint: \$blueprint"
        val query = "query CovenantUsage(\$page: Int!, \$pageSize: Int!$variableDefinition) { " +
            "$field(page: \$page, pageSize: \$pageSize$argument) { items { $selection } page pageSize total } }"
        val variables = mapOf("page" to page, "pageSize" to pageSize) +
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

    private class ReadBudget(var requests: Int = 0, var entities: Int = 0, var bytes: Int = 0)

    private companion object {
        const val BLUEPRINT_FIELDS = "id identifier relations"
        const val ENTITY_FIELDS = "id blueprint identifier title team relations updatedAt"
        fun fail(code: String): Nothing = throw ToadieFetchException(code)
    }
}
