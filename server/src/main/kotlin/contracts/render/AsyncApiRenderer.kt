package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * AsyncAPI 2.6 / 3.x → [AsyncApiModel], 2.x unified onto the 3.x vocabulary: a 2.x channel's key
 * is its address, `publish` → an operation with `action: receive` (`legacyAction: publish`),
 * `subscribe` → `send`. Every message payload is rendered ONCE: `components.messages` by key,
 * inline channel/operation messages keyed `<channel>/<key>`; channels and operations point at
 * those keys. Payloads: Avro formats through [AvroSchemaMapper], JSON-Schema-like through
 * [SchemaWalker], anything else as raw text.
 */
class AsyncApiRenderer(private val root: JsonNode, private val budget: RenderBudget) {
    private val v3 = (Nodes.text(root, "asyncapi") ?: "").startsWith("3.")
    private val walker = SchemaWalker(root, budget, SchemaDialect.JSON_SCHEMA)
    private val messages = linkedMapOf<String, MessageView>()

    fun render(): AsyncApiModel {
        Nodes.fields(root.path("components").path("messages")).forEach { (key, m) ->
            messages[key] = message(key, m, "/components/messages/${JsonPointers.escape(key)}", inline = false)
        }
        val channels = Nodes.fields(root.path("channels")).map { (key, c) -> channel(key, c) }
        val operations = if (v3) operationsV3() else operationsV2()
        return AsyncApiModel(
            info = Shared.info(root.path("info")),
            tags = Shared.tags(firstPresent(root.path("info").path("tags"), root.path("tags"))),
            defaultContentType = Nodes.text(root, "defaultContentType"),
            servers = Nodes.fields(root.path("servers")).map { (name, s) -> server(name, Shared.deref(root, s)) },
            channels = channels,
            operations = operations,
            messages = messages.values.toList(),
            schemas = Nodes.fields(root.path("components").path("schemas")).map { (name, s) ->
                val pointer = "/components/schemas/${JsonPointers.escape(name)}"
                NamedSchemaView(name, pointer, payloadNode(s, pointer, null))
            },
            securitySchemes = Shared.securitySchemes(root, root.path("components").path("securitySchemes"), "/components/securitySchemes"),
            externalDocs = Shared.externalDocs(firstPresent(root.path("info").path("externalDocs"), root.path("externalDocs"))),
        )
    }

    private fun server(name: String, s: JsonNode): AsyncServerView {
        val url = Nodes.text(s, "url")
        return AsyncServerView(
            name = name,
            host = Nodes.text(s, "host") ?: url?.substringAfter("://")?.substringBefore('/'),
            pathname = Nodes.text(s, "pathname") ?: url?.let { pathOf(it) },
            protocol = Nodes.text(s, "protocol"),
            protocolVersion = Nodes.text(s, "protocolVersion"),
            description = Nodes.text(s, "description"),
            security = securityNames(s.path("security")),
            tags = Shared.tagNames(s.path("tags")),
            bindings = Nodes.fields(s.path("bindings")).map { it.first },
        )
    }

    private fun channel(key: String, raw: JsonNode): ChannelView {
        val c = Shared.deref(root, raw)
        val pointer = Shared.pointerOf(raw, "/channels/${JsonPointers.escape(key)}")
        val refs = if (v3) {
            Nodes.fields(c.path("messages")).map { (name, m) -> messageRef(name, m, "$pointer/messages/${JsonPointers.escape(name)}", key) }
        } else {
            listOf("publish", "subscribe").flatMap { verb -> operationMessagesV2(c, key, pointer, verb) }
        }
        return ChannelView(
            pointer = pointer,
            name = key,
            address = if (v3) Nodes.text(c, "address") else key,
            title = Nodes.text(c, "title"),
            summary = Nodes.text(c, "summary"),
            description = Nodes.text(c, "description"),
            servers = if (v3) refNames(c.path("servers")) else Nodes.strings(c, "servers"),
            parameters = Nodes.fields(c.path("parameters")).map { (name, p) ->
                parameter(name, Shared.deref(root, p), "$pointer/parameters/${JsonPointers.escape(name)}")
            },
            messages = refs,
            bindings = Nodes.fields(c.path("bindings")).map { it.first },
        )
    }

    private fun parameter(name: String, p: JsonNode, pointer: String): ChannelParameterView = ChannelParameterView(
        name = name,
        description = Nodes.text(p, "description"),
        location = Nodes.text(p, "location"),
        enumValues = Nodes.strings(p, "enum"),
        default = Nodes.text(p, "default"),
        schema = p.path("schema").takeIf { it.isObject }?.let { walker.node(it, "$pointer/schema") },
    )

