package ch.nokillswit

import ch.nokillswit.contracts.render.AvroSchemaMapper
import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.render.SchemaMarker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvroSchemaMapperTest {
    private val yaml = ObjectMapper(YAMLFactory())

    @Test
    fun `record, nullable union, enum, array of records, map, back-reference, fixed, logical type, multi-branch union`() {
        val root = yaml.readTree(RenderFixtures.asyncApi3Rich)
        val schema = root.at("/components/messages/orderPlaced/payload/schema")
        val node = AvroSchemaMapper(RenderBudget()).node(schema, "/p")
        assertEquals(listOf("object"), node.types)
        assertEquals("OrderPlaced", node.title)
        assertEquals("An order", node.description)
        val f = node.properties.associateBy { it.name }
        assertTrue(f.getValue("orderId").required)
        assertEquals("The id", f.getValue("orderId").schema.description)
        val note = f.getValue("note")
        assertTrue(note.schema.nullable && !note.required, "a null union with a default")
        assertEquals(listOf("string"), note.schema.types)
        assertEquals("null", note.schema.defaultValue)
        assertEquals(listOf("NEW", "PAID"), f.getValue("status").schema.enumValues)
        assertEquals("Status", f.getValue("status").schema.title)
        val lines = f.getValue("lines").schema
        assertEquals(listOf("array"), lines.types)
        assertEquals("Line", lines.items?.title)
        assertEquals(listOf("sku"), lines.items?.properties?.map { it.name })
        val attrs = f.getValue("attrs").schema
        assertEquals(listOf("object"), attrs.types)
        assertEquals("int64", attrs.additionalProperties?.format)
        val parent = f.getValue("parent").schema
        assertEquals(SchemaMarker.CIRCULAR, parent.marker, "a named back-reference")
        assertEquals("com.example.OrderPlaced", parent.ref)
        assertTrue(parent.nullable)
        assertEquals("fixed(16)", f.getValue("hash").schema.format)
        assertEquals("timestamp-millis", f.getValue("when").schema.format)
        val either = f.getValue("either").schema
        assertEquals(2, either.anyOf.size)
        assertEquals(listOf("integer"), either.anyOf[0].types)
        assertEquals("/p/fields/8/type/1", either.anyOf[1].pointer)
    }

    @Test
    fun `primitives, unknown names and the budget`() {
        val mapper = AvroSchemaMapper(RenderBudget())
        val node = mapper.node(yaml.readTree("\"bytes\""), "/x")
        assertEquals("bytes", node.format)
        assertEquals(listOf("string"), node.types)
        assertEquals(SchemaMarker.UNRESOLVED, mapper.node(yaml.readTree("\"Nope\""), "/y").marker)
        val small = RenderBudget(maxNodes = 1)
        val cut = AvroSchemaMapper(small).node(yaml.readTree("""{"type": "array", "items": "string"}"""), "/z")
        assertEquals(SchemaMarker.TRUNCATED, assertNotNull(cut.items).marker)
        assertTrue(small.truncated)
    }
}
