package ch.nokillswit

import ch.nokillswit.contracts.SemVer
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SemVer 2.0 parsing and precedence — the "new version must exceed the highest" rule's engine. */
class SemVerTest {

    @Test
    fun `parses the grammar and rejects everything else`() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
        assertEquals(SemVer(2, 0, 0, prerelease = "rc.1"), SemVer.parse("2.0.0-rc.1"))
        assertEquals(SemVer(1, 0, 0, prerelease = "alpha", build = "001"), SemVer.parse("1.0.0-alpha+001"))
        assertEquals(SemVer(1, 0, 0, build = "20130313144700"), SemVer.parse("1.0.0+20130313144700"))
        val bads = listOf(
            "", "1", "1.2", "01.2.3", "1.2.3-", "1.2.3-01", "v1.2.3", "1.2.3 ", "1.2.3.4", "a.b.c", "1.2.3-rc..1", "1".repeat(70),
        )
        for (bad in bads) {
            assertNull(SemVer.parseOrNull(bad), "expected rejection of '$bad'")
        }
        assertFailsWith<BadRequestException> { SemVer.parse("nope") }
    }

    @Test
    fun `precedence follows the spec's ordered example`() {
        // https://semver.org/spec/v2.0.0.html#spec-item-11
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0",
        ).map { SemVer.parse(it) }
        for (i in 1 until ordered.size) {
            assertTrue(ordered[i - 1] < ordered[i], "${ordered[i - 1]} must precede ${ordered[i]}")
        }
        // Build metadata is ignored in precedence.
        assertEquals(0, SemVer.parse("1.0.0+a").compareTo(SemVer.parse("1.0.0+b")))
        assertEquals("1.0.0-alpha+001", SemVer.parse("1.0.0-alpha+001").toString())
    }
}