    /** A message under a channel/operation: a `$ref` to a component message points at its key; an inline one is rendered here. */
    private fun messageRef(name: String, raw: JsonNode, pointer: String, channelKey: String): MessageRefView {
        val ref = Nodes.text(raw, "\$ref")
        if (ref != null) {
            val target = componentMessageKey(ref)
            if (target != null && target in messages) return MessageRefView(name, target)
            val resolved = JsonPointers.resolve(root, ref) ?: return MessageRefView(name, null)
            val key = "$channelKey/$name"
            messages.putIfAbsent(key, message(key, resolved, JsonPointers.pointerOf(ref) ?: pointer, inline = true))
            return MessageRefView(name, key)
        }
        val key = "$channelKey/$name"
        messages.putIfAbsent(key, message(key, raw, pointer, inline = true))
        return MessageRefView(name, key)
    }

    private fun operationMessagesV2(channel: JsonNode, channelKey: String, pointer: String, verb: String): List<MessageRefView> {
        val msg = channel.path(verb).path("message")
        return when {
            msg.isMissingNode -> emptyList()
            msg.has("oneOf") -> msg["oneOf"].mapIndexed { i, m ->
                messageRef(nameOf(m, "$verb-$i"), m, "$pointer/$verb/message/oneOf/$i", channelKey)
            }
            else -> listOf(messageRef(nameOf(msg, verb), msg, "$pointer/$verb/message", channelKey))
        }
    }

    private fun nameOf(m: JsonNode, fallback: String): String {
        val ref = Nodes.text(m, "\$ref")
        if (ref != null) return JsonPointers.name(ref)
        return Nodes.text(m, "name") ?: Nodes.text(m, "messageId") ?: fallback
    }

    private fun operationsV3(): List<AsyncOperationView> = Nodes.fields(root.path("operations")).map { (name, raw) ->
        val op = Shared.deref(root, raw)
        val pointer = Shared.pointerOf(raw, "/operations/${JsonPointers.escape(name)}")
        val channelKey = Nodes.text(op.path("channel"), "\$ref")?.let { JsonPointers.name(it) }
        AsyncOperationView(
            pointer = pointer,
            name = name,
            action = Nodes.text(op, "action") ?: "",
            legacyAction = null,
            channel = channelKey,
            messages = op.path("messages").takeIf { it.isArray }?.mapNotNull { m ->
                Nodes.text(m, "\$ref")?.let { ref -> MessageRefView(JsonPointers.name(ref), messageKeyFor(ref)) }
            }.orEmpty(),
            reply = Nodes.text(op.path("reply").path("channel"), "\$ref")?.let { JsonPointers.name(it) },
            summary = Nodes.text(op, "summary"),
            description = Nodes.text(op, "description"),
            security = securityNames(op.path("security")),
            tags = Shared.tagNames(op.path("tags")),
            bindings = Nodes.fields(op.path("bindings")).map { it.first },
        )
    }

    /** `#/channels/<c>/messages/<m>` → the rendered key (`<m>` when it points at a component message, else `<c>/<m>`). */
    private fun messageKeyFor(ref: String): String? {
        val pointer = JsonPointers.pointerOf(ref) ?: return null
        val parts = pointer.split('/').drop(1).map { JsonPointers.unescape(it) }
        if (parts.size == 3 && parts[0] == "components" && parts[1] == "messages") return parts[2].takeIf { it in messages }
        if (parts.size == 4 && parts[0] == "channels" && parts[2] == "messages") {
            val channelMessage = root.at(pointer)
            val target = Nodes.text(channelMessage, "\$ref")?.let { componentMessageKey(it) }
            return if (target != null && target in messages) target else "${parts[1]}/${parts[3]}".takeIf { it in messages }
        }
        return null
    }

    private fun operationsV2(): List<AsyncOperationView> = Nodes.fields(root.path("channels")).flatMap { (key, c) ->
        listOf("publish" to "receive", "subscribe" to "send").mapNotNull { (verb, action) ->
            val op = c.path(verb).takeIf { it.isObject } ?: return@mapNotNull null
            val pointer = "/channels/${JsonPointers.escape(key)}/$verb"
            AsyncOperationView(
                pointer = pointer,
                name = Nodes.text(op, "operationId") ?: "$key $verb",
                action = action,
                legacyAction = verb,
                channel = key,
                messages = operationMessagesV2(c, key, "/channels/${JsonPointers.escape(key)}", verb),
                reply = null,
                summary = Nodes.text(op, "summary"),
                description = Nodes.text(op, "description"),
                security = op.path("security").takeIf { it.isArray }?.flatMap { req -> Nodes.fields(req).map { it.first } }.orEmpty(),
                tags = Shared.tagNames(op.path("tags")),
                bindings = Nodes.fields(op.path("bindings")).map { it.first },
            )
        }
    }

