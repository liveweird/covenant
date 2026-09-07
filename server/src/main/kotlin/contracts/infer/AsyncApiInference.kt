package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.tryit.TryCatalog
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Message payloads → AsyncAPI 3.0. One channel per batch (its topic becomes the `address`, its id
 * a camelCase of the topic split on `[.\-/_ ]`), one `send` operation per channel; messages are
 * declared INLINE inside the channel — the operation's own message `$ref` points INTO the channel,
 * never `components`. A CloudEvents-shaped payload (string `specversion`/`id`/`source`/`type`)
 * lifts its `data` sub-schema into `components.schemas`.
 */
object AsyncApiInference {
    private val NODES = JsonNodeFactory.instance
    private val CHANNEL_SPLIT = Regex("[.\\-/_ ]+")
    private val CLOUDEVENTS_FIELDS = listOf("specversion", "id", "source", "type")

    fun build(request: InferRequest, budget: RenderBudget, notes: Notes): ObjectNode {
        val root = NODES.objectNode()
        root.put("asyncapi", "3.0.0")
        val info = root.putObject("info")
        info.put("title", request.name?.trim()?.takeIf { it.isNotEmpty() } ?: request.messages.firstOrNull()?.channel ?: "Inferred events")
        info.put("version", request.version?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0")
        info.put("description", "")
        val channels = root.putObject("channels")
        val operations = root.putObject("operations")
        val schemas = LinkedHashMap<String, JsonNode>()
        val usedChannelIds = mutableSetOf<String>()
        val usedOperationIds = mutableSetOf<String>()
        request.messages.forEach { batch ->
            buildChannel(channels, operations, batch, budget, notes, schemas, usedChannelIds, usedOperationIds)
        }
        if (schemas.isNotEmpty()) {
            val componentSchemas = root.putObject("components").putObject("schemas")
            schemas.forEach { (name, node) -> componentSchemas.set<JsonNode>(name, node) }
        }
        notes.info("INFER_SAMPLES_MERGED", "${request.messages.size} channel sample(s) were merged into this document", "/channels")
        return root
    }

    private fun buildChannel(
        channels: ObjectNode,
        operations: ObjectNode,
        batch: MessageBatchSample,
        budget: RenderBudget,
        notes: Notes,
        schemas: LinkedHashMap<String, JsonNode>,
        usedChannelIds: MutableSet<String>,
        usedOperationIds: MutableSet<String>,
    ) {
        val id = uniqueId(channelId(batch.channel), usedChannelIds)
        val pascal = id.replaceFirstChar { it.uppercase() }
        val messageName = "${pascal}Message"
        val channelPointer = "/channels/${TryCatalog.esc(id)}"
        val channelNode = channels.putObject(id)
        channelNode.put("address", batch.channel)
        val messageNode = channelNode.putObject("messages").putObject(messageName)
        messageNode.put("name", messageName)
        messageNode.put("contentType", "application/json")
        val payloads = batch.payloads.mapNotNull { parseJsonOrNull(it) }
        val payloadPointer = "$channelPointer/messages/${TryCatalog.esc(messageName)}/payload"
        val payloadSchema = SchemaInference.infer(payloads, budget, notes, payloadPointer)
        applyCloudEvents(payloadSchema, payloads, pascal, schemas, notes, payloadPointer)
        messageNode.set<ObjectNode>("payload", payloadSchema)
        val opName = uniqueId("send$pascal", usedOperationIds)
        val opNode = operations.putObject(opName)
        opNode.put("action", "send")
        opNode.putObject("channel").put("\$ref", "#$channelPointer")
        opNode.putArray("messages").addObject().put("\$ref", "#$channelPointer/messages/${TryCatalog.esc(messageName)}")
    }

    private fun applyCloudEvents(
        payloadSchema: ObjectNode,
        payloads: List<JsonNode>,
        pascalName: String,
        schemas: LinkedHashMap<String, JsonNode>,
        notes: Notes,
        pointer: String,
    ) {
        if (payloads.isEmpty() || !payloads.all { isCloudEvent(it) }) return
        val properties = payloadSchema.path("properties")
        if (!properties.isObject || properties.path("data").isMissingNode) return
        val dataSchema = properties.path("data")
        val schemaName = "${pascalName}Data"
        schemas[schemaName] = dataSchema
        (properties as ObjectNode).set<ObjectNode>("data", NODES.objectNode().put("\$ref", "#/components/schemas/$schemaName"))
        notes.info(
            "INFER_CLOUDEVENTS",
            "The payload matches the CloudEvents envelope — 'data' was extracted to components.schemas.$schemaName",
            "$pointer/properties/data",
        )
    }

    private fun isCloudEvent(node: JsonNode): Boolean = node.isObject && CLOUDEVENTS_FIELDS.all { node.path(it).isTextual }

    private fun channelId(topic: String): String {
        val words = topic.split(CHANNEL_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "channel"
        return words.first().replaceFirstChar { it.lowercase() } +
            words.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun uniqueId(base: String, used: MutableSet<String>): String {
        var candidate = base
        var n = 2
        while (!used.add(candidate)) {
            candidate = "$base$n"
            n++
        }
        return candidate
    }
}
