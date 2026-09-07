package ch.nokillswit

import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.infer.Notes
import ch.nokillswit.contracts.infer.SchemaInference
import ch.nokillswit.contracts.render.RenderBudget
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure JSON → JSON-Schema inferencer every builder (HTTP bodies, AsyncAPI payloads, ODCS rows) funnels through. */
class SchemaInferenceTest {
    private val mapper = ObjectMapper()

    private fun infer(vararg json: String, pointer: String = "/x"): ObjectNode {
        val notes = Notes()
        return SchemaInference.infer(json.map { mapper.readTree(it) }, RenderBudget(), notes, pointer)
    }

    private fun inferWithNotes(vararg json: String, pointer: String = "/x"): Pair<ObjectNode, Notes> {
        val notes = Notes()
        return SchemaInference.infer(json.map { mapper.readTree(it) }, RenderBudget(), notes, pointer) to notes
    }

    @Test
    fun `scalar types`() {
        assertEquals("integer", infer("1", "2")["type"].asText())
        assertEquals("boolean", infer("true", "false")["type"].asText())
        assertEquals("string", infer("\"a\"", "\"b\"")["type"].asText())
    }

    @Test
    fun `an integer and a decimal at the same position merge to number`() {
        assertEquals("number", infer("1", "1.0")["type"].asText())
        assertEquals("number", infer("1.5", "2.5")["type"].asText())
    }

    @Test
    fun `null folds into a type array alongside the non-null type`() {
        val schema = infer("1", "null")
        assertTrue(schema["type"].isArray)
        assertEquals(listOf("integer", "null"), schema["type"].map { it.asText() })
    }

    @Test
    fun `mixed non-null types produce a type array and a WARN note`() {
        val (schema, notes) = inferWithNotes("1", "\"a\"")
        assertTrue(schema["type"].isArray)
        assertEquals(setOf("integer", "string"), schema["type"].map { it.asText() }.toSet())
        val note = notes.all.single()
        assertEquals("INFER_MIXED_TYPES", note.code)
        assertEquals(Severity.WARN, note.severity)
    }

    @Test
    fun `required is present and non-null in every sample`() {
        val schema = infer("""{"a":1,"b":2}""", """{"a":3}""")
        assertEquals(listOf("a"), schema.path("required").map { it.asText() })
        assertTrue(schema.path("properties").has("a"))
        assertTrue(schema.path("properties").has("b"))
    }

    @Test
    fun `a key that is null in one sample is not required, but its type gains null`() {
        val schema = infer("""{"a":1}""", """{"a":null}""")
        assertTrue(schema.path("required").isMissingNode)
        assertEquals(listOf("integer", "null"), schema.path("properties").path("a").path("type").map { it.asText() })
    }

    @Test
    fun `objects nest recursively`() {
        val schema = infer("""{"a":{"b":1}}""")
        assertEquals("object", schema.path("properties").path("a").path("type").asText())
        assertEquals("integer", schema.path("properties").path("a").path("properties").path("b").path("type").asText())
    }

    @Test
    fun `arrays unify their element samples into one items schema`() {
        val schema = infer("""[1,2]""", """[3]""")
        assertEquals("array", schema["type"].asText())
        assertEquals("integer", schema.path("items").path("type").asText())
    }

    @Test
    fun `an empty array carries no items key`() {
        val schema = infer("""[]""")
        assertEquals("array", schema["type"].asText())
        assertTrue(schema.path("items").isMissingNode)
    }

    @Test
    fun `date-time date time uuid email and uri formats are asserted when every sample matches`() {
        assertEquals("date-time", infer("\"2024-01-02T03:04:05Z\"")["format"].asText())
        assertEquals("date", infer("\"2024-01-02\"")["format"].asText())
        assertEquals("time", infer("\"03:04:05\"")["format"].asText())
        assertEquals("uuid", infer("\"123e4567-e89b-12d3-a456-426614174000\"")["format"].asText())
        assertEquals("email", infer("\"a@b.com\"")["format"].asText())
        assertEquals("uri", infer("\"https://example.com/x\"")["format"].asText())
    }

    @Test
    fun `a format is never asserted unless every non-null sample matches it`() {
        assertTrue(infer("\"2024-01-02\"", "\"not-a-date\"").path("format").isMissingNode)
        assertTrue(infer("\"a@b.com\"", "\"not-an-email\"").path("format").isMissingNode)
    }

    @Test
    fun `an empty sample list infers an empty untyped schema`() {
        val schema = SchemaInference.infer(emptyList(), RenderBudget(), Notes(), "/x")
        assertTrue(schema.path("type").isMissingNode)
    }

    @Test
    fun `the budget truncates past its ceiling with exactly one WARN note`() {
        val budget = RenderBudget(maxNodes = 1)
        val notes = Notes()
        val schema = SchemaInference.infer(listOf(mapper.readTree("""{"a":1}""")), budget, notes, "/x")
        assertTrue(schema.path("properties").path("a").path("type").isMissingNode, "the nested property was cut by the budget")
        assertEquals(1, notes.all.count { it.code == "INFER_TRUNCATED" })
        assertEquals(Severity.WARN, notes.all.single { it.code == "INFER_TRUNCATED" }.severity)
    }
}
