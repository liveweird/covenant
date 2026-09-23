package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType

/** Apache Avro reader/writer compatibility for payloads at the same AsyncAPI message site. */
object AvroBreaking {
    const val CODE_INCOMPATIBLE = "AVRO_READER_WRITER_INCOMPATIBLE"
    const val CODE_LOGICAL_TYPE = "AVRO_LOGICAL_TYPE_CHANGED"
    const val CODE_DECIMAL = "AVRO_DECIMAL_CHANGED"
    const val CODE_SKIPPED = "AVRO_COMPATIBILITY_SKIPPED"

    fun compare(oldRoot: JsonNode, newRoot: JsonNode): List<Finding> {
        val oldSites = sites(oldRoot)
        val newSites = sites(newRoot)
        val matched = match(oldSites, newSites)
        val facts = matched.pairs.flatMap { (old, new) -> compareSite(old, new) }.toMutableList()
        val unmatchedNewAvro = matched.newUnmatched.filter { it.avro }.groupBy { it.channel }
        matched.oldUnmatched.filter { it.avro }.groupBy { it.channel }.forEach { (channel, _) ->
            val newOnChannel = unmatchedNewAvro[channel].orEmpty()
            if (newOnChannel.isNotEmpty()) {
                facts += skipped(
                    "Avro payloads on channel '$channel' could not be paired unambiguously across the documents",
                    newOnChannel.first().pointer,
                )
            }
        }
        return facts
    }

    private fun compareSite(old: Site, new: Site): List<Finding> {
        if (!old.avro && !new.avro) return emptyList()
        val label = "Avro payload '${new.message}' on channel '${new.channel}'"
        if (!old.avro || !new.avro) return emptyList() // format switches remain the generic AsyncAPI differ's fact
        val problem = old.problem ?: new.problem
        if (problem != null) return listOf(skipped("$label could not be compared — $problem", new.pointer))
        val oldSchema = parse(old.schema!!)
        val newSchema = parse(new.schema!!)
        if (oldSchema == null || newSchema == null) {
            return listOf(skipped("$label could not be compared — one of the schemas is invalid", new.pointer))
        }
        val roles = old.roles + new.roles
        if (roles.isEmpty()) {
            return listOf(skipped("$label could not be compared — no send or receive operation identifies its direction", new.pointer))
        }
        return roles.flatMap { role ->
            val (reader, writer) = when (role) {
                Role.RECEIVE -> newSchema to oldSchema
                Role.SEND -> oldSchema to newSchema
            }
            compatibility(reader, writer, label, role, new.pointer)
        }.distinctBy { Triple(it.code, it.message, it.path) }
    }

