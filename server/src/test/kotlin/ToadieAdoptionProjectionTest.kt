package ch.nokillswit

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.toadie.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToadieAdoptionProjectionTest {
    private val cache = ToadieCacheStatus(ToadieCacheState.CURRENT, 1, 1, false, null)
    private val connection = ToadieConnectionRef(1u, "Toadie", "https://toadie.example")

    @Test
    fun `pure projection covers consumption match and unavailable states`() {
        val mapping = ToadieAdoptionMapping(environmentRelation = null)
        val usage = ToadieMapping()
        val target = ToadieEntitySnapshot("1", "api", "orders", "Orders", emptyList(), emptyMap(), 1)
        val consumer = ToadieEntitySnapshot(
            "2", "service", "checkout", "Checkout", emptyList(),
            mapOf("consumes_apis" to listOf("orders")), 1,
        )
        val adoption = ToadieEntitySnapshot(
            "3", "api_adoption", "checkout-orders", "Checkout Orders", emptyList(),
            mapOf("consumer" to listOf("checkout"), "api" to listOf("orders")), 1,
            scalarProperties = mapOf("major_line" to "v2"),
        )
        val projected = projectAdoptions(
            ToadieAdoptionAvailability.AVAILABLE, mapping, usage, connection.browserUrl, null,
            listOf(target, consumer, adoption), setOf("orders"),
        )
        assertEquals(1, projected.items.size)
        assertTrue(projected.items.single().matchesConsumption)
        assertEquals(ToadieAdoptionEnvironmentScope.UNKNOWN, projected.items.single().environmentScope)
        assertEquals("v2", projected.items.single().value)
        assertTrue(projectAdoptions(
            ToadieAdoptionAvailability.NOT_CONFIGURED, null, usage, connection.browserUrl, null,
            emptyList(), emptySet(),
        ).items.isEmpty())
        assertTrue(projectAdoptions(
            ToadieAdoptionAvailability.AVAILABLE, null, usage, connection.browserUrl, null,
            emptyList(), emptySet(),
        ).items.isEmpty())
        assertTrue(projectAdoptions(
            ToadieAdoptionAvailability.AVAILABLE, mapping, usage, connection.browserUrl, null,
            listOf(target, consumer, adoption), setOf("other"),
        ).items.isEmpty())
    }

    @Test
    fun `adoption page searches every displayed identity and handles sort directions and distant pages`() {
        val first = row("10", "alpha", "Alpha", "consumer-a", "Consumer A", "target-a", "Target A")
        val second = row("2", "beta", "Beta", "consumer-b", "Consumer B", "target-b", "Target B")
        val projection = ToadieFullUsageProjection(
            connection, cache, emptyList(), emptyList(),
            ToadieAdoptionsProjection(ToadieAdoptionAvailability.AVAILABLE, listOf(first, second)),
        )
        fun page(query: String?, page: Int = 1, sort: SortField) = adoptionPage(
            projection, query, PageRequest(page, 20, listOf(sort)),
        )

        assertEquals(listOf("2", "10"), page(null, sort = SortField("id", false)).items.map { it.id })
        assertEquals(listOf("2", "10"), page(null, sort = SortField("title", true)).items.map { it.id })
        assertEquals("10", page("alpha", sort = SortField("id", false)).items.single().id)
        assertEquals("10", page("consumer a", sort = SortField("id", false)).items.single().id)
        assertEquals("2", page("target-b", sort = SortField("id", false)).items.single().id)
        assertTrue(page("missing", sort = SortField("id", false)).items.isEmpty())
        assertTrue(page(null, page = Int.MAX_VALUE, sort = SortField("id", false)).items.isEmpty())
        assertFalse(page("  ", sort = SortField("id", false)).items.isEmpty())
    }

    private fun row(
        id: String,
        identifier: String,
        title: String,
        consumerIdentifier: String,
        consumerTitle: String,
        targetIdentifier: String,
        targetTitle: String,
    ) = ToadieAdoptionRow(
        id, identifier, title, null,
        ToadieEntityRef("c-$id", consumerIdentifier, consumerTitle, null),
        ToadieEntityRef("t-$id", targetIdentifier, targetTitle, null),
        null, ToadieAdoptionEnvironmentScope.ALL, ToadieAdoptionKind.API_MAJOR_LINE,
        null, null, null, null, null, false,
    )
}
