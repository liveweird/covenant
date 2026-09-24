package ch.nokillswit.contracts.checks

import ch.nokillswit.authz.caller
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
 * 6. the breaking-change step when a `baseline` (the contract's highest eligible published version below the
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
                    "The lint/semantic checker was unavailable — only the built-in checks ran; re-check later",
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

    /**
     * The two-way compatibility report between an arbitrary pair (`contracts/checks/Compatibility.kt`):
     * `backward` = the facts of comparing old=`from` → new=`to`, `forward` = old=`to` → new=`from` —
     * the same raw facts `BreakingChanges`/`settle` draw on, kept at their WARN severity (no
     * MAJOR-bump flip here; that sentence is the SPA's). `from == null` (no published predecessor)
     * answers UNKNOWN without touching any engine. Identical OPENAPI/ODCS text short-circuits to
     * `compatible = true` twice; ASYNCAPI still needs the checker to detect unresolved external
     * references in a historical baseline.
     */
    suspend fun compatibility(type: ContractType, from: Baseline?, toVersion: SemVer, toContent: String): CompatibilityOutcome {
        if (from == null) {
            val note = CompatibilityDirection(null, listOf(skippedFinding("no published version to compare against")))
            return CompatibilityOutcome(CompatibilityVerdict.UNKNOWN, null, note, note, checkerAvailable = true)
        }
        if (from.content == toContent && type != ContractType.ASYNCAPI) {
            val same = CompatibilityDirection(true, emptyList())
            val bump = Compatibility.bump(from.version, toVersion)
            return CompatibilityOutcome(CompatibilityVerdict.FULL, bump, same, same, checkerAvailable = true)
        }
        val backward = breakingFacts(type, from.version, from.content, toContent)
        val forward = breakingFacts(type, toVersion, toContent, from.content)
        return CompatibilityOutcome(
            Compatibility.verdict(backward.direction.compatible, forward.direction.compatible),
            Compatibility.bump(from.version, toVersion),
            backward.direction,
            forward.direction,
            backward.checkerAvailable && forward.checkerAvailable,
        )
    }

    /**
     * Lifecycle transitions do not rerun slow validators. Only the ODCS status cross-check is
     * lifecycle-dependent, so replace that one finding and preserve the rest of the snapshot.
     */
    fun refreshLifecycleFinding(
        type: ContractType,
        content: String,
        lifecycle: Lifecycle,
        findings: List<Finding>,
    ): List<Finding> {
        val retained = findings.filterNot { it.code == CODE_STATUS_MISMATCH }
        if (type != ContractType.ODCS) return capStoredFindings(retained)
        val parsed = DocumentParser.parse(content) as? ParseOutcome.Parsed ?: return capStoredFindings(retained)
        val status = Metadata.declaredStatus(type, parsed.root) ?: return capStoredFindings(retained)
        val refreshed = if (status.equals(lifecycle.name, ignoreCase = true)) {
            retained
        } else {
            retained + Finding(
                Severity.INFO, FindingSource.SEMANTIC, CODE_STATUS_MISMATCH,
                "The document declares status '$status' but the version's lifecycle is ${lifecycle.name}", "/status",
            )
        }
        return capStoredFindings(refreshed)
    }

    /** One direction: the facts of comparing `old` (`oldVersion`/`oldContent`) against `newContent`. */
    private suspend fun breakingFacts(type: ContractType, oldVersion: SemVer, oldContent: String, newContent: String): DirectionResult {
        val parsedNew = when (val outcome = DocumentParser.parse(newContent)) {
            is ParseOutcome.Failed ->
                return DirectionResult(CompatibilityDirection(null, listOf(skippedFinding("the document does not parse"))), true)
            is ParseOutcome.Parsed -> outcome
        }
        if (DocumentParser.typeGate(type, parsedNew.root) != null) {
            return DirectionResult(
                CompatibilityDirection(null, listOf(skippedFinding("the document does not match the contract's type"))),
                true,
            )
        }
        return when (type) {
            ContractType.OPENAPI, ContractType.ODCS -> {
                val facts = BreakingChanges.facts(type, Baseline(oldVersion, oldContent), newContent, parsedNew.root)
                val isSkipped = facts.any { it.code == BreakingChanges.CODE_SKIPPED }
                DirectionResult(CompatibilityDirection(if (isSkipped) null else facts.isEmpty(), facts), true)
            }
            ContractType.ASYNCAPI -> {
                val nativeFacts = BreakingChanges.facts(type, Baseline(oldVersion, oldContent), newContent, parsedNew.root)
                try {
                    val checkerFindings = checkerProvider().check(type, newContent, previousContent = oldContent).findings
                    // The checker sorts by severity before capping. Its INFO skip (or even a
                    // BREAKING fact) can fall past 500 earlier errors, so truncation is unknown.
                    val checkerFacts = checkerFindings.filter { it.source == FindingSource.BREAKING } +
                        if (checkerFindings.any { it.code == "findings-truncated" && it.source == FindingSource.SYSTEM }) {
                            listOf(skippedFinding("the checker result was truncated"))
                        } else {
                            emptyList()
                        }
                    val breaking = checkerFacts + nativeFacts
                    DirectionResult(asyncApiDirection(breaking, unavailable = false), true)
                } catch (e: CheckerUnavailableException) {
                    log.warn("checker unavailable: {}", e.message)
                    val note = Finding(
                        Severity.WARN,
                        FindingSource.SYSTEM,
                        CODE_CHECKER_UNAVAILABLE,
                        CHECKER_UNAVAILABLE_DIRECTION_MESSAGE,
                    )
                    DirectionResult(asyncApiDirection(nativeFacts + note, unavailable = true), false)
                }
            }
        }
    }

    /** A proven fact wins over incomplete coverage; uncertainty matters only when no break was established. */
    private fun asyncApiDirection(findings: List<Finding>, unavailable: Boolean): CompatibilityDirection {
        val hasFact = findings.any { it.source == FindingSource.BREAKING && !BreakingChanges.isSkipped(it) }
        val compatible = when {
            hasFact -> false
            unavailable || findings.any(BreakingChanges::isSkipped) -> null
            else -> true
        }
        return CompatibilityDirection(compatible, findings)
    }

    private fun skippedFinding(reason: String) = Finding(
        Severity.INFO, FindingSource.BREAKING, BreakingChanges.CODE_SKIPPED,
        "Breaking changes could not be computed — $reason",
    )

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

        private const val CHECKER_UNAVAILABLE_DIRECTION_MESSAGE =
            "The lint/semantic checker was unavailable — this direction's breaking changes could not be computed"
    }
}

/** [ChecksService.breakingFacts]'s answer: the direction plus whether the checker was reachable for it. */
private data class DirectionResult(val direction: CompatibilityDirection, val checkerAvailable: Boolean)

/** Audit the operational fact once per failed sidecar call: who was checking what (byUserId + the request path), never the document. */
fun io.ktor.server.application.ApplicationCall.auditCheckerUnavailable(report: CheckReport) {
    if (!report.checkerAvailable) {
        audit("checker.unavailable", "byUserId" to caller().userId.toLong(), "path" to request.local.uri)
    }
}
