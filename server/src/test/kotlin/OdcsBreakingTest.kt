package ch.nokillswit

import ch.nokillswit.contracts.checks.OdcsBreaking
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/** `replace` that refuses a missing target — a fixture edit that silently no-ops would test nothing. */
private fun String.edit(target: String, replacement: String): String {
    check(contains(target)) { "fixture edit target missing: $target" }
    return replace(target, replacement)
}

/** The hand-written ODCS comparer: datasets/properties/servers by name, the MAJOR rules of contract-standards.md. */
class OdcsBreakingTest {

    private val base = """
        apiVersion: v3.1.0
        kind: DataContract
        id: c1
        version: 1.0.0
        status: active
        schema:
          - name: orders
            physicalType: view
            properties:
              - name: order_id
                logicalType: string
                physicalType: uuid
                required: true
              - name: total
                logicalType: number
                physicalType: numeric
              - name: shipping
                logicalType: object
                properties:
                  - name: street
                    logicalType: string
          - name: customers
            physicalType: view
            properties:
              - name: customer_id
                logicalType: string
        servers:
          - server: prod
            type: postgres
          - server: staging
            type: postgres
    """.trimIndent()

    private fun root(text: String) = (DocumentParser.parse(text) as ParseOutcome.Parsed).root
    private fun facts(new: String) = OdcsBreaking.compare(root(base), root(new))

    @Test
    fun `identical, reordered and additive documents have no facts`() {
        assertEquals(emptyList(), facts(base))
        val reordered = base.edit("  - name: orders", "  - name: zzz\n    properties: []\n  - name: orders")
        assertEquals(emptyList(), facts(reordered), "a new dataset ahead of the others moves indexes, not identities")
        val optional = base.edit("      - name: total", "      - name: note\n        logicalType: string\n      - name: total")
        assertEquals(emptyList(), facts(optional))
    }

    @Test
    fun `removed dataset, property and server`() {
        check(base.contains("  - name: customers"))
        val noCustomers = base.substringBefore("  - name: customers") + base.substring(base.indexOf("servers:"))
        assertEquals(listOf(OdcsBreaking.CODE_REMOVED_DATASET to "/schema"), facts(noCustomers).map { it.code to it.path })
        val noTotal = base.edit("      - name: total\n        logicalType: number\n        physicalType: numeric\n", "")
        val f = facts(noTotal).single()
        assertEquals(OdcsBreaking.CODE_REMOVED_PROPERTY, f.code)
        assertEquals("/schema/0/properties", f.path)
        assertTrue(f.message.contains("'total'") && f.message.contains("'orders'"), f.message)
        val noStaging = base.edit("\n  - server: staging\n    type: postgres", "")
        assertEquals(listOf(OdcsBreaking.CODE_REMOVED_SERVER), facts(noStaging).map { it.code })
    }

    @Test
    fun `newly required and changed types point at the candidate's field, nested properties included`() {
        val required = base.edit("        physicalType: numeric\n", "        physicalType: numeric\n        required: true\n")
        assertEquals(
            listOf(OdcsBreaking.CODE_PROPERTY_NOW_REQUIRED to "/schema/0/properties/1/required"),
            facts(required).map { it.code to it.path },
        )
        val retyped = base.edit("        logicalType: number", "        logicalType: string")
        assertEquals(
            listOf(OdcsBreaking.CODE_CHANGED_LOGICAL_TYPE to "/schema/0/properties/1/logicalType"),
            facts(retyped).map { it.code to it.path },
        )
        val physical = base.edit("        physicalType: uuid", "        physicalType: text")
        assertEquals(listOf(OdcsBreaking.CODE_CHANGED_PHYSICAL_TYPE), facts(physical).map { it.code })
        val nested = base.edit("          - name: street\n            logicalType: string\n", "")
        val f = facts(nested).single()
        assertEquals(OdcsBreaking.CODE_REMOVED_PROPERTY, f.code)
        assertEquals("/schema/0/properties/2/properties", f.path)
        assertTrue(f.message.contains("'street'"), f.message)
    }
}
