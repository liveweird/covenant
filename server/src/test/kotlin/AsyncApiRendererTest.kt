package ch.nokillswit

import ch.nokillswit.contracts.render.AsyncApiRenderer
import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.render.SchemaMarker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsyncApiRendererTest {
    private val yaml = ObjectMapper(YAMLFactory())

    private fun render(doc: String) = AsyncApiRenderer(yaml.readTree(doc), RenderBudget()).render()

    @Test
    fun `2-6 unified onto 3-x - the key is the address, publish is receive, oneOf and inline messages rendered once`() {
        val m = render(RenderFixtures.asyncApi2Rich)
        val server = m.servers.single()
        assertEquals("broker.example.com:9092", server.host)
        assertEquals("/prod", server.pathname)
        assertEquals("3.6", server.protocolVersion)
        assertEquals(listOf("sasl"), server.security)
        val channel = m.channels.single()
        assertEquals("lights/{id}/measured", channel.address)
        assertEquals("Measurements", channel.description)
        assertEquals("id", channel.parameters.single().name)
        assertEquals(listOf("string"), channel.parameters.single().schema?.types)
        assertEquals(listOf("lightMeasured", "turnedOff", "subscribe"), channel.messages.map { it.name })
        val targets = listOf("lightMeasured", "lights/{id}/measured/turnedOff", "lights/{id}/measured/subscribe")
        assertEquals(targets, channel.messages.map { it.target })
        val ops = m.operations
        assertEquals(listOf("receive" to "publish", "send" to "subscribe"), ops.map { it.action to it.legacyAction })
        assertEquals("onMeasured", ops[0].name)
        assertEquals("lights/{id}/measured subscribe", ops[1].name)
        assertEquals(2, ops[0].messages.size)
        val messages = m.messages.associateBy { it.key }
        assertEquals(3, messages.size, "each payload rendered once")
        val lm = messages.getValue("lightMeasured")
        assertTrue(!lm.inline)
        assertEquals("application/json", lm.contentType, "the default content type")
        assertEquals("\$message.header#/correlationId", lm.correlationId)
        assertEquals(listOf("correlationId"), lm.headers?.properties?.map { it.name })
        assertEquals("""{"lumens":900}""", lm.examples.single().value)
        assertEquals("bright", lm.examples.single().name)
        val off = messages.getValue("lights/{id}/measured/turnedOff")
        assertTrue(off.inline)
        assertEquals("date-time", off.payload?.properties?.single()?.schema?.format)
        assertEquals("Dim command", messages.getValue("lights/{id}/measured/subscribe").title)
        assertEquals(listOf("sasl"), m.securitySchemes.map { it.name })
        assertEquals("scramSha256", m.securitySchemes.single().type)
    }

    @Test
    fun `3-x - refs to channel messages resolve to keys, reply, Avro payload, protobuf raw, servers and tags`() {
        val m = render(RenderFixtures.asyncApi3Rich)
        assertEquals(listOf("orders"), m.tags.map { it.name })
        assertEquals("https://docs.example.com/orders", m.externalDocs?.url)
        val server = m.servers.single()
        assertEquals("/orders", server.pathname)
        assertEquals(listOf("sasl"), server.security)
        assertEquals(listOf("kafka"), server.bindings)
        val placed = m.channels.single { it.name == "orderPlaced" }
        assertEquals("orders.placed", placed.address)
        assertEquals(listOf("prod"), placed.servers)
        val refs = listOf("orderPlaced" to "orderPlaced", "inlineNote" to "orderPlaced/inlineNote")
        assertEquals(refs, placed.messages.map { it.name to it.target })
        val op = m.operations.single()
        assertEquals("receive", op.action)
        assertNull(op.legacyAction)
        assertEquals("orderPlaced", op.channel)
        assertEquals(listOf("orderPlaced", "orderPlaced/inlineNote"), op.messages.map { it.target })
        assertEquals("orderReply", op.reply)
        assertEquals(listOf("orders"), op.tags)
        val messages = m.messages.associateBy { it.key }
        assertEquals(setOf("orderPlaced", "orderPlaced/inlineNote", "proto/raw"), messages.keys)
        val avro = messages.getValue("orderPlaced")
        assertEquals("application/vnd.apache.avro;version=1.9.0", avro.schemaFormat)
        assertEquals("OrderPlaced", avro.payload?.title, "Avro through the mapper")
        assertEquals("/components/messages/orderPlaced/payload/schema", avro.payload?.pointer)
        val note = messages.getValue("orderPlaced/inlineNote")
        assertEquals("text/plain", note.contentType)
        assertEquals(listOf("string"), note.payload?.types)
        val raw = messages.getValue("proto/raw")
        assertNotNull(raw.payload?.raw, "an unknown format stays raw text")
        assertEquals("application/vnd.google.protobuf;version=3", raw.payload?.format)
        assertEquals(listOf("Money"), m.schemas.map { it.name })
        assertNull(raw.payload?.marker)
    }

    @Test
    fun `the fixtures the checks use, and broken shapes, all render totally`() {
        val v3 = render(ContractFixtures.asyncApi3)
        assertEquals("lightMeasured", v3.channels.single().messages.single().target)
        assertEquals(listOf("integer"), v3.messages.single().payload?.properties?.single()?.schema?.types)
        val avro = render(ContractFixtures.asyncApiAvro)
        assertEquals("OrderPlaced", avro.messages.single().payload?.title)
        val v2 = render(ContractFixtures.asyncApi2)
        assertEquals("receiveLightMeasurement", v2.operations.single().name)
        val broken = render("asyncapi: 3.0.0\ninfo: 3\nchannels: [1]\noperations: { x: { channel: 5 } }\ncomponents: { messages: 1 }\n")
        assertTrue(broken.channels.isEmpty() && broken.messages.isEmpty())
        assertEquals("", broken.operations.single().action)
        assertNull(broken.operations.single().channel)
        val danglingDoc = "asyncapi: 3.0.0\ninfo: { title: T, version: '1' }\n" +
            "channels:\n  c:\n    messages:\n      m: { \$ref: '#/components/messages/nope' }\n"
        val dangling = render(danglingDoc)
        assertNull(dangling.channels.single().messages.single().target)
        val cut = AsyncApiRenderer(yaml.readTree(ContractFixtures.asyncApi3), RenderBudget(maxNodes = 1)).render()
        assertEquals(SchemaMarker.TRUNCATED, cut.messages.single().payload?.properties?.single()?.schema?.marker)
    }
}
