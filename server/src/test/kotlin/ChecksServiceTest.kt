package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.SemVer
import ch.nokillswit.contracts.checks.Baseline
import ch.nokillswit.contracts.checks.BreakingChanges
import ch.nokillswit.contracts.checks.CheckerClient
import ch.nokillswit.contracts.checks.CheckerResponse
import ch.nokillswit.contracts.checks.CheckerUnavailableException
import ch.nokillswit.contracts.checks.ChecksService
import ch.nokillswit.contracts.checks.CompatibilityVerdict
import ch.nokillswit.contracts.checks.DocumentFormat
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orchestration: HARD short-circuits, the merge with the checker, fail-soft, the cross-checks. */
class ChecksServiceTest {

    private class StubChecker(private val findings: List<Finding> = emptyList(), private val fail: Boolean = false) : CheckerClient {
        var calls = 0
        override suspend fun check(type: ContractType, content: String, previousContent: String?): CheckerResponse {
            calls++
            if (fail) throw CheckerUnavailableException("boom")
            return CheckerResponse(findings)
        }
    }

    private val lint = Finding(Severity.WARN, FindingSource.LINT, "info-contact", "Info object must have contact", "/info", 2, 1)

    @Test
    fun `a clean document merges the checker's findings and reads the metadata`() = runBlocking {
        val checker = StubChecker(listOf(lint))
        val report = ChecksService(checker).check(ContractType.OPENAPI, ContractFixtures.openApi, declaredVersion = "1.0.0")
        assertEquals(DocumentFormat.yaml, report.format)
        assertEquals("Petstore", report.title)
        assertEquals("3.1.0", report.specVersion)
        assertEquals(listOf(lint), report.findings)
        assertEquals(1, report.warnings)
        assertTrue(report.checkerAvailable)
        assertTrue(report.hardFindings.isEmpty() && report.softErrors.isEmpty())
        assertEquals(1, checker.calls)
    }

    @Test
    fun `unparseable text and a type mismatch are HARD and stop the pipeline before the checker`() = runBlocking {
        val checker = StubChecker(listOf(lint))
        val syntax = ChecksService(checker).check(ContractType.OPENAPI, "openapi: 3.1.0\ninfo: [oops\n")
        assertEquals(1, syntax.findings.size)
        assertTrue(syntax.hardFindings.single().hard)
        val mismatch = ChecksService(checker).check(ContractType.ASYNCAPI, ContractFixtures.openApi)
        assertEquals("TYPE_MISMATCH", mismatch.hardFindings.single().code)
        assertEquals(0, checker.calls, "no checker call on a HARD failure")
    }

    @Test
    fun `unsupported AsyncAPI and ODCS versions remain soft schema findings`() = runBlocking {
        val asyncApi = ChecksService(StubChecker()).check(
            ContractType.ASYNCAPI,
            ContractFixtures.asyncApi3.replace("asyncapi: 3.0.0", "asyncapi: 3.0.1"),
        )
        val asyncFinding = asyncApi.findings.single { it.code == "UNSUPPORTED_SPEC_VERSION" }
        assertEquals(FindingSource.SCHEMA, asyncFinding.source)
        assertFalse(asyncFinding.hard)

        val odcs = ChecksService(StubChecker()).check(ContractType.ODCS, ContractFixtures.odcsOldVersion)
        val odcsFinding = odcs.findings.single { it.code == "UNSUPPORTED_SPEC_VERSION" }
        assertEquals(FindingSource.SCHEMA, odcsFinding.source)
        assertFalse(odcsFinding.hard)
    }

    @Test
    fun `an unavailable checker degrades to one SYSTEM warning and flags the report`() = runBlocking {
        val report = ChecksService(StubChecker(fail = true)).check(ContractType.ASYNCAPI, ContractFixtures.asyncApi3)
        assertFalse(report.checkerAvailable)
        val system = report.findings.single { it.source == FindingSource.SYSTEM }
        assertEquals(ChecksService.CODE_CHECKER_UNAVAILABLE, system.code)
        assertEquals(Severity.WARN, system.severity)
        assertTrue(report.softErrors.isEmpty(), "a missing checker is never a blocking error")
    }

