package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Metadata
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.checks.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The HARD gate: format detection, parse failures with positions, the type gate, metadata extraction. */
class DocumentParserTest {

    @Test
    fun `format is detected from the first significant character`() {
        assertEquals(DocumentFormat.json, DocumentParser.detectFormat("  \n{\"openapi\": \"3.1.0\"}"))
        assertEquals(DocumentFormat.json, DocumentParser.detectFormat("[1]"))
        assertEquals(DocumentFormat.yaml, DocumentParser.detectFormat("openapi: 3.1.0\n"))
        assertEquals(DocumentFormat.yaml, DocumentParser.detectFormat(""))
    }

    @Test
    fun `a broken document yields ONE syntax finding with its position`() {
        val yaml = DocumentParser.parse("openapi: 3.1.0\ninfo: [unclosed\n")
        val failed = assertIs<ParseOutcome.Failed>(yaml)
        assertEquals(Severity.ERROR, failed.finding.severity)
        assertEquals(FindingSource.SYNTAX, failed.finding.source)
        assertTrue(failed.finding.hard)
        assertNotNull(failed.finding.line)
        val json = assertIs<ParseOutcome.Failed>(DocumentParser.parse("{\"openapi\": "))
        assertNotNull(json.finding.line)
        assertIs<ParseOutcome.Failed>(DocumentParser.parse("   "))
        val scalar = assertIs<ParseOutcome.Failed>(DocumentParser.parse("just a string"))
        assertTrue(scalar.finding.message.contains("root must be an object"))
        assertIs<ParseOutcome.Failed>(DocumentParser.parse("- a\n- b\n"))
    }

    @Test
    fun `an alias bomb stays linear - Jackson's event parser never expands aliases`() {
        val bomb = buildString {
            appendLine("a: &a [x, x, x, x, x, x, x, x, x, x]")
            var prev = 'a'
            for (c in 'b'..'k') {
                appendLine("$c: &$c [*$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev]")
                prev = c
            }
        }
        val started = System.nanoTime()
        DocumentParser.parse(bomb) // whichever outcome — it must return promptly without expanding 10^11 nodes
        assertTrue((System.nanoTime() - started) < 5_000_000_000L, "an alias bomb must not be expanded")
    }

    @Test
    fun `the type gate matches each standard's root and refuses Swagger 2`() {
        fun root(text: String) = assertIs<ParseOutcome.Parsed>(DocumentParser.parse(text)).root
        assertNull(DocumentParser.typeGate(ContractType.OPENAPI, root("openapi: 3.0.3\ninfo: {title: T, version: '1'}\npaths: {}")))
        assertEquals(
            "UNSUPPORTED_SPEC_VERSION",
            DocumentParser.typeGate(ContractType.OPENAPI, root("swagger: '2.0'\ninfo: {title: T}"))?.code,
        )
        assertEquals("UNSUPPORTED_SPEC_VERSION", DocumentParser.typeGate(ContractType.OPENAPI, root("openapi: 4.0.0"))?.code)
        assertEquals("TYPE_MISMATCH", DocumentParser.typeGate(ContractType.OPENAPI, root("asyncapi: 3.0.0"))?.code)
        assertNull(DocumentParser.typeGate(ContractType.ASYNCAPI, root("asyncapi: 3.0.0\ninfo: {title: T, version: '1'}")))
        assertEquals("TYPE_MISMATCH", DocumentParser.typeGate(ContractType.ASYNCAPI, root("openapi: 3.1.0"))?.code)
        assertNull(
            DocumentParser.typeGate(
                ContractType.ODCS,
                root("apiVersion: v3.1.0\nkind: DataContract\nid: x\nversion: 1.0.0\nstatus: active"),
            ),
        )
        assertEquals("TYPE_MISMATCH", DocumentParser.typeGate(ContractType.ODCS, root("apiVersion: v3.1.0\nkind: Other"))?.code)
        assertEquals("TYPE_MISMATCH", DocumentParser.typeGate(ContractType.ODCS, root("kind: DataContract"))?.code)
    }

    @Test
    fun `metadata comes from each standard's own fields and is clipped to the column widths`() {
        fun root(text: String) = assertIs<ParseOutcome.Parsed>(DocumentParser.parse(text)).root
        val oas = Metadata.extract(
            ContractType.OPENAPI,
            root("openapi: 3.1.0\ninfo:\n  title: Petstore\n  version: 2.1.0\n  description: pets\n"),
        )
        assertEquals("3.1.0", oas.specVersion)
        assertEquals("Petstore", oas.title)
        assertEquals("pets", oas.description)
        assertEquals("2.1.0", Metadata.declaredVersion(ContractType.OPENAPI, root("openapi: 3.1.0\ninfo: {title: T, version: 2.1.0}")))
        val odcs = Metadata.extract(
            ContractType.ODCS,
            root("apiVersion: v3.1.0\nkind: DataContract\nid: abc\nversion: 1.0.0\nstatus: active\ndescription:\n  usage: read me\n"),
        )
        assertEquals("v3.1.0", odcs.specVersion)
        assertEquals("abc", odcs.title, "falls back to id without a name")
        assertEquals("read me", odcs.description, "purpose first, usage as the fallback")
        assertEquals("active", Metadata.declaredStatus(ContractType.ODCS, root("apiVersion: v3.1.0\nkind: DataContract\nstatus: active")))
        val long = Metadata.extract(ContractType.ASYNCAPI, root("asyncapi: 3.0.0\ninfo:\n  title: ${"t".repeat(300)}\n  version: '1'\n"))
        assertEquals(Metadata.MAX_TITLE_LENGTH, long.title!!.length)
        assertNull(Metadata.extract(ContractType.OPENAPI, root("openapi: 3.1.0\ninfo: {}")).title)
    }
}
