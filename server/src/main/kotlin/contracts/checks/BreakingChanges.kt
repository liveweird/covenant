package ch.nokillswit.contracts.checks

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.SemVer
import com.fasterxml.jackson.databind.JsonNode

/** The version a candidate is compared against: its highest eligible published predecessor. */
data class Baseline(val version: SemVer, val content: String)

/**
 * The breaking-change step of the pipeline (milestone 2). The engines — openapi-diff-core for
 * OPENAPI, the checker's `@asyncapi/diff` plus Apache Avro for ASYNCAPI, `OdcsBreaking` for
 * ODCS — report the FACTS as `BREAKING` findings;
 * this step settles their severity against the SemVer rule: with the MAJOR bump the baseline
 * demands they are INFO (the change log of a deliberate break), without it they stay WARN and
 * ONE soft `ERROR` `BREAKING_WITHOUT_MAJOR_BUMP` blocks the strict save — waivable like every
 * soft finding, recorded as such.
 */
object BreakingChanges {
    const val CODE_WITHOUT_MAJOR_BUMP = "BREAKING_WITHOUT_MAJOR_BUMP"
    const val CODE_SKIPPED = "BREAKING_CHECK_SKIPPED"
    const val CODE_ASYNCAPI_DIFF_SKIPPED = "asyncapi-diff-skipped"

    /** The JVM-side facts for the type; generic ASYNCAPI facts still come from the checker. */
    fun facts(type: ContractType, baseline: Baseline, content: String, root: JsonNode): List<Finding> = when (type) {
        ContractType.OPENAPI -> OpenApiBreaking.compare(baseline.content, content) ?: listOf(skipped(baseline))
        ContractType.ASYNCAPI -> when (val old = DocumentParser.parse(baseline.content)) {
            is ParseOutcome.Parsed -> AvroBreaking.compare(old.root, root)
            is ParseOutcome.Failed -> listOf(skipped(baseline))
        }
        ContractType.ODCS -> when (val old = DocumentParser.parse(baseline.content)) {
            is ParseOutcome.Parsed -> OdcsBreaking.compare(old.root, root)
            is ParseOutcome.Failed -> listOf(skipped(baseline))
        }
    }

    /**
     * Settles the BREAKING findings' severity and adds the gate. `candidate` is the version the
     * document is (to be) stored as; unknown or unparseable, the facts stay WARN and nothing blocks.
     */
    fun settle(findings: List<Finding>, baseline: Baseline, candidate: String?): List<Finding> {
        val facts = findings.filter { it.source == FindingSource.BREAKING && !isSkipped(it) }
        if (facts.isEmpty()) return findings
        val version = candidate?.let { SemVer.parseOrNull(it) }
        val majorBumped = version != null && version.major > baseline.version.major
        val severity = if (majorBumped) Severity.INFO else Severity.WARN
        val settled = findings.map { f -> if (f in facts) f.copy(severity = severity) else f }
        if (majorBumped || version == null) return settled
        val noun = if (facts.size == 1) "change" else "changes"
        return settled + Finding(
            Severity.ERROR, FindingSource.BREAKING, CODE_WITHOUT_MAJOR_BUMP,
            "${facts.size} breaking $noun against published version ${baseline.version} — " +
                "$version needs a MAJOR bump (${baseline.version.major + 1}.0.0 or above)",
        )
    }

    private fun skipped(baseline: Baseline) = Finding(
        Severity.INFO, FindingSource.BREAKING, CODE_SKIPPED,
        "Breaking changes against published version ${baseline.version} could not be computed — that document does not compare",
    )

    fun isSkipped(finding: Finding): Boolean = finding.code == CODE_SKIPPED ||
        finding.code == CODE_ASYNCAPI_DIFF_SKIPPED || finding.code == AvroBreaking.CODE_SKIPPED
}
