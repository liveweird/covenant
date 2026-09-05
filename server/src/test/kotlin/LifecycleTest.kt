package ch.nokillswit

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.canTransitionTo
import ch.nokillswit.contracts.contentEditable
import ch.nokillswit.contracts.deletable
import ch.nokillswit.contracts.published
import ch.nokillswit.contracts.requireTransitionTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The strict lifecycle state machine — pinned as a whole matrix so no edge slips in unnoticed. */
class LifecycleTest {

    @Test
    fun `the transition matrix is exactly the documented one`() {
        val allowed = Lifecycle.entries.flatMap { from -> Lifecycle.entries.filter { from.canTransitionTo(it) }.map { from to it } }
        assertEquals(
            setOf(
                Lifecycle.DRAFT to Lifecycle.PROPOSED,
                Lifecycle.PROPOSED to Lifecycle.DRAFT,
                Lifecycle.PROPOSED to Lifecycle.ACTIVE,
                Lifecycle.ACTIVE to Lifecycle.DEPRECATED,
                Lifecycle.DEPRECATED to Lifecycle.RETIRED,
            ),
            allowed.toSet(),
        )
        assertFailsWith<ConflictException> { Lifecycle.DRAFT.requireTransitionTo(Lifecycle.ACTIVE) }
        assertFailsWith<ConflictException> { Lifecycle.RETIRED.requireTransitionTo(Lifecycle.DRAFT) }
        Lifecycle.DRAFT.requireTransitionTo(Lifecycle.PROPOSED)
    }

    @Test
    fun `content is editable only before publication, only a draft is deletable`() {
        assertTrue(Lifecycle.DRAFT.contentEditable)
        assertTrue(Lifecycle.PROPOSED.contentEditable)
        for (l in listOf(Lifecycle.ACTIVE, Lifecycle.DEPRECATED, Lifecycle.RETIRED)) assertFalse(l.contentEditable, "$l")
        assertTrue(Lifecycle.DRAFT.deletable)
        assertFalse(Lifecycle.PROPOSED.deletable)
        assertTrue(Lifecycle.ACTIVE.published)
        assertTrue(Lifecycle.DEPRECATED.published)
        assertFalse(Lifecycle.RETIRED.published)
    }
}