    private fun compatibility(reader: Schema, writer: Schema, label: String, role: Role, pointer: String): List<Finding> {
        val direction = if (role == Role.RECEIVE) "receive (new reader / old writer)" else "send (old reader / new writer)"
        val result = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).result
        val facts = result.incompatibilities.map { issue ->
            breaking(
                CODE_INCOMPATIBLE,
                "$label is incompatible for $direction: ${issue.message} (${issue.type})",
                pointer,
            )
        }.toMutableList()
        facts += logicalTypeFacts(reader, writer, label, direction, pointer)
        return facts
    }

    /**
     * Avro 1.12's structural checker deliberately ignores logical-type annotations. The Avro
     * specification requires decimal scale and precision to match; treating any other logical
     * type change as a break also prevents equal primitive encodings from hiding changed meaning.
     */
    private fun logicalTypeFacts(
        reader: Schema,
        writer: Schema,
        label: String,
        direction: String,
        pointer: String,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val seen = mutableSetOf<SchemaPair>()
        fun walk(r: Schema, w: Schema, location: String) {
            val pair = SchemaPair(r, w)
            if (!seen.add(pair)) return
            val readerLogical = r.logicalType?.name
            val writerLogical = w.logicalType?.name
            if (readerLogical != writerLogical) {
                out += breaking(
                    CODE_LOGICAL_TYPE,
                    "$label changes logical type at $location for $direction: ${writerLogical ?: "none"} to ${readerLogical ?: "none"}",
                    pointer,
                )
            } else if (readerLogical == "decimal") {
                val readerPrecision = r.getObjectProp("precision")
                val writerPrecision = w.getObjectProp("precision")
                val readerScale = r.getObjectProp("scale") ?: 0
                val writerScale = w.getObjectProp("scale") ?: 0
                if (readerPrecision != writerPrecision || readerScale != writerScale) {
                    out += breaking(
                        CODE_DECIMAL,
                        "$label changes decimal precision/scale at $location for $direction: " +
                            "$writerPrecision/$writerScale to $readerPrecision/$readerScale",
                        pointer,
                    )
                }
            }
            when {
                r.type == Schema.Type.RECORD && w.type == Schema.Type.RECORD -> r.fields.forEach { readerField ->
                    SchemaCompatibility.lookupWriterField(w, readerField)?.let { writerField ->
                        walk(readerField.schema(), writerField.schema(), "$location/${readerField.name()}")
                    }
                }
                r.type == Schema.Type.ARRAY && w.type == Schema.Type.ARRAY -> walk(r.elementType, w.elementType, "$location/items")
                r.type == Schema.Type.MAP && w.type == Schema.Type.MAP -> walk(r.valueType, w.valueType, "$location/values")
                w.type == Schema.Type.UNION -> w.types.forEachIndexed { index, branch ->
                    compatibleBranch(r, branch)?.let { matched -> walk(matched, branch, "$location/union/$index") }
                }
                r.type == Schema.Type.UNION -> r.types.forEachIndexed { index, branch ->
                    if (compatible(branch, w)) walk(branch, w, "$location/union/$index")
                }
            }
        }
        walk(reader, writer, "payload")
        return out
    }

    private fun compatibleBranch(reader: Schema, writer: Schema): Schema? = if (reader.type == Schema.Type.UNION) {
        reader.types.firstOrNull { compatible(it, writer) }
    } else {
        reader.takeIf { compatible(it, writer) }
    }

    private fun compatible(reader: Schema, writer: Schema): Boolean =
        SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).type == SchemaCompatibilityType.COMPATIBLE

    private fun sites(root: JsonNode): List<Site> {
        val v3 = AsyncApiSites.isV3(root)
        val operationRoles = if (v3) operationRoles(root) else OperationRoleIndex.EMPTY
        return root.path("channels").fields().asSequence().flatMap { (channelKey, declaredChannel) ->
            val channelResolution = resolve(root, declaredChannel, "/channels/${AsyncApiSites.escape(channelKey)}")
            val channel = channelResolution.node
            val channelPointer = channelResolution.pointer
            val channelName = if (v3) channel.path("address").takeIf { it.isTextual }?.asText() ?: channelKey else channelKey
            AsyncApiSites.messagesOf(channel, channelPointer, v3).asSequence().map { site ->
                val messageResolution = resolve(root, site.node, site.pointer)
                val message = messageResolution.node
                val payload = payload(root, message, messageResolution.pointer)
                val roles = if (v3) {
                    operationRoles.roles(
                        setOf("/channels/${AsyncApiSites.escape(channelKey)}", channelPointer),
                        setOf(site.pointer, messageResolution.pointer),
                    )
                } else {
                    setOfNotNull(if (site.verb == "publish") Role.RECEIVE else if (site.verb == "subscribe") Role.SEND else null)
                }
                val explicitName = MESSAGE_NAME_FIELDS.firstNotNullOfOrNull { field ->
                    message.path(field).takeIf { it.isTextual }?.asText()
                }
                val refName = site.node.path("\$ref").takeIf { it.isTextual }?.asText()?.substringAfterLast('/')
                val schemaName = payload.schema?.path("name")?.takeIf { it.isTextual }?.asText()?.let { name ->
                    payload.schema.path("namespace").takeIf { it.isTextual }?.asText()?.let { "$it.$name" } ?: name
                }
                Site(
                    channelName,
                    explicitName ?: refName ?: schemaName ?: site.displayName(),
                    buildList {
                        if (v3) add("key:${site.key}")
                        explicitName?.let { add("name:$it") }
                        refName?.let { add("ref:$it") }
                        schemaName?.let { add("schema:$it") }
                    }.distinct(),
                    payload.schema,
                    payload.format?.let(SchemaFormats::isAvro) == true,
                    payload.pointer,
                    roles,
                    channelResolution.problem ?: messageResolution.problem ?: payload.problem,
                )
            }
        }.toList()
    }

    private fun operationRoles(root: JsonNode): OperationRoleIndex {
        val allMessages = mutableMapOf<String, MutableSet<Role>>()
        val selectedMessages = mutableMapOf<OperationMessageKey, MutableSet<Role>>()
        root.path("operations").fields().forEach { (name, raw) ->
            val operation = resolve(root, raw, "/operations/${AsyncApiSites.escape(name)}").node
            val role = when (operation.path("action").asText()) {
                "receive" -> Role.RECEIVE
                "send" -> Role.SEND
                else -> return@forEach
            }
            indexOperationRole(operation, role, allMessages, selectedMessages)
            operation.path("reply").takeIf { it.isObject }?.let { reply ->
                indexOperationRole(
                    reply,
                    if (role == Role.RECEIVE) Role.SEND else Role.RECEIVE,
                    allMessages,
                    selectedMessages,
                )
            }
        }
        return OperationRoleIndex(allMessages, selectedMessages)
    }

    /** A reply flows opposite to its parent operation, but otherwise names a channel/messages the same way. */
    private fun indexOperationRole(
        node: JsonNode,
        role: Role,
        allMessages: MutableMap<String, MutableSet<Role>>,
        selectedMessages: MutableMap<OperationMessageKey, MutableSet<Role>>,
    ) {
        val channelRef = node.path("channel").path("\$ref").takeIf { it.isTextual }?.asText() ?: return
        val channelPointer = localPointer(channelRef) ?: return
        val messages = node.path("messages").takeIf { it.isArray }?.mapNotNull { item ->
            item.path("\$ref").takeIf { it.isTextual }?.asText()?.let(::localPointer)
        }.orEmpty().toSet()
        if (messages.isEmpty()) {
            allMessages.getOrPut(channelPointer) { mutableSetOf() } += role
        } else {
            messages.forEach { messagePointer ->
                selectedMessages.getOrPut(OperationMessageKey(channelPointer, messagePointer)) { mutableSetOf() } += role
            }
        }
    }

    private fun payload(root: JsonNode, message: JsonNode, messagePointer: String): Payload {
        val raw = message.path("payload")
        if (raw.isMissingNode || raw.isNull) return Payload(null, message.path("schemaFormat").text(), "$messagePointer/payload", null)
        val wrapper = raw.isObject && raw.has("schemaFormat") && raw.has("schema")
        val format = if (wrapper) raw.path("schemaFormat").text() else message.path("schemaFormat").text()
        val schema = if (wrapper) raw.path("schema") else raw
        val pointer = if (wrapper) "$messagePointer/payload/schema" else "$messagePointer/payload"
        val resolved = resolve(root, schema, pointer)
        return Payload(resolved.node, format, resolved.pointer, resolved.problem)
    }

    private fun match(old: List<Site>, new: List<Site>): MatchedSites {
        val remainingOld = old.indices.toMutableSet()
        val remainingNew = new.indices.toMutableSet()
        val pairs = mutableListOf<Pair<Site, Site>>()
        IDENTITY_PREFIXES.forEach { prefix ->
            val oldIndex = uniqueIndex(old, remainingOld, prefix)
            val newIndex = uniqueIndex(new, remainingNew, prefix)
            oldIndex.keys.intersect(newIndex.keys).forEach { key ->
                val before = oldIndex.getValue(key)
                val after = newIndex.getValue(key)
                pairs += old[before] to new[after]
                remainingOld -= before
                remainingNew -= after
            }
        }
        val newByChannel = remainingNew.groupBy { new[it].channel }
        remainingOld.groupBy { old[it].channel }.forEach { (channel, oldOnChannel) ->
            val newOnChannel = newByChannel[channel].orEmpty()
            if (oldOnChannel.size == 1 && newOnChannel.size == 1) {
                val before = oldOnChannel.single()
                val after = newOnChannel.single()
                pairs += old[before] to new[after]
                remainingOld -= before
                remainingNew -= after
            }
        }
        return MatchedSites(pairs, remainingOld.map(old::get), remainingNew.map(new::get))
    }

    /** One pass per bounded identity kind; duplicate identities are intentionally left ambiguous. */
    private fun uniqueIndex(sites: List<Site>, remaining: Set<Int>, prefix: String): Map<MatchKey, Int> {
        val grouped = mutableMapOf<MatchKey, MutableList<Int>>()
        remaining.forEach { index ->
            sites[index].identities.firstOrNull { it.startsWith(prefix) }?.let { identity ->
                grouped.getOrPut(MatchKey(sites[index].channel, identity)) { mutableListOf() } += index
            }
        }
        return grouped.mapNotNull { (key, indices) -> indices.singleOrNull()?.let { key to it } }.toMap()
    }

    private fun resolve(root: JsonNode, declared: JsonNode, fallbackPointer: String): Resolution {
        var node = declared
        var pointer = fallbackPointer
        val seen = mutableSetOf<String>()
        repeat(MAX_REF_DEPTH) {
            val ref = node.path("\$ref").takeIf { it.isTextual }?.asText() ?: return Resolution(node, pointer, null)
            val target = localPointer(ref) ?: return Resolution(node, pointer, "external reference '$ref' is unresolved")
            if (!seen.add(target)) return Resolution(node, pointer, "reference cycle at '$ref'")
            val resolved = root.at(target)
            if (resolved.isMissingNode) return Resolution(node, pointer, "local reference '$ref' is dangling")
            node = resolved
            pointer = target
        }
        return Resolution(node, pointer, "reference chain exceeds $MAX_REF_DEPTH hops")
    }

    private fun localPointer(ref: String): String? = when {
        ref == "#" -> ""
        ref.startsWith("#/") -> ref.drop(1)
        else -> null
    }

    private fun JsonNode.text(): String? = takeIf { it.isTextual }?.asText()
    private fun parse(node: JsonNode): Schema? = try {
        Schema.Parser().parse(node.toString())
    } catch (_: RuntimeException) {
        null
    }

    private fun breaking(code: String, message: String, path: String) =
        Finding(Severity.WARN, FindingSource.BREAKING, code, message, path)

    private fun skipped(message: String, path: String) =
        Finding(Severity.INFO, FindingSource.BREAKING, CODE_SKIPPED, message, path)

    private enum class Role { RECEIVE, SEND }
    private data class Site(
        val channel: String,
        val message: String,
        val identities: List<String>,
        val schema: JsonNode?,
        val avro: Boolean,
        val pointer: String,
        val roles: Set<Role>,
        val problem: String?,
    )
    private data class Payload(val schema: JsonNode?, val format: String?, val pointer: String, val problem: String?)
    private data class Resolution(val node: JsonNode, val pointer: String, val problem: String?)
    private data class OperationMessageKey(val channelPointer: String, val messagePointer: String)
    private data class OperationRoleIndex(
        val allMessages: Map<String, Set<Role>>,
        val selectedMessages: Map<OperationMessageKey, Set<Role>>,
    ) {
        fun roles(channelPointers: Set<String>, messagePointers: Set<String>): Set<Role> = buildSet {
            channelPointers.forEach { channelPointer ->
                addAll(allMessages[channelPointer].orEmpty())
                messagePointers.forEach { messagePointer ->
                    addAll(selectedMessages[OperationMessageKey(channelPointer, messagePointer)].orEmpty())
                }
            }
        }

        companion object {
            val EMPTY = OperationRoleIndex(emptyMap(), emptyMap())
        }
    }
    private data class MatchKey(val channel: String, val identity: String)
    private data class MatchedSites(
        val pairs: List<Pair<Site, Site>>,
        val oldUnmatched: List<Site>,
        val newUnmatched: List<Site>,
    )
    private class SchemaPair(val reader: Schema, val writer: Schema) {
        override fun equals(other: Any?) = other is SchemaPair && reader === other.reader && writer === other.writer
        override fun hashCode() = 31 * System.identityHashCode(reader) + System.identityHashCode(writer)
    }

    private const val MAX_REF_DEPTH = 16
    private val MESSAGE_NAME_FIELDS = listOf("name", "messageId", "title")
    private val IDENTITY_PREFIXES = listOf("key:", "name:", "ref:", "schema:")
}
