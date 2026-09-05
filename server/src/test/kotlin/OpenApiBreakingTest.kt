package ch.nokillswit

import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.OpenApiBreaking
import ch.nokillswit.contracts.checks.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/** `replace` that refuses a missing target — a fixture edit that silently no-ops would test nothing. */
private fun String.edit(target: String, replacement: String): String {
    check(contains(target)) { "fixture edit target missing: $target" }
    return replace(target, replacement)
}

/** The OpenAPI breaking-change walker over openapi-diff-core: one BREAKING fact per incompatible element. */
class OpenApiBreakingTest {

    private val base = """
        openapi: 3.0.3
        info: { title: Pets, version: 1.0.0 }
        paths:
          /pets:
            get:
              operationId: listPets
              parameters:
                - name: limit
                  in: query
                  schema: { type: integer }
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: array
                        items: { ${'$'}ref: "#/components/schemas/Pet" }
            post:
              operationId: createPet
              requestBody:
                content:
                  application/json:
                    schema: { ${'$'}ref: "#/components/schemas/NewPet" }
              responses:
                "201": { description: created }
          /pets/{id}:
            delete:
              operationId: deletePet
              parameters:
                - { name: id, in: path, required: true, schema: { type: string } }
              responses:
                "204": { description: gone }
        components:
          schemas:
            Pet:
              type: object
              required: [id, name]
              properties:
                id: { type: integer }
                name: { type: string }
                tag: { type: string }
            NewPet:
              type: object
              required: [name]
              properties:
                name: { type: string }
                tag: { type: string }
    """.trimIndent()

    /** `Pet.name` + `Pet.tag` as written above, and the same slice with the REQUIRED `name` gone. */
    private val petNameAndTag = "        name: { type: string }\n        tag: { type: string }\n    NewPet"
    private val petTagOnly = "        tag: { type: string }\n    NewPet"

    private fun codes(new: String) = OpenApiBreaking.compare(base, new).also { assertNotNull(it) }!!

    @Test
    fun `identical and additive documents have no breaking facts`() {
        assertEquals(emptyList(), codes(base))
        val additive = base
            .edit(
                "        tag: { type: string }\n    NewPet",
                "        tag: { type: string }\n        colour: { type: string }\n    NewPet",
            )
            .edit("      responses:\n        \"204\"", "      responses:\n        \"404\": { description: unknown }\n        \"204\"")
        assertEquals(emptyList(), codes(additive), "a new optional property and a new response code are compatible")
    }

    @Test
    fun `a removed operation is one REMOVED_OPERATION at the operation's pointer`() {
        check(base.contains("  /pets/{id}:"))
        val without = base.substringBefore("  /pets/{id}:") + base.substring(base.indexOf("components:"))
        val facts = codes(without)
        assertEquals(1, facts.size, facts.toString())
        val f = facts.single()
        assertEquals(OpenApiBreaking.CODE_REMOVED_OPERATION, f.code)
        assertEquals(FindingSource.BREAKING, f.source)
        assertEquals(Severity.WARN, f.severity, "facts are minted WARN; BreakingChanges.settle decides")
        assertEquals("/paths/~1pets~1{id}/delete", f.path)
        assertTrue(f.message.contains("DELETE /pets/{id}"), f.message)
    }

    @Test
    fun `parameters - removed, newly required and narrowed are each a fact`() {
        val removed = base.edit("      parameters:\n        - name: limit\n          in: query\n          schema: { type: integer }\n", "")
        assertEquals(listOf(OpenApiBreaking.CODE_REMOVED_PARAMETER), codes(removed).map { it.code })
        val required = base.edit("          in: query\n", "          in: query\n          required: true\n")
        val f = codes(required).single()
        assertEquals(OpenApiBreaking.CODE_CHANGED_PARAMETER, f.code)
        assertTrue(f.message.contains("is now required"), f.message)
        val added = base.edit(
            "          schema: { type: integer }\n",
            "          schema: { type: integer }\n        - { name: tenant, in: header, required: true, schema: { type: string } }\n",
        )
        assertEquals(listOf(OpenApiBreaking.CODE_NEW_REQUIRED_PARAMETER), codes(added).map { it.code })
    }

    @Test
    fun `a required response property removed is CHANGED_RESPONSE naming it, a removed status is REMOVED_RESPONSE`() {
        // openapi-diff's rule: dropping an OPTIONAL response property is compatible, a REQUIRED one is not.
        val optionalGone = base.edit(petNameAndTag, "        name: { type: string }\n    NewPet")
        assertEquals(emptyList(), codes(optionalGone))
        val narrowed = base.edit(petNameAndTag, petTagOnly)
        val f = codes(narrowed).single()
        assertEquals(OpenApiBreaking.CODE_CHANGED_RESPONSE, f.code)
        assertEquals("/paths/~1pets/get/responses/200", f.path)
        assertTrue(f.message.contains("'items.name' was removed"), f.message)
        val gone = base.edit("        \"201\": { description: created }", "        \"200\": { description: created }")
        assertTrue(codes(gone).any { it.code == OpenApiBreaking.CODE_REMOVED_RESPONSE && it.message.contains("response 201") })
    }

    @Test
    fun `a request property newly required is CHANGED_REQUEST_BODY`() {
        val stricter = base.edit("      required: [name]\n", "      required: [name, tag]\n")
        val f = codes(stricter).single()
        assertEquals(OpenApiBreaking.CODE_CHANGED_REQUEST_BODY, f.code)
        assertEquals("/paths/~1pets/post/requestBody", f.path)
        assertTrue(f.message.contains("'tag' is now required"), f.message)
    }

    @Test
    fun `OpenAPI 3-1 documents compare through the 3-0 model, YAML and JSON roots alike`() {
        val base31 = base.edit("openapi: 3.0.3", "openapi: 3.1.0")
        val narrowed31 = base31.edit(petNameAndTag, petTagOnly)
        assertEquals(listOf(OpenApiBreaking.CODE_CHANGED_RESPONSE), OpenApiBreaking.compare(base31, narrowed31)!!.map { it.code })
        val operation = """{"/a": {"get": {"responses": {"200": {"description": "ok"}}}}}"""
        val json = """{"openapi": "3.1.0", "info": {"title": "J", "version": "1"}, "paths": $operation}"""
        val jsonWithout = json.replace(operation, "{}")
        assertEquals(listOf(OpenApiBreaking.CODE_REMOVED_OPERATION), OpenApiBreaking.compare(json, jsonWithout)!!.map { it.code })
    }

    @Test
    fun `an unreadable side yields null - the caller reports the skip`() {
        assertNull(OpenApiBreaking.compare("not: openapi", base))
        assertNull(OpenApiBreaking.compare(base, "openapi: 3.0.3\ninfo: [oops\n"))
    }
}
