package ch.nokillswit.contracts

import ch.nokillswit.authz.ConflictException
import kotlinx.serialization.Serializable

/**
 * The version lifecycle — strict on purpose (decided with the product owner): a document is
 * editable only while DRAFT or PROPOSED; from ACTIVE on only the lifecycle moves; a DRAFT may
 * be deleted, nothing else; several ACTIVE versions may coexist (that is what DEPRECATED is
 * for). An illegal transition is a state-shaped `409`, like the last-admin rule.
 */
@Serializable
enum class Lifecycle { DRAFT, PROPOSED, ACTIVE, DEPRECATED, RETIRED }

val ALLOWED_TRANSITIONS: Map<Lifecycle, Set<Lifecycle>> = mapOf(
    Lifecycle.DRAFT to setOf(Lifecycle.PROPOSED),
    Lifecycle.PROPOSED to setOf(Lifecycle.DRAFT, Lifecycle.ACTIVE),
    Lifecycle.ACTIVE to setOf(Lifecycle.DEPRECATED),
    Lifecycle.DEPRECATED to setOf(Lifecycle.RETIRED),
    Lifecycle.RETIRED to emptySet(),
)

fun Lifecycle.canTransitionTo(target: Lifecycle): Boolean = target in ALLOWED_TRANSITIONS.getValue(this)

fun Lifecycle.requireTransitionTo(target: Lifecycle) {
    if (!canTransitionTo(target)) throw ConflictException("A $this version cannot move to $target")
}

/** The document text is editable only before the contract is published. */
val Lifecycle.contentEditable: Boolean
    get() = this == Lifecycle.DRAFT || this == Lifecycle.PROPOSED

/** Only a DRAFT may be deleted — everything after has been shown to consumers. */
val Lifecycle.deletable: Boolean
    get() = this == Lifecycle.DRAFT

/** A contract may be deleted only when nothing published (ACTIVE/DEPRECATED) remains. */
val Lifecycle.published: Boolean
    get() = this == Lifecycle.ACTIVE || this == Lifecycle.DEPRECATED
