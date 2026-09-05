package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.render.ContractRenderer
import ch.nokillswit.contracts.render.RenderBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractRendererTest {
    @Test
    fun `dispatches by type with the spec version, flags truncation, and answers HARD problems as an error model`() {
        val openApi = ContractRenderer.render(ContractType.OPENAPI, RenderFixtures.openApiRich30)
        assertEquals("3.0.3", openApi.specVersion)
        assertNotNull(openApi.openApi)
        assertNull(openApi.asyncApi)
        assertNull(openApi.error)
        assertTrue(!openApi.truncated)
        val asyncApi = ContractRenderer.render(ContractType.ASYNCAPI, RenderFixtures.asyncApi3Rich)
        assertEquals("3.0.0", asyncApi.specVersion)
        assertNotNull(asyncApi.asyncApi)
        val odcs = ContractRenderer.render(ContractType.ODCS, RenderFixtures.odcsRich)
        assertEquals("v3.1.0", odcs.specVersion)
        assertNotNull(odcs.odcs)
        val cut = ContractRenderer.render(ContractType.OPENAPI, RenderFixtures.openApiRich30, RenderBudget(maxNodes = 3))
        assertTrue(cut.truncated)
        assertNotNull(cut.openApi)
        val unparseable = ContractRenderer.render(ContractType.OPENAPI, "openapi: 3.1.0\ninfo: [oops\n")
        assertEquals(FindingSource.SYNTAX, unparseable.error?.source)
        assertNull(unparseable.openApi)
        val mismatch = ContractRenderer.render(ContractType.OPENAPI, ContractFixtures.asyncApi3)
        assertEquals("TYPE_MISMATCH", mismatch.error?.code)
        assertNull(mismatch.asyncApi)
    }
}
