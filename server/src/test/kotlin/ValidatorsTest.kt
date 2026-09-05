package ch.nokillswit

import ch.nokillswit.contracts.checks.AsyncApiValidator
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.OdcsValidator
import ch.nokillswit.contracts.checks.OpenApiValidator
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.checks.VendoredSchemas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The three JVM validators against the fixtures — offline, against the vendored schemas. */
class ValidatorsTest {

    private fun root(text: String) = assertIs<ParseOutcome.Parsed>(DocumentParser.parse(text)).root

    @Test
    fun `OpenAPI - a clean document has no findings, a dangling ref is a SEMANTIC error with a pointer`() {
        assertEquals(emptyList(), OpenApiValidator.validate(ContractFixtures.openApi))
        assertEquals(emptyList(), OpenApiValidator.validate(ContractFixtures.openApiJson))
        val findings = OpenApiValidator.validate(ContractFixtures.openApiBrokenRef)
        assertTrue(findings.isNotEmpty(), "the dangling ref must be flagged")
        assertTrue(
            findings.all { it.severity == Severity.ERROR && it.source == FindingSource.SEMANTIC && it.code == OpenApiValidator.CODE_PARSE },
        )
        assertTrue(findings.any { it.message.contains("Missing") }, findings.toString())
    }

    @Test
    fun `AsyncAPI - 3_0 and 2_6 documents validate, structural breaks and bad payloads are SCHEMA errors`() {
        assertEquals(emptyList(), AsyncApiValidator.validate(root(ContractFixtures.asyncApi3)))
        assertEquals(emptyList(), AsyncApiValidator.validate(root(ContractFixtures.asyncApi2)))
        val broken = AsyncApiValidator.validate(root(ContractFixtures.asyncApiBroken))
        assertTrue(broken.any { it.code == AsyncApiValidator.CODE_SCHEMA && it.path == "/channels" }, broken.toString())
        val badPayload = AsyncApiValidator.validate(root(ContractFixtures.asyncApiBadPayload))
        assertTrue(
            badPayload.any { it.code == AsyncApiValidator.CODE_PAYLOAD_SCHEMA && it.path!!.startsWith("/channels/c/messages/m/payload") },
            badPayload.toString(),
        )
        val unsupported = AsyncApiValidator.validate(root("asyncapi: 2.0.0\ninfo: {title: T, version: '1'}\nchannels: {}"))
        assertEquals(AsyncApiValidator.CODE_UNSUPPORTED, unsupported.single().code)
    }

    @Test
    fun `AsyncAPI - Avro payloads parse with Apache Avro`() {
        assertEquals(emptyList(), AsyncApiValidator.validate(root(ContractFixtures.asyncApiAvro)))
        // The AsyncAPI 3 schema knows the Avro grammar too, so the document-level pass may flag the
        // bogus type as well — Apache Avro's own verdict is the one pinned here.
        val bad = AsyncApiValidator.validate(root(ContractFixtures.asyncApiBadAvro))
        val avro = bad.single { it.code == AsyncApiValidator.CODE_AVRO }
        assertEquals("/channels/orderPlaced/messages/orderPlaced/payload/schema", avro.path)
    }

    @Test
    fun `ODCS - a valid contract passes, a required violation is a SCHEMA error, v2 is unsupported`() {
        assertEquals(emptyList(), OdcsValidator.validate(root(ContractFixtures.odcs)))
        // ODCS 3.x keeps `status` a free string (no enum) — the missing required `id` is the schema violation.
        val broken = OdcsValidator.validate(root(ContractFixtures.odcsBroken))
        assertTrue(broken.any { it.code == OdcsValidator.CODE_SCHEMA && it.message.contains("'id'") }, broken.toString())
        assertEquals(OdcsValidator.CODE_UNSUPPORTED, OdcsValidator.validate(root(ContractFixtures.odcsOldVersion)).single().code)
    }

    @Test
    fun `networknt instance locations become JSON pointers`() {
        assertEquals(null, VendoredSchemas.pointerOf("$"))
        assertEquals(null, VendoredSchemas.pointerOf(""))
        assertEquals("/channels/foo/2", VendoredSchemas.pointerOf("/channels/foo/2"), "2.x already prints pointers")
        assertEquals("/channels", VendoredSchemas.pointerOf("$.channels"))
        assertEquals("/channels/foo/2/bar", VendoredSchemas.pointerOf("$.channels.foo[2].bar"))
        assertEquals("/a~1b/c", VendoredSchemas.pointerOf("$.a/b.c"))
    }
}
