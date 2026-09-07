package ch.nokillswit.contracts.checks

import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.SemVer
import kotlinx.serialization.Serializable

/**
 * The compatibility report between two arbitrary versions of the same contract (milestone —
 * "compatibility, named"): never stored, never audited, a pure read like `…/{vid}/model`. With
 * `from` the older/reference side and `to` the newer/candidate side:
 * - **backward compatible** — consumers built for `from` keep working against `to`: the facts of
 *   comparing old=`from` → new=`to` are empty.
 * - **forward compatible** — consumers built for `to` work against `from`: comparing old=`to` →
 *   new=`from` yields no facts.
 *
 * `verdict` names the pair (`FULL`/`BACKWARD`/`FORWARD`/`NONE`/`UNKNOWN` — a direction could not
 * be computed: no ACTIVE predecessor, an unparseable side, a differ skip, the checker down for
 * AsyncAPI); `bump` is the "why" sentence — a MINOR/PATCH bump of `to` over `from` PROMISES
 * backward compatibility, MAJOR permits a break, `DOWNGRADE` when `from` is actually the newer one.
 */
@Serializable
enum class CompatibilityVerdict { FULL, BACKWARD, FORWARD, NONE, UNKNOWN }

@Serializable
enum class VersionBump { MAJOR, MINOR, PATCH, PRERELEASE, NONE, DOWNGRADE }

/** One side of the pair, as far as the SPA needs to know: which row this is (a compact link target). */
@Serializable
data class VersionRef(val id: UInt, val version: String, val lifecycle: Lifecycle)

/** `compatible = null` means not computable — `findings` then holds the SKIPPED / CHECKER_UNAVAILABLE note, never facts. */
@Serializable
data class CompatibilityDirection(val compatible: Boolean?, val findings: List<Finding>)

@Serializable
data class CompatibilityReport(
    val from: VersionRef?,
    val to: VersionRef,
    val verdict: CompatibilityVerdict,
    // ABSENT (not null) when `from` is null: a nullable enum has no clean OpenAPI 3.0 spelling —
    // `nullable` beside a `$ref` never reaches the enum's value check, and a literal `null` in
    // the enum list crashes Spectral's path engine — so the wire simply omits it (the spec
    // declares it optional and non-nullable).
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val bump: VersionBump? = null,
    val backward: CompatibilityDirection,
    val forward: CompatibilityDirection,
    val checkerAvailable: Boolean,
)

/** The non-ref half of a [CompatibilityReport] — [ChecksService.compatibility]'s answer, before the route attaches the [VersionRef]s. */
data class CompatibilityOutcome(
    val verdict: CompatibilityVerdict,
    val bump: VersionBump?,
    val backward: CompatibilityDirection,
    val forward: CompatibilityDirection,
    val checkerAvailable: Boolean,
)

/** Pure: the SemVer bump between two versions, and the two-way verdict from each direction's `compatible` flag. */
object Compatibility {
    /** `to < from` is a `DOWNGRADE` regardless of which column differs; otherwise the highest differing column wins. */
    fun bump(from: SemVer, to: SemVer): VersionBump = when {
        to < from -> VersionBump.DOWNGRADE
        to.major != from.major -> VersionBump.MAJOR
        to.minor != from.minor -> VersionBump.MINOR
        to.patch != from.patch -> VersionBump.PATCH
        to.prerelease != from.prerelease -> VersionBump.PRERELEASE
        else -> VersionBump.NONE
    }

    fun verdict(backward: Boolean?, forward: Boolean?): CompatibilityVerdict = when {
        backward == null || forward == null -> CompatibilityVerdict.UNKNOWN
        backward && forward -> CompatibilityVerdict.FULL
        backward -> CompatibilityVerdict.BACKWARD
        forward -> CompatibilityVerdict.FORWARD
        else -> CompatibilityVerdict.NONE
    }
}
