package ch.nokillswit

import ch.nokillswit.contracts.infer.PathTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The path-templating classifier and naming rule — `OpenApiInference`'s one caller. */
class PathTemplatesTest {
    @Test
    fun `classify recognizes integers, UUIDs, hex and opaque tokens, but not plain words`() {
        assertEquals(PathTemplates.ParamKind.INTEGER, PathTemplates.classify("123"))
        assertEquals(PathTemplates.ParamKind.UUID, PathTemplates.classify("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(PathTemplates.ParamKind.STRING, PathTemplates.classify("deadbeefcafebabe")) // 16 hex chars
        assertEquals(PathTemplates.ParamKind.STRING, PathTemplates.classify("abc123defghij456")) // >=16 chars, has a digit
        assertNull(PathTemplates.classify("orders"))
        assertNull(PathTemplates.classify("alice"))
        assertNull(PathTemplates.classify("short1")) // has a digit but under the length floor
    }

    @Test
    fun `a numeric segment templates with a param named from the preceding static segment`() {
        val t = PathTemplates.template("/orders/123")
        assertEquals("/orders/{orderId}", t.template)
        assertEquals(listOf("orderId"), t.params.map { it.name })
        assertEquals(PathTemplates.ParamKind.INTEGER, t.params.single().kind)
    }

    @Test
    fun `no preceding segment names the param id`() {
        val t = PathTemplates.template("/123")
        assertEquals("/{id}", t.template)
        assertEquals("id", t.params.single().name)
    }

    @Test
    fun `several templated segments each read their own preceding static segment`() {
        val t = PathTemplates.template("/orders/123/items/456")
        assertEquals("/orders/{orderId}/items/{itemId}", t.template)
        assertEquals(listOf("orderId", "itemId"), t.params.map { it.name })
    }

    @Test
    fun `a forced param-name collision gets a deduped suffix`() {
        val t = PathTemplates.template("/orders/1/orders/2")
        assertEquals("/orders/{orderId}/orders/{orderId2}", t.template)
        assertEquals(listOf("orderId", "orderId2"), t.params.map { it.name })
        assertEquals(t.params.map { it.name }.toSet().size, t.params.size, "param names must be distinct")
    }

    @Test
    fun `singularize drops a trailing s, turns ies into y, and leaves a double s alone`() {
        assertEquals("order", PathTemplates.singularize("orders"))
        assertEquals("category", PathTemplates.singularize("categories"))
        assertEquals("class", PathTemplates.singularize("class"))
        assertEquals("s", PathTemplates.singularize("s"))
    }

    @Test
    fun `no variation merging - two literal paths stay two literal paths`() {
        assertEquals("/users/alice", PathTemplates.template("/users/alice").template)
        assertEquals("/users/bob", PathTemplates.template("/users/bob").template)
        assertEquals(emptyList(), PathTemplates.template("/users/alice").params)
    }

    @Test
    fun `operationId is method plus PascalCased static segments plus By-param`() {
        val t = PathTemplates.template("/orders/123")
        assertEquals("getOrdersByOrderId", PathTemplates.operationId("GET", t.template, t.params))
    }

    @Test
    fun `operationId with no params carries no By suffix`() {
        assertEquals("getUsers", PathTemplates.operationId("GET", "/users", emptyList()))
    }
}
