package ch.nokillswit

import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.render.SchemaDialect
import ch.nokillswit.contracts.render.SchemaMarker
import ch.nokillswit.contracts.render.SchemaWalker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The normalization matrix, the cycle rule, the budget. */
class SchemaWalkerTest {
    private val yaml = ObjectMapper(YAMLFactory())

    private fun walk(
        doc: String,
        pointer: String,
        dialect: SchemaDialect = SchemaDialect.JSON_SCHEMA,
        budget: RenderBudget = RenderBudget(),
    ) = yaml.readTree(doc).let { root -> SchemaWalker(root, budget, dialect).node(root.at(pointer), pointer) }

    @Test
    fun `OpenAPI 3-0 keywords - nullable, boolean exclusiveMinimum, example, inferred object`() {
        val node = walk(RenderFixtures.openApiRich30, "/components/schemas/Pet", SchemaDialect.OPENAPI_30)
        assertEquals(listOf("object"), node.types)
        assertEquals("A pet", node.title)
        val byName = node.properties.associateBy { it.name }
        assertTrue(byName.getValue("id").required && !byName.getValue("score").required)
        assertEquals(listOf("7"), byName.getValue("id").schema.examples)
        assertTrue(byName.getValue("id").schema.readOnly)
        assertTrue(byName.getValue("name").schema.nullable, "3.0 nullable")
        val score = byName.getValue("score").schema.constraints.associate { it.keyword to it.value }
        assertEquals(mapOf("minimum" to "0", "maximum" to "10", "exclusiveMinimum" to "0"), score, "boolean exclusiveMinimum → minimum")
        assertEquals(listOf("dog", "cat"), byName.getValue("kind").schema.enumValues)
        assertEquals("dog", byName.getValue("kind").schema.defaultValue)
        assertEquals("kind", node.discriminator?.propertyName)
        assertEquals("#/components/schemas/Pet", node.discriminator?.mapping?.single()?.value)
        assertEquals(listOf("object"), byName.getValue("tags").schema.types, "inferred from additionalProperties")
        assertEquals(listOf("string"), byName.getValue("tags").schema.additionalProperties?.types)
        assertEquals(false, byName.getValue("extra").schema.additionalPropertiesAllowed)
        assertEquals(SchemaMarker.UNRESOLVED, byName.getValue("external").schema.marker)
        assertEquals("https://example.com/schemas.yaml#/Thing", byName.getValue("external").schema.ref)
    }

    @Test
    fun `the cycle rule - re-entry on the current stack is CIRCULAR, a sibling reference expands`() {
        val pet = walk(RenderFixtures.openApiRich30, "/components/schemas/Pet", SchemaDialect.OPENAPI_30)
        val owner = pet.properties.single { it.name == "owner" }.schema
        assertEquals("#/components/schemas/Owner", owner.ref)
        assertEquals("/components/schemas/Owner", owner.pointer, "a ref'd node lives at its target's pointer")
        assertTrue(owner.properties.single { it.name == "name" }.schema.writeOnly)
        val back = owner.properties.single { it.name == "pet" }.schema
        assertEquals(SchemaMarker.CIRCULAR, back.marker, "Pet is on the stack")
        assertEquals("A pet", back.title, "the title travels so the reader can name the cycle")
        val friends = pet.properties.single { it.name == "friends" }.schema
        assertEquals(listOf("array"), friends.types)
        assertEquals(SchemaMarker.CIRCULAR, friends.items?.marker)
    }

    @Test
    fun `3-1 keywords - type arrays, const, prefixItems, examples, numeric exclusiveMinimum, ref siblings overlaid`() {
        val node = walk(RenderFixtures.openApiRich31, "/paths/~1nodes/get/responses/200/content/application~1json/schema")
        assertEquals(listOf("array"), node.types)
        assertEquals(2, node.tupleItems.size)
        assertEquals(listOf("integer"), node.tupleItems[1].types)
        assertTrue(node.tupleItems[1].nullable, "a type array with null")
        assertEquals("1", node.tupleItems[1].constValue)
        assertEquals(listOf("""["a",1]"""), node.examples)
        assertEquals("3", node.constraints.single { it.keyword == "exclusiveMinimum" }.value)
        val items = assertNotNull(node.items)
        assertEquals("overlaid", items.description, "the referencing node's sibling keyword wins")
        assertEquals("#/components/schemas/Node", items.ref)
        val left = items.properties.single { it.name == "left" }.schema
        assertEquals(SchemaMarker.CIRCULAR, left.marker)
        val children = items.properties.single { it.name == "children" }.schema
        assertEquals(SchemaMarker.CIRCULAR, children.items?.marker)
        assertNull(node.marker)
    }

    @Test
    fun `boolean and non-object schemas, tuple items, allOf-anyOf-oneOf-not`() {
        val doc = """
            s:
              allOf: [{ type: object }, true]
              anyOf: [{ type: string }]
              oneOf: [{ type: integer }, { type: number }]
              not: { type: "null" }
              items: [{ type: string }, { type: integer }]
              minItems: 1
              uniqueItems: true
            b: false
            n: 42
        """.trimIndent()
        val s = walk(doc, "/s")
        assertEquals(listOf("array"), s.types, "array inferred from tuple items")
        assertEquals(2, s.tupleItems.size)
        assertEquals("/s/items/1", s.tupleItems[1].pointer)
        assertNull(s.items)
        assertEquals(true, s.allOf[1].additionalPropertiesAllowed, "a boolean schema true")
        assertEquals(1, s.anyOf.size)
        assertEquals(2, s.oneOf.size)
        assertTrue(s.not?.nullable == true)
        assertEquals(listOf("minItems" to "1", "uniqueItems" to "true"), s.constraints.map { it.keyword to it.value })
        assertEquals(false, walk(doc, "/b").additionalPropertiesAllowed)
        assertEquals("42", walk(doc, "/n").raw)
    }

    @Test
    fun `the budget - a deep chain hits the depth cap, a wide object hits the node cap, both flag the render`() {
        val deep = StringBuilder("root:\n")
        var indent = "  "
        repeat(40) {
            deep.append("${indent}type: object\n${indent}properties:\n$indent  next:\n")
            indent += "    "
        }
        deep.append("${indent}type: string\n")
        val budget = RenderBudget()
        val node = walk(deep.toString(), "/root", budget = budget)
        var cursor = node
        var depth = 0
        while (cursor.marker == null && cursor.properties.isNotEmpty()) {
            cursor = cursor.properties.single().schema
            depth++
        }
        assertEquals(SchemaMarker.TRUNCATED, cursor.marker)
        assertTrue(depth in 30..33, "cut around the depth cap, at $depth")
        assertTrue(budget.truncated)

        val wide = StringBuilder("root:\n  type: object\n  properties:\n")
        repeat(30) { wide.append("    p$it: { type: string }\n") }
        val small = RenderBudget(maxNodes = 10)
        val w = walk(wide.toString(), "/root", budget = small)
        assertEquals(30, w.properties.size)
        assertEquals(9, w.properties.count { it.schema.marker == null })
        assertTrue(w.properties.drop(9).all { it.schema.marker == SchemaMarker.TRUNCATED })
        assertTrue(small.truncated)
    }
}