    @Test
    fun `ODCS never calls the checker - the JVM schema pass is the gate`() = runBlocking {
        val checker = StubChecker(fail = true)
        val ok = ChecksService(
            checker,
        ).check(ContractType.ODCS, ContractFixtures.odcs, declaredVersion = "1.0.0", lifecycle = Lifecycle.ACTIVE)
        assertEquals(emptyList(), ok.findings)
        assertTrue(ok.checkerAvailable)
        val broken = ChecksService(checker).check(ContractType.ODCS, ContractFixtures.odcsBroken)
        assertTrue(broken.softErrors.isNotEmpty())
        assertEquals(0, checker.calls)
    }

    @Test
    fun `the cross-checks are INFO - declared version vs stored, ODCS status vs lifecycle`() = runBlocking {
        val service = ChecksService(StubChecker())
        val versioned = service.check(ContractType.OPENAPI, ContractFixtures.openApi, declaredVersion = "1.1.0")
        assertEquals(ChecksService.CODE_VERSION_MISMATCH, versioned.findings.single().code)
        assertEquals(Severity.INFO, versioned.findings.single().severity)
        assertEquals("/info/version", versioned.findings.single().path)
        val same = service.check(ContractType.OPENAPI, ContractFixtures.openApi, declaredVersion = "1.0.0+build.7")
        assertEquals(emptyList(), same.findings, "build metadata does not make a mismatch")
        val status = service.check(ContractType.ODCS, ContractFixtures.odcs, declaredVersion = "1.0.0", lifecycle = Lifecycle.DRAFT)
        assertEquals(ChecksService.CODE_STATUS_MISMATCH, status.findings.single().code)
    }

    @Test
    fun `findings are sorted and the truncation marker occupies one store-cap slot`() = runBlocking {
        val many = (1..600).map { Finding(Severity.WARN, FindingSource.LINT, "w$it", "w", null, 601 - it, 1) } +
            Finding(Severity.ERROR, FindingSource.LINT, "e", "e", null, 999, 1)
        val report = ChecksService(StubChecker(many)).check(ContractType.OPENAPI, ContractFixtures.openApi)
        assertEquals("e", report.findings.first().code, "ERROR sorts first regardless of line")
        assertEquals(500, report.findings.size)
        assertEquals("FINDINGS_TRUNCATED", report.findings.last().code)
        assertEquals(
            "102 further findings were not stored (cap 500, including this marker)",
            report.findings.last().message,
        )
        assertEquals(1, report.errors)
    }

    @Test
    fun `exactly the store cap needs no truncation marker`() = runBlocking {
        val exact = (1..500).map { Finding(Severity.WARN, FindingSource.LINT, "w$it", "w", null, it, 1) }
        val report = ChecksService(StubChecker(exact)).check(ContractType.OPENAPI, ContractFixtures.openApi)
        assertEquals(500, report.findings.size)
        assertTrue(report.findings.none { it.code == "FINDINGS_TRUNCATED" })
    }

    @Test
    fun `the store cap preserves evidence that the checker was already truncated`() = runBlocking {
        val checkerFindings = (1..499).map {
            Finding(Severity.WARN, FindingSource.LINT, "w$it", "w", null, it, 1)
        } + Finding(
            Severity.INFO,
            FindingSource.SYSTEM,
            "findings-truncated",
            "8 further findings were not returned (cap 500, including this marker)",
        )
        val report = ChecksService(StubChecker(checkerFindings)).check(
            ContractType.OPENAPI,
            ContractFixtures.openApi,
            declaredVersion = "2.0.0",
        )
        assertEquals(500, report.findings.size)
        val marker = report.findings.single { it.code == "FINDINGS_TRUNCATED" }
        assertTrue(marker.message.contains("earlier truncation also omitted findings"))
    }

