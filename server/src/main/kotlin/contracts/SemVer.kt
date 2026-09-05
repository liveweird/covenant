package ch.nokillswit.contracts

import io.ktor.server.plugins.BadRequestException

/**
 * SemVer 2.0.0 (https://semver.org/spec/v2.0.0.html): `MAJOR.MINOR.PATCH[-prerelease][+build]`.
 * Stored verbatim; the parsed parts back SQL ordering, and [compareTo] is the full precedence
 * rule — numeric-then-lexical prerelease identifiers, a prerelease BELOW its release, build
 * metadata ignored — deciding "a new version must exceed the highest existing one".
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
    val build: String? = null,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        val mine = prerelease
        val theirs = other.prerelease
        if (mine == null && theirs == null) return 0
        if (mine == null) return 1 // a release outranks any prerelease of the same triple
        if (theirs == null) return -1
        return comparePrerelease(mine, theirs)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        prerelease?.let { append('-').append(it) }
        build?.let { append('+').append(it) }
    }

    companion object {
        const val MAX_LENGTH = 64

        // The official grammar (semver.org "FAQ: Is there a suggested regular expression").
        private val PATTERN = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
                "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$",
        )

        fun parseOrNull(raw: String): SemVer? {
            if (raw.length > MAX_LENGTH) return null
            val m = PATTERN.matchEntire(raw) ?: return null
            val g = m.groupValues
            return SemVer(
                major = g[1].toIntOrNull() ?: return null,
                minor = g[2].toIntOrNull() ?: return null,
                patch = g[3].toIntOrNull() ?: return null,
                prerelease = g[4].ifEmpty { null },
                build = g[5].ifEmpty { null },
            )
        }

        /** The route/service gate: a malformed version is the caller's `400`. */
        fun parse(raw: String): SemVer =
            parseOrNull(raw) ?: throw BadRequestException("Version must be a valid SemVer 2.0 string (e.g. 1.2.0 or 2.0.0-rc.1)")

        private fun comparePrerelease(a: String, b: String): Int {
            val xs = a.split('.')
            val ys = b.split('.')
            for (i in 0 until minOf(xs.size, ys.size)) {
                val x = xs[i]
                val y = ys[i]
                val xn = x.toLongOrNull()
                val yn = y.toLongOrNull()
                val c = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1 // numeric identifiers rank below alphanumeric ones
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return xs.size.compareTo(ys.size) // a longer identifier list ranks higher
        }
    }
}
