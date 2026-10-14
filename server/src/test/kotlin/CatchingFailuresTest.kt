package ch.nokillswit

import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatchingFailuresTest {
    @Test
    fun `ordinary failures are handled`() {
        assertEquals("ok", catchingFailures({ "ok" }) { "failed" })
        assertEquals("IllegalStateException", catchingFailures({ error("sensitive") }) { it.javaClass.simpleName })
    }

    @Test
    fun `cancellation and JVM errors propagate`() {
        assertFailsWith<CancellationException> {
            catchingFailures<Unit>({ throw CancellationException("gone") }) { }
        }
        assertFailsWith<StackOverflowError> {
            catchingFailures<Unit>({ throw StackOverflowError() }) { }
        }
    }
}
