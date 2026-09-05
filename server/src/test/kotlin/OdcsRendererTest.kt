package ch.nokillswit

import ch.nokillswit.contracts.render.OdcsRenderer
import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.render.SchemaMarker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdcsRendererTest {
    private val yaml = ObjectMapper(YAMLFactory())

    private fun render(doc: String, budget: RenderBudget = RenderBudget()) = OdcsRenderer(yaml.readTree(doc), budget).render()

    @Test
    fun `the header, description, datasets with nested properties and quality, servers, team, roles, SLA, support, price`() {
        val m = render(RenderFixtures.odcsRich)
        assertEquals("customers", m.name)
        assertEquals("active", m.status)
        assertEquals(listOf("pii", "gold"), m.tags)
        assertEquals("2026-01-01T00:00:00Z", m.contractCreatedTs)
        val d = assertNotNull(m.description)
        assertEquals("EU only.", d.limitations)
        assertEquals("businessDefinition", d.authoritativeDefinitions.single().type)
        assertEquals(listOf("steward" to "alice"), d.customProperties.map { it.key to it.value })
        val ds = m.datasets.single()
        assertEquals("/schema/0", ds.pointer)
        assertEquals("crm.customers", ds.physicalName)
        assertEquals("Per customer", ds.dataGranularityDescription)
        val q = ds.quality.single()
        assertEquals("rowCount", q.rule)
        assertEquals(listOf("mustBeGreaterThan" to "100"), q.thresholds.map { it.key to it.value })
        assertEquals("/schema/0/quality/0", q.pointer)
        val props = ds.properties.associateBy { it.name }
        val id = props.getValue("id")
        assertTrue(id.required && id.unique && id.primaryKey && id.criticalDataElement)
        assertEquals(1, id.primaryKeyPosition)
        assertEquals(listOf("format" to "uuid"), id.options.map { it.key to it.value })
        assertEquals("Never null", id.quality.single().description)
        assertEquals(listOf("8d1b..."), id.examples)
        val address = props.getValue("address")
        assertEquals("/schema/0/properties/1", address.pointer)
        assertEquals(listOf("city", "lines"), address.properties.map { it.name })
        assertTrue(address.properties[0].partitioned)
        assertEquals(1, address.properties[0].partitionKeyPosition)
        assertEquals("lines_enc", address.properties[1].items?.encryptedName)
        assertEquals("/schema/0/properties/1/properties/1/items", address.properties[1].items?.pointer)
        assertEquals("SUM(orders.amount)", props.getValue("total").transformLogic)
        assertEquals(listOf("orders"), props.getValue("total").transformSourceObjects)
        assertEquals(listOf("unit" to "EUR"), props.getValue("total").customProperties.map { it.key to it.value })
        val server = m.servers.single()
        assertEquals("postgres", server.type)
        assertEquals(listOf("host" to "db.example.com", "port" to "5432", "database" to "crm"), server.details.map { it.key to it.value })
        assertEquals("reader", server.roles.single().role)
        assertEquals("alice", m.team.single().username)
        assertEquals("alice", m.roles.single().firstLevelApprovers)
        assertEquals("updated_at", m.slaDefaultElement)
        assertEquals("4", m.slaProperties.single().value)
        assertEquals("h", m.slaProperties.single().unit)
        assertEquals("slack", m.support.single().tool)
        assertEquals("9.95", m.price?.priceAmount)
        assertEquals("privacy-statement", m.authoritativeDefinitions.single().type)
        assertEquals(listOf("refreshCadence" to "daily"), m.customProperties.map { it.key to it.value })
    }

    @Test
    fun `broken shapes render totally, the budget cuts nested properties`() {
        val broken = render("apiVersion: v3.1.0\nkind: DataContract\nschema: nope\nservers: [1]\nteam: { a: 1 }\ndescription: text\n")
        assertTrue(broken.datasets.isEmpty() && broken.servers.isEmpty() && broken.team.isEmpty())
        assertNull(broken.description)
        assertNull(broken.name)
        val small = render(RenderFixtures.odcsRich, RenderBudget(maxNodes = 2))
        val props = small.datasets.single().properties
        assertNull(props[0].marker)
        assertNull(props[1].marker)
        assertEquals(SchemaMarker.TRUNCATED, props[1].properties[0].marker, "the third node spends the budget")
        assertTrue(props[1].properties[0].properties.isEmpty())
        val plain = render(ContractFixtures.odcs)
        assertEquals("customer_view", plain.datasets.single().name)
        assertEquals("uuid", plain.datasets.single().properties.single().physicalType)
    }
}
