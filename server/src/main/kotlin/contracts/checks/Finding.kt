package ch.nokillswit.contracts.checks

import kotlinx.serialization.Serializable

/**
 * The finding vocabulary — shared verbatim with the checker sidecar (`checker/src/findings.ts`)
 * and stored as the version's JSON snapshot. HARD = `source == SYNTAX` (a `400` on every store
 * path, never waivable); a SOFT `ERROR` blocks a strict save unless `allowInvalid=true`;
 * `WARN`/`INFO` never block. `SYSTEM` carries operational notes (`CHECKER_UNAVAILABLE`,
 * `FINDINGS_TRUNCATED`); `CONFORMANCE` is a LIVE observation — a try-it response, message or table
 * measured against the document (never stored on a version); `INFERENCE` is the inference
 * engine's voice (`contracts/infer/`) — a heuristic applied while building a draft, never stored
 * either. Codes are stable machine ids: the JVM's own SCREAMING_SNAKE codes,
 * Spectral's kebab rule ids, the AsyncAPI parser's codes.
 */
@Serializable
enum class Severity { ERROR, WARN, INFO }

@Serializable
enum class FindingSource { SYNTAX, SCHEMA, SEMANTIC, LINT, BREAKING, CONFORMANCE, SYSTEM, INFERENCE }

@Serializable
data class Finding(
    val severity: Severity,
    val source: FindingSource,
    val code: String,
    val message: String,
    /** A JSON pointer into the document (`/paths/~1pets/get`), when located. */
    val path: String? = null,
    /** 1-based, when located. */
    val line: Int? = null,
    val column: Int? = null,
) {
    val hard: Boolean get() = source == FindingSource.SYNTAX
}

/** At most this many entries are stored per version; a final INFO occupies one slot when truncated. */
const val MAX_STORED_FINDINGS = 500

private const val STORED_TRUNCATION_CODE = "FINDINGS_TRUNCATED"
private const val CHECKER_TRUNCATION_CODE = "findings-truncated"
private val truncationCodes = setOf(STORED_TRUNCATION_CODE, CHECKER_TRUNCATION_CODE)
private val findingOrder = compareBy<Finding>({ it.severity.ordinal }, { it.line ?: Int.MAX_VALUE }, { it.code })

/**
 * Applies the persisted-response bound while retaining evidence that an upstream checker result
 * was already incomplete. Existing markers do not consume a second slot or survive as duplicates.
 */
internal fun capStoredFindings(findings: List<Finding>): List<Finding> {
    val sorted = findings.sortedWith(findingOrder)
    val previousMarker = sorted.firstOrNull {
        it.source == FindingSource.SYSTEM && it.code in truncationCodes
    }
    val substantive = sorted.filterNot {
        it.source == FindingSource.SYSTEM && it.code in truncationCodes
    }
    if (previousMarker == null && substantive.size <= MAX_STORED_FINDINGS) return substantive

    val retained = substantive.take(MAX_STORED_FINDINGS - 1)
    val omittedHere = substantive.size - retained.size
    val message = when {
        previousMarker != null && omittedHere > 0 ->
            "$omittedHere additional findings were not stored; earlier truncation also omitted findings " +
                "(cap $MAX_STORED_FINDINGS, including this marker)"
        previousMarker != null -> previousMarker.message
        else -> "$omittedHere further findings were not stored " +
            "(cap $MAX_STORED_FINDINGS, including this marker)"
    }
    return retained + Finding(
        severity = Severity.INFO,
        source = FindingSource.SYSTEM,
        code = STORED_TRUNCATION_CODE,
        message = message,
    )
}

/**
 * The document's serialization format, detected from the first significant character. Lowercase
 * on purpose: the entry name IS the wire value, the V10 CHECK value and the download extension.
 */
@Suppress("EnumNaming")
@Serializable
enum class DocumentFormat { yaml, json }

/** What one check run produced — the findings plus the metadata read on the way. */
@Serializable
data class CheckReport(
    val format: DocumentFormat?,
    val specVersion: String?,
    val title: String?,
    val description: String?,
    /** The version the DOCUMENT itself claims (`info.version` / ODCS `version`) — the import form's prefill. */
    val declaredVersion: String?,
    val findings: List<Finding>,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    /** False when the checker sidecar could not be reached — its LINT/SEMANTIC verdicts are missing. */
    val checkerAvailable: Boolean,
    /** The ACTIVE or DEPRECATED version used for breaking changes; null when none applied. */
    val baselineVersion: String? = null,
) {
    val hardFindings: List<Finding> get() = findings.filter { it.hard }
    val softErrors: List<Finding> get() = findings.filter { !it.hard && it.severity == Severity.ERROR }

    companion object {
        fun of(
            format: DocumentFormat?,
            metadata: DocumentMetadata?,
            findings: List<Finding>,
            checkerAvailable: Boolean,
            baselineVersion: String? = null,
        ): CheckReport {
            val capped = capStoredFindings(findings)
            return CheckReport(
                format = format,
                specVersion = metadata?.specVersion,
                title = metadata?.title,
                description = metadata?.description,
                declaredVersion = metadata?.declaredVersion,
                findings = capped,
                errors = capped.count { it.severity == Severity.ERROR },
                warnings = capped.count { it.severity == Severity.WARN },
                infos = capped.count { it.severity == Severity.INFO },
                checkerAvailable = checkerAvailable,
                baselineVersion = baselineVersion,
            )
        }
    }
}

/** What Covenant reads out of a document for display; truncated to the column widths at write time. */
data class DocumentMetadata(val specVersion: String?, val title: String?, val description: String?, val declaredVersion: String? = null)
