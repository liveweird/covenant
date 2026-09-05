package ch.nokillswit.contracts.tryit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.AsyncApiValidator
import com.fasterxml.jackson.databind.JsonNode

/**
 * What a document offers to try, read off the parsed tree (pure, total over any shape — a stored
 * document may be schema-invalid). OpenAPI: every declared operation with its parameters
 * (path-level merged in, `$ref`s to components followed), request body media types, response
 * keys and the header names the security schemes expect. AsyncAPI: every channel with its
 * address, verbs and messages (the payload pointer comes from the validator's payload walk, so the
 * try validates exactly what the checks validated). ODCS: every dataset with its properties.
 */
object TryCatalog {
    // TRACE is never offered: it echoes the request (credentials included) and no contract worth trying declares it.
    private val METHODS = listOf("get", "put", "post", "delete", "options", "head", "patch")

    fun build(type: ContractType, root: JsonNode): TryCatalogResponse = when (type) {
        ContractType.OPENAPI -> TryCatalogResponse(type, http = httpOperations(root))
        ContractType.ASYNCAPI -> TryCatalogResponse(type, kafka = kafkaChannels(root))
        ContractType.ODCS -> TryCatalogResponse(type, sql = sqlDatasets(root))
    }

    // ---- OpenAPI ------------------------------------------------------------------------------

    private fun httpOperations(root: JsonNode): List<HttpOperationSummary> {
        val securityHeaders = securityHeaders(root)
        return fields(root.path("paths")).flatMap { (path, item) ->
            val shared = parameters(root, item.path("parameters"))
            METHODS.mapNotNull { method ->
                val op = item.path(method).takeIf { it.isObject } ?: return@mapNotNull null
                val own = parameters(root, op.path("parameters"))
                val merged = (own + shared.filter { s -> own.none { it.name == s.name && it.location == s.location } })
                HttpOperationSummary(
                    operationId = op.path("operationId").textOrNull(),
                    method = method.uppercase(),
                    path = path,
                    summary = op.path("summary").textOrNull() ?: item.path("summary").textOrNull(),
                    parameters = merged,
                    requestBody = requestBody(root, op.path("requestBody")),
                    responses = fields(op.path("responses")).map { it.first },
                    securityHeaders = securityHeaders,
                )
            }
        }
    }

    private fun parameters(root: JsonNode, node: JsonNode): List<HttpParameterSummary> =
        node.takeIf { it.isArray }?.mapNotNull { raw ->
            val p = deref(root, raw)
            val name = p.path("name").textOrNull() ?: return@mapNotNull null
            val location = p.path("in").textOrNull() ?: return@mapNotNull null
            val required = p.path("required").asBoolean(false) || location == "path"
            HttpParameterSummary(name, location, required, p.path("schema").path("type").textOrNull())
        }.orEmpty()

    private fun requestBody(root: JsonNode, node: JsonNode): HttpRequestBodySummary? {
        if (node.isMissingNode || node.isNull) return null
        val body = deref(root, node)
        return HttpRequestBodySummary(body.path("required").asBoolean(false), fields(body.path("content")).map { it.first })
    }

    private fun securityHeaders(root: JsonNode): List<String> =
        fields(root.path("components").path("securitySchemes")).mapNotNull { (_, scheme) ->
            when (scheme.path("type").textOrNull()) {
                "http", "oauth2", "openIdConnect" -> "Authorization"
                "apiKey" -> scheme.path("name").textOrNull()?.takeIf { scheme.path("in").textOrNull() == "header" }
                else -> null
            }
        }.distinct()

    // ---- AsyncAPI -----------------------------------------------------------------------------