    private fun message(key: String, m: JsonNode, pointer: String, inline: Boolean): MessageView {
        val payload = m.path("payload")
        val (payloadNode, format, payloadPointer) = when {
            payload.isMissingNode || payload.isNull -> Triple(null, Nodes.text(m, "schemaFormat"), null)
            payload.isObject && payload.has("schemaFormat") && payload.has("schema") ->
                Triple(payload["schema"], Nodes.text(payload, "schemaFormat"), "$pointer/payload/schema")
            else -> Triple(payload, Nodes.text(m, "schemaFormat"), "$pointer/payload")
        }
        val headers = m.path("headers")
        return MessageView(
            key = key,
            pointer = pointer,
            name = Nodes.text(m, "name"),
            title = Nodes.text(m, "title"),
            summary = Nodes.text(m, "summary"),
            description = Nodes.text(m, "description"),
            contentType = Nodes.text(m, "contentType") ?: Nodes.text(root, "defaultContentType"),
            schemaFormat = format,
            headers = headers.takeIf { it.isObject }?.let { payloadNode(headersSchema(it), "$pointer/headers", null) },
            payload = payloadNode?.let { payloadNode(it, payloadPointer!!, format) },
            correlationId = m.path("correlationId").takeIf { it.isObject }?.let { Nodes.text(Shared.deref(root, it), "location") },
            examples = Nodes.objects(m, "examples").mapIndexed { i, ex ->
                val value = ex.path("payload").takeIf { !it.isMissingNode } ?: ex
                ExampleView(Nodes.text(ex, "name") ?: "example ${i + 1}", Nodes.text(ex, "summary"), null, Nodes.stringify(value))
            },
            tags = Shared.tagNames(m.path("tags")),
            bindings = Nodes.fields(m.path("bindings")).map { it.first },
            deprecated = Nodes.bool(m, "deprecated"),
            inline = inline,
        )
    }

    /** 3.x multi-format headers `{schemaFormat, schema}` → the schema; otherwise the node is the schema. */
    private fun headersSchema(h: JsonNode): JsonNode = if (h.has("schemaFormat") && h.has("schema")) h["schema"] else h

    private fun payloadNode(schema: JsonNode, pointer: String, format: String?): SchemaNode {
        val f = format?.lowercase()
        return when {
            f != null && f.contains("avro") -> AvroSchemaMapper(budget).node(Shared.deref(root, schema), pointer)
            f == null || JSON_LIKE.any { f.contains(it) } -> walker.node(schema, pointer)
            else -> SchemaWalker.empty(pointer).copy(raw = Nodes.pretty(schema), format = format)
        }
    }

    private fun refNames(node: JsonNode): List<String> =
        node.takeIf { it.isArray }?.mapNotNull { Nodes.text(it, "\$ref")?.let { r -> JsonPointers.name(r) } }.orEmpty()

    /** Security requirements as scheme names — 3.x `$ref`s to component schemes, 2.x `{name: scopes}` maps. */
    private fun securityNames(node: JsonNode): List<String> = node.takeIf { it.isArray }?.flatMap { req ->
        Nodes.text(req, "\$ref")?.let { listOf(JsonPointers.name(it)) } ?: Nodes.fields(req).map { it.first }
    }.orEmpty().distinct()

    private fun componentMessageKey(ref: String): String? =
        ref.takeIf { it.startsWith(COMPONENT_MESSAGES) }?.removePrefix(COMPONENT_MESSAGES)?.let { JsonPointers.unescape(it) }

    private fun firstPresent(a: JsonNode, b: JsonNode): JsonNode = if (a.isMissingNode) b else a

    /** A 2.x server `url`'s path part (`kafka://host:9092/prod` → `/prod`), when any. */
    private fun pathOf(url: String): String? =
        url.substringAfter("://", "").substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { "/$it" }

    companion object {
        private val JSON_LIKE = listOf("json", "asyncapi", "openapi")
        private const val COMPONENT_MESSAGES = "#/components/messages/"
    }
}
