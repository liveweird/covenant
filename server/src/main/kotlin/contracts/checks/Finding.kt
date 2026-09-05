package ch.nokillswit.contracts.checks

import kotlinx.serialization.Serializable

/**
 * The finding vocabulary — shared verbatim with the checker sidecar (`checker/src/findings.ts`)
 * and stored as the version's JSON snapshot. HARD = `source == SYNTAX` (a `400` on every store
 * path, never waivable); a SOFT `ERROR` blocks a strict save unless `allowInvalid=true`;
 * `WARN`/`INFO` never block. `SYSTEM` carries operational notes (`CHECKER_UNAVAILABLE`,
 * `FINDINGS_TRUNCATED`). Codes are stable machine ids: the JVM's own SCREAMING_SNAKE codes,
 * Spectral's kebab rule ids, the AsyncAPI parser's codes.
 */
@Serializable
enum class Severity { ERROR, WARN, INFO }

@Serializable
enum class FindingSource { SYNTAX, SCHEMA, SEMANTIC, LINT, BREAKING, SYSTEM }

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

/** At most this many findings are stored per version; a final INFO marks the cut. */
const val MAX_STORED_FINDINGS = 500

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
    /** The ACTIVE version the document was compared against for breaking changes; null when none applied. */
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
            val sorted = findings.sortedWith(
                compareBy<Finding>({ it.severity.ordinal }, { it.line ?: Int.MAX_VALUE }, { it.code }),
            )
            val capped = if (sorted.size <= MAX_STORED_FINDINGS) {
                sorted
            } else {
                sorted.take(MAX_STORED_FINDINGS) + Finding(
                    severity = Severity.INFO,
                    source = FindingSource.SYSTEM,
                    code = "FINDINGS_TRUNCATED",
                    message = "${sorted.size - MAX_STORED_FINDINGS} further findings were not stored (cap $MAX_STORED_FINDINGS)",
                )
            }
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
