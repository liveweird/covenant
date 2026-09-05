package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/** RFC 6901 helpers over Jackson trees — the renderer's `$ref` resolution, in-document only, no I/O. */
object JsonPointers {
    fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

    fun unescape(segment: String): String = segment.replace("~1", "/").replace("~0", "~")

    /** The last segment of a pointer or `#`-ref (`#/components/schemas/Pet` → `Pet`), unescaped. */
    fun name(pointerOrRef: String): String = unescape(pointerOrRef.substringAfterLast('/'))

    /** `#/a/b~1c` or `/a/b~1c` → the node, or null when the ref is external, malformed or dangling. */
    fun resolve(root: JsonNode, ref: String): JsonNode? {
        val pointer = when {
            ref.startsWith("#/") -> ref.drop(1)
            ref == "#" -> ""
            ref.startsWith("/") -> ref
            else -> return null
        }
        val node = root.at(pointer)
        return if (node.isMissingNode) null else node
    }

    /** A `#`-ref's pointer form (`#/a/b` → `/a/b`), or null for anything not in-document. */
    fun pointerOf(ref: String): String? = if (ref.startsWith("#/")) ref.drop(1) else null
}
