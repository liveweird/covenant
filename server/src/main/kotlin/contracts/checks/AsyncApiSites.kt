package ch.nokillswit.contracts.checks

import com.fasterxml.jackson.databind.JsonNode

/**
 * One message site under an AsyncAPI channel — THE shared enumeration behind the validator's payload
 * walk ([AsyncApiValidator.payloads]), the reader's channel view (`render/AsyncApiRenderer`) and the
 * try catalog (`tryit/TryCatalog`). Before the checkup each kept its own copy of the 2.x
 * `publish`/`subscribe` + `oneOf` walk with its own pointer spelling; the reader's findings deep-link
 * and the try-it conformance path both depend on every pointer here agreeing with the validator's.
 */
internal data class MessageSite(
    /** The key under `messages` (3.x), or the 2.x verb — `publish`/`subscribe`, `-<i>` for a `oneOf` alternative. */
    val key: String,
    /** The JSON pointer of the message node AS DECLARED under the channel (a `$ref` site keeps its own pointer). */
    val pointer: String,
    /** The node as declared — possibly a bare `$ref` wrapper (resolve it where the target's content matters). */
    val node: JsonNode,
    /** The 2.x verb the message hangs under; null for a 3.x message. */
    val verb: String?,
) {
    /** What to call it: a bare `$ref`'s last segment, else `name` / `messageId` / `title`, else the key. */
    fun displayName(): String {
        val ref = node.path("\$ref").takeIf { it.isTextual }?.asText()
        if (ref != null) return ref.substringAfterLast('/')
        return NAME_FIELDS.firstNotNullOfOrNull { field -> node.path(field).takeIf { it.isTextual }?.asText() } ?: key
    }

    private companion object {
        val NAME_FIELDS = listOf("name", "messageId", "title")
    }
}

internal object AsyncApiSites {
    /** The 2.x operation verbs, in document order. */
    val LEGACY_VERBS: List<String> = listOf("publish", "subscribe")

    fun isV3(root: JsonNode): Boolean = root.path("asyncapi").asText().startsWith("3.")

    /** RFC 6901 escaping of one pointer segment. */
    fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

    /**
     * The messages a channel declares: 3.x `messages` by key; 2.x the `publish`/`subscribe` message, or each
     * `oneOf` alternative. [channel] is the channel node the caller chose (resolved or as declared) and
     * [channelPointer] where it sits — the sites' pointers extend it.
     */
    fun messagesOf(channel: JsonNode, channelPointer: String, v3: Boolean): List<MessageSite> =
        if (v3) {
            channel.path("messages").fields().asSequence()
                .map { (key, message) -> MessageSite(key, "$channelPointer/messages/${escape(key)}", message, verb = null) }
                .toList()
        } else {
            LEGACY_VERBS.flatMap { verb ->
                val message = channel.path(verb).path("message")
                when {
                    message.isMissingNode -> emptyList()
                    message.has("oneOf") -> message["oneOf"].mapIndexed { i, alternative ->
                        MessageSite("$verb-$i", "$channelPointer/$verb/message/oneOf/$i", alternative, verb)
                    }
                    else -> listOf(MessageSite(verb, "$channelPointer/$verb/message", message, verb))
                }
            }
        }
}

/** Which `schemaFormat` strings mean what — one answer for the validator, the renderer and the conformance check. */
internal object SchemaFormats {
    private val JSON_LIKE = listOf("json", "asyncapi", "openapi")

    /** No format = the AsyncAPI Schema Object (JSON Schema); `*json*`, `*asyncapi*` and `*openapi*` formats are JSON-Schema-like too. */
    fun isJsonLike(format: String?): Boolean = format == null || JSON_LIKE.any { format.contains(it, ignoreCase = true) }

    fun isAvro(format: String?): Boolean = format?.contains("avro", ignoreCase = true) == true
}
