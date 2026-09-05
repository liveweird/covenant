package ch.nokillswit.contracts.checks

import ch.nokillswit.audit.audit
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.SemVer
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

val ChecksServiceKey = AttributeKey<ChecksService>("ChecksService")

/**
 * The check orchestration — one pipeline for every store path and the live `POST …/check`:
 *
 * 1. parse (format + Jackson tree) → a failure is the ONE HARD finding, stop;
 * 2. the type gate (the declared standard matches the contract's type) → HARD, stop;
 * 3. metadata extraction (title, spec version, the document's own version/status);
 * 4. the JVM validator for the type (SOFT: SCHEMA/SEMANTIC);
 * 5. the checker sidecar for OPENAPI/ASYNCAPI (SOFT: LINT/SEMANTIC) — any failure becomes one
 *    `SYSTEM WARN CHECKER_UNAVAILABLE` finding and `checkerAvailable = false`, never an error;
 * 6. the breaking-change step when a `baseline` (the contract's highest ACTIVE version below the
 *    candidate) is known — openapi-diff / the checker's `@asyncapi/diff` pass / `OdcsBreaking`
 *    report the facts, `BreakingChanges.settle` decides WARN vs INFO by the MAJOR bump and adds
 *    the soft `ERROR` `BREAKING_WITHOUT_MAJOR_BUMP` when the bump is missing;
 * 7. cross-checks (INFO: the document's declared version vs the stored SemVer, ODCS status vs
 *    the lifecycle), merge, cap, count.
 *
 * Synchronous on purpose: a 2 MiB document parses in milliseconds and the checker budget is
 * bounded (`checker.timeoutMs`); the caller stores the report beside the text in ONE transaction.
 */
class ChecksService(private val checkerProvider: () -> CheckerClient) {
    constructor(checker: CheckerClient) : this({ checker })

    private val log = LoggerFactory.getLogger(ChecksService::class.java)

    suspend fun check(
        type: ContractType,
        content: String,
        declaredVersion: String? = null,
        lifecycle: Lifecycle? = null,
        baseline: Baseline? = null,
    ): CheckReport {
        val parsed = when (val outcome = DocumentParser.parse(content)) {
            is ParseOutcome.Failed -> return CheckReport.of(outcome.format, null, listOf(outcome.finding), checkerAvailable = true)
            is ParseOutcome.Parsed -> outcome
        }
        DocumentParser.typeGate(type, parsed.root)?.let { gate ->
            return CheckReport.of(parsed.format, null, listOf(gate), checkerAvailable = true)
        }
        val metadata = Metadata.extract(type, parsed.root).copy(declaredVersion = Metadata.declaredVersion(type, parsed.root))
        val findings = mutableListOf<Finding>()
        findings += when (type) {
            ContractType.OPENAPI -> OpenApiValidator.validate(content)
            ContractType.ASYNCAPI -> AsyncApiValidator.validate(parsed.root)
            ContractType.ODCS -> OdcsValidator.validate(parsed.root)
        }
        var checkerAvailable = true
        if (type != ContractType.ODCS) {
            try {
                // The baseline rides along for ASYNCAPI only — the checker's `@asyncapi/diff` pass
                // answers BREAKING findings for it; the JVM computes the OPENAPI/ODCS facts itself.
                val previous = baseline?.content?.takeIf { type == ContractType.ASYNCAPI }
                findings += checkerProvider().check(type, content, previous).findings
            } catch (e: CheckerUnavailableException) {
                checkerAvailable = false
                log.warn("checker unavailable: {}", e.message)
                findings += Finding(
                    Severity.WARN, FindingSource.SYSTEM, CODE_CHECKER_UNAVAILABLE,
                    "The lint/semantic checker was unavailable — only the built-in syntax and schema checks ran; re-check later",
                )
            }
        }
        if (baseline != null) {
            findings += BreakingChanges.facts(type, baseline, content, parsed.root)
        }
        findings += crossChecks(type, parsed, declaredVersion, lifecycle)
        val settled = if (baseline != null) BreakingChanges.settle(findings, baseline, declaredVersion) else findings
        return CheckReport.of(parsed.format, metadata, settled, checkerAvailable, baseline?.version?.toString())
    }

    private fun crossChecks(
        type: ContractType,
        parsed: ParseOutcome.Parsed,
        declaredVersion: String?,
        lifecycle: Lifecycle?,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val documentVersion = Metadata.declaredVersion(type, parsed.root)
        if (declaredVersion != null && documentVersion != null && !sameVersion(documentVersion, declaredVersion)) {
            val where = if (type == ContractType.ODCS) "/version" else "/info/version"
            out += Finding(
                Severity.INFO, FindingSource.SEMANTIC, CODE_VERSION_MISMATCH,
                "The document declares version '$documentVersion' but is stored as '$declaredVersion'", where,
            )
        }
        val status = Metadata.declaredStatus(type, parsed.root)
        if (lifecycle != null && status != null && !status.equals(lifecycle.name, ignoreCase = true)) {
            out += Finding(
                Severity.INFO, FindingSource.SEMANTIC, CODE_STATUS_MISMATCH,
                "The document declares status '$status' but the version's lifecycle is ${lifecycle.name}", "/status",
            )
        }
        return out
    }

    /** `1.2.0` vs `1.2.0+build` are the same version (build metadata carries no precedence). */
    private fun sameVersion(a: String, b: String): Boolean {
        val pa = SemVer.parseOrNull(a)
        val pb = SemVer.parseOrNull(b)
        return if (pa != null && pb != null) pa.compareTo(pb) == 0 else a.trim() == b.trim()
    }

    companion object {
        const val CODE_CHECKER_UNAVAILABLE = "CHECKER_UNAVAILABLE"
        const val CODE_VERSION_MISMATCH = "VERSION_MISMATCH"
        const val CODE_STATUS_MISMATCH = "STATUS_MISMATCH"
    }
}

/** Audit the operational fact once per failed sidecar call (host + reason, never the document). */
fun io.ktor.server.application.ApplicationCall.auditCheckerUnavailable(report: CheckReport) {
    if (!report.checkerAvailable) audit("checker.unavailable", "path" to request.local.uri)
}