    private fun kafkaChannels(root: JsonNode): List<KafkaChannelSummary> {
        val v3 = root.path("asyncapi").asText().startsWith("3.")
        val payloads = AsyncApiValidator.payloads(root, v3)
        fun payloadPointerFor(messagePointer: String) = payloads.firstOrNull { it.pointer.startsWith("$messagePointer/payload") }?.pointer
        return fields(root.path("channels")).map { (key, channel) ->
            val base = "/channels/${esc(key)}"
            if (v3) {
                val actions = fields(root.path("operations")).mapNotNull { (_, op) ->
                    op.path("action").textOrNull()?.takeIf { op.path("channel").path("\$ref").textOrNull() == "#$base" }
                }.distinct()
                val messages = fields(channel.path("messages")).map { (name, msg) ->
                    val ref = msg.path("\$ref").textOrNull()
                    val messagePointer = if (ref != null && ref.startsWith("#")) ref.drop(1) else "$base/messages/${esc(name)}"
                    val resolved = deref(root, msg)
                    message(name, resolved, payloadPointerFor(messagePointer))
                }
                KafkaChannelSummary(key, channel.path("address").textOrNull() ?: key, actions, messages)
            } else {
                val actions = listOf("publish", "subscribe").filter { channel.path(it).isObject }
                val messages = actions.flatMap { verb ->
                    val msg = channel.path(verb).path("message")
                    val alternatives = when {
                        msg.has("oneOf") -> msg["oneOf"].mapIndexed { i, m -> "$base/$verb/message/oneOf/$i" to m }
                        else -> listOf("$base/$verb/message" to msg)
                    }
                    alternatives.mapIndexed { i, (pointer, m) ->
                        val resolved = deref(root, m)
                        val ref = m.path("\$ref").textOrNull()
                        val messagePointer = if (ref != null && ref.startsWith("#")) ref.drop(1) else pointer
                        val name = resolved.path("name").textOrNull() ?: resolved.path("title").textOrNull()
                            ?: ref?.substringAfterLast('/') ?: "$verb-$i"
                        message(name, resolved, payloadPointerFor(messagePointer))
                    }
                }.distinctBy { it.name }
                KafkaChannelSummary(key, key, actions, messages)
            }
        }
    }

    private fun message(name: String, resolved: JsonNode, payloadPointer: String?) =
        KafkaMessageSummary(name, resolved.path("contentType").textOrNull(), schemaFormat(resolved), payloadPointer)

    private fun schemaFormat(message: JsonNode): String? {
        val payload = message.path("payload")
        return payload.path("schemaFormat").textOrNull() ?: message.path("schemaFormat").textOrNull()
    }

    // ---- ODCS ---------------------------------------------------------------------------------

    private fun sqlDatasets(root: JsonNode): List<SqlDatasetSummary> =
        root.path("schema").takeIf { it.isArray }?.mapNotNull { dataset ->
            val name = dataset.path("name").textOrNull() ?: return@mapNotNull null
            SqlDatasetSummary(
                name = name,
                physicalName = dataset.path("physicalName").textOrNull(),
                physicalType = dataset.path("physicalType").textOrNull(),
                properties = dataset.path("properties").takeIf { it.isArray }?.mapNotNull { p ->
                    val n = p.path("name").textOrNull() ?: return@mapNotNull null
                    SqlPropertySummary(
                        name = n,
                        physicalName = p.path("physicalName").textOrNull(),
                        logicalType = p.path("logicalType").textOrNull(),
                        physicalType = p.path("physicalType").textOrNull(),
                        required = p.path("required").asBoolean(false),
                    )
                }.orEmpty(),
            )
        }.orEmpty()

    // ---- helpers ------------------------------------------------------------------------------

    /** Follows an internal `$ref` one hop (parameters, request bodies, messages); anything else is returned as is. */
    internal fun deref(root: JsonNode, node: JsonNode): JsonNode {
        val ref = node.path("\$ref").textOrNull() ?: return node
        if (!ref.startsWith("#/")) return node
        val target = root.at(ref.drop(1))
        return if (target.isMissingNode) node else target
    }

    internal fun fields(node: JsonNode): List<Pair<String, JsonNode>> =
        if (node.isObject) node.fields().asSequence().map { it.key to it.value }.toList() else emptyList()

    private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

    internal fun esc(segment: String) = segment.replace("~", "~0").replace("/", "~1")
}