    @Test
    fun `lifecycle refresh cannot grow a capped stored snapshot`() {
        val stored = (1..499).map {
            Finding(Severity.WARN, FindingSource.SCHEMA, "w$it", "w", null, it, 1)
        } + Finding(
            Severity.INFO,
            FindingSource.SYSTEM,
            "FINDINGS_TRUNCATED",
            "10 further findings were not stored (cap 500, including this marker)",
        )
        val refreshed = ChecksService(StubChecker()).refreshLifecycleFinding(
            ContractType.ODCS,
            ContractFixtures.odcs,
            Lifecycle.DRAFT,
            stored,
        )
        assertEquals(500, refreshed.size)
        assertEquals(1, refreshed.count { it.code == "FINDINGS_TRUNCATED" })
        assertTrue(refreshed.last().message.contains("earlier truncation also omitted findings"))
    }

    @Test
    fun `an incomplete AsyncAPI baseline diff neither blocks a save nor claims full compatibility`() = runBlocking {
        val skip = Finding(
            Severity.INFO,
            FindingSource.BREAKING,
            BreakingChanges.CODE_ASYNCAPI_DIFF_SKIPPED,
            "Breaking changes could not be computed — the previous document contains external references",
        )
        val service = ChecksService(StubChecker(listOf(skip)))
        val baseline = Baseline(SemVer.parse("1.0.0"), ContractFixtures.asyncApi3)

        val report = service.check(
            ContractType.ASYNCAPI,
            ContractFixtures.asyncApi3.replace("version: 1.0.0", "version: 1.1.0"),
            declaredVersion = "1.1.0",
            baseline = baseline,
        )
        assertTrue(report.softErrors.isEmpty())
        assertTrue(report.findings.any { it.code == BreakingChanges.CODE_ASYNCAPI_DIFF_SKIPPED })

        val compatibility = service.compatibility(
            ContractType.ASYNCAPI,
            baseline,
            SemVer.parse("1.1.0"),
            ContractFixtures.asyncApi3.replace("version: 1.0.0", "version: 1.1.0"),
        )
        assertEquals(CompatibilityVerdict.UNKNOWN, compatibility.verdict)
        assertEquals(null, compatibility.backward.compatible)
        assertEquals(null, compatibility.forward.compatible)
    }

    @Test
    fun `identical AsyncAPI text still checks baseline reference coverage`() = runBlocking {
        val skip = Finding(
            Severity.INFO,
            FindingSource.BREAKING,
            BreakingChanges.CODE_ASYNCAPI_DIFF_SKIPPED,
            "Breaking changes could not be computed — the previous document contains external references",
        )
        val checker = StubChecker(listOf(skip))
        val content = ContractFixtures.asyncApi3
        val compatibility = ChecksService(checker).compatibility(
            ContractType.ASYNCAPI,
            Baseline(SemVer.parse("1.0.0"), content),
            SemVer.parse("1.0.0"),
            content,
        )

        assertEquals(CompatibilityVerdict.UNKNOWN, compatibility.verdict)
        assertEquals(null, compatibility.backward.compatible)
        assertEquals(null, compatibility.forward.compatible)
        assertEquals(2, checker.calls)
    }

    @Test
    fun `truncated checker results never prove full AsyncAPI compatibility`() = runBlocking {
        val checker = StubChecker(listOf(
            Finding(Severity.ERROR, FindingSource.SCHEMA, "external-ref-not-allowed", "unresolved reference"),
            Finding(Severity.INFO, FindingSource.SYSTEM, "findings-truncated", "more findings were not returned"),
        ))
        val content = ContractFixtures.asyncApi3
        val compatibility = ChecksService(checker).compatibility(
            ContractType.ASYNCAPI,
            Baseline(SemVer.parse("1.0.0"), content),
            SemVer.parse("1.0.0"),
            content,
        )

        assertEquals(CompatibilityVerdict.UNKNOWN, compatibility.verdict)
        assertEquals(null, compatibility.backward.compatible)
        assertEquals(null, compatibility.forward.compatible)
        assertTrue(compatibility.backward.findings.any { it.code == BreakingChanges.CODE_SKIPPED })
    }
}
