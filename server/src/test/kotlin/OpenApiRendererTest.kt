package ch.nokillswit

import ch.nokillswit.contracts.render.OpenApiRenderer
import ch.nokillswit.contracts.render.RenderBudget
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiRendererTest {
    private val yaml = ObjectMapper(YAMLFactory())

    private fun render(doc: String) = OpenApiRenderer(yaml.readTree(doc), RenderBudget()).render()

    @Test
    fun `info, servers, tags in declared order plus the undeclared, root security and schemes`() {
        val m = render(RenderFixtures.openApiRich30)
        assertEquals("Pets", m.info.title)
        assertEquals("api@example.com", m.info.contact?.email)
        assertEquals("MIT", m.info.license?.name)
        assertEquals("The handbook", m.externalDocs?.description)
        val server = m.servers.single()
        assertEquals("https://{env}.example.com/v2", server.url)
        assertEquals(listOf("api", "staging"), server.variables.single().enumValues)
        assertEquals(listOf("pets", "admin"), m.tags.map { it.name }, "declared first, then the operations' undeclared tag")
        assertEquals("Everything about pets", m.tags[0].description)
        assertEquals(listOf("bearer"), m.security.single().schemes.map { it.name })
        val schemes = m.securitySchemes.associateBy { it.name }
        assertEquals("JWT", schemes.getValue("bearer").bearerFormat)
        val flow = schemes.getValue("oauth").flows.single()
        assertEquals("authorizationCode", flow.type)
        assertEquals(listOf("read", "write"), flow.scopes.map { it.key })
        assertEquals(listOf("Pet", "Owner"), m.schemas.map { it.name })
        assertEquals("/components/schemas/Pet", m.schemas[0].pointer)
    }

    @Test
    fun `operations - merged parameters, ref'd request body, response and headers, security inherit vs none`() {
        val m = render(RenderFixtures.openApiRich30)
        assertEquals(listOf("GET /pets/{id}", "PUT /pets/{id}", "POST /untagged"), m.operations.map { "${it.method} ${it.path}" })
        val get = m.operations[0]
        assertEquals("/paths/~1pets~1{id}/get", get.pointer)
        assertEquals("getPet", get.operationId)
        assertTrue(get.deprecated)
        assertEquals(listOf("id" to "path", "verbose" to "query", "X-Tenant" to "header"), get.parameters.map { it.name to it.location })
        assertEquals(listOf("string"), get.parameters[0].schema?.types, "the operation's own id wins over the path-level one")
        assertEquals("/components/parameters/Tenant", get.parameters[2].pointer, "a ref'd parameter carries its component pointer")
        assertEquals("20", get.parameters[2].schema?.constraints?.single()?.value)
        assertEquals(listOf("true"), get.parameters[1].examples.map { it.value })
        assertEquals(true, get.parameters[1].explode)
        assertNull(get.security, "inherits the root")
        val found = get.responses.single { it.status == "200" }
        assertEquals("/components/responses/PetFound", found.pointer)
        assertEquals("The pet", found.description)
        assertEquals("X-Rate-Limit", found.headers.single().name)
        assertEquals(listOf("integer"), found.headers.single().schema?.types)
        assertEquals("#/components/schemas/Pet", found.content.single().schema?.ref)
        val put = m.operations[1]
        assertEquals(emptyList(), put.security, "explicitly none")
        val body = assertNotNull(put.requestBody)
        assertEquals("/components/requestBodies/NewPet", body.pointer)
        assertTrue(body.required)
        assertEquals("rex", body.content.single().examples.single().name)
        assertEquals("""{"id":1,"name":"Rex"}""", body.content.single().examples.single().value)
        assertEquals("One pet", put.summary, "the path item's summary is inherited")
        assertEquals(listOf("default"), put.responses.map { it.status })
        val post = m.operations[2]
        assertEquals("https://docs.example.com/untagged", post.externalDocs?.url)
        assertEquals(listOf("admin"), post.tags)
    }

    @Test
    fun `3-1 - webhooks render as operations, the tree stays total over broken shapes`() {
        val m = render(RenderFixtures.openApiRich31)
        assertEquals("A tree API", m.info.summary)
        assertEquals("POST", m.webhooks.single().method)
        assertEquals("nodeChanged", m.webhooks.single().path)
        assertEquals("/webhooks/nodeChanged/post", m.webhooks.single().pointer)
        assertEquals(listOf("Node"), m.schemas.map { it.name })
        val broken = render("openapi: 3.1.0\ninfo: [1]\npaths: [1, 2]\ncomponents: { schemas: 7 }\n")
        assertEquals("", broken.info.title)
        assertTrue(broken.operations.isEmpty() && broken.schemas.isEmpty())
        val brokenRef = render(ContractFixtures.openApiBrokenRef)
        assertEquals(1, brokenRef.operations.size)
    }
}
