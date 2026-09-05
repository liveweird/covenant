package ch.nokillswit

import ch.nokillswit.contracts.render.JsonPointers
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonPointersTest {
    private val root = ObjectMapper().readTree("""{"a": {"b/c": {"~x": 1}}, "list": [10, 20]}""")

    @Test
    fun `escape, unescape and the last segment`() {
        assertEquals("b~1c", JsonPointers.escape("b/c"))
        assertEquals("~0x", JsonPointers.escape("~x"))
        assertEquals("b/c", JsonPointers.unescape("b~1c"))
        assertEquals("Pet", JsonPointers.name("#/components/schemas/Pet"))
        assertEquals("b/c", JsonPointers.name("/a/b~1c"))
    }

    @Test
    fun `resolves in-document refs and pointers, refuses external or dangling ones`() {
        assertEquals(1, JsonPointers.resolve(root, "#/a/b~1c/~0x")?.asInt())
        assertEquals(20, JsonPointers.resolve(root, "/list/1")?.asInt())
        assertEquals(root, JsonPointers.resolve(root, "#"))
        assertNull(JsonPointers.resolve(root, "#/a/missing"))
        assertNull(JsonPointers.resolve(root, "https://example.com/x.yaml#/a"))
        assertNull(JsonPointers.resolve(root, "a/b"))
        assertEquals("/a", JsonPointers.pointerOf("#/a"))
        assertNull(JsonPointers.pointerOf("other.yaml#/a"))
    }
}
