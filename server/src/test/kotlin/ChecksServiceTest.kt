package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.checks.CheckerClient
import ch.nokillswit.contracts.checks.CheckerResponse
import ch.nokillswit.contracts.checks.CheckerUnavailableException
import ch.nokillswit.contracts.checks.ChecksService
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
    fun `findings are sorted by severity then line and the store cap appends a marker`() = runBlocking {
        val many = (1..600).map { Finding(Severity.WARN, FindingSource.LINT, "w$it", "w", null, 601 - it, 1) } +
            Finding(Severity.ERROR, FindingSource.LINT, "e", "e", null, 999, 1)
        val report = ChecksService(StubChecker(many)).check(ContractType.OPENAPI, ContractFixtures.openApi)
        assertEquals("e", report.findings.first().code, "ERROR sorts first regardless of line")
        assertEquals(501, report.findings.size)
        assertEquals("FINDINGS_TRUNCATED", report.findings.last().code)
        assertEquals(1, report.errors)
    }
}
