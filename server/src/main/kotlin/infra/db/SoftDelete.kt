package ch.nokillswit.infra.db

import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The soft-delete convention as a type (persistence.md "Soft delete"): every business table carries
 * `marked_as_deleted`, and every read filters on it through ONE [active] predicate instead of a private
 * copy per service (seven copies before the checkup).
 */
interface SoftDeletable {
    val markedAsDeleted: Column<Boolean>
}

/** The rows that still exist, in the business sense. */
fun SoftDeletable.active(): Op<Boolean> = markedAsDeleted eq false

/** The wall clock every write stamps (`created_at`/`updated_at`, epoch millis) — one name to grep for. */
fun nowMillis(): Long = System.currentTimeMillis()

/**
 * One grouped count of ACTIVE child rows per parent id — the "n systems" / "n contracts" / "n versions"
 * every list caption shows, one query per page. Callers pass the table they read (a sanctioned
 * cross-feature read — the list lives in persistence.md).
 */
suspend fun <T> T.activeCountsBy(parentId: Column<EntityID<UInt>>, ids: List<UInt>): Map<UInt, Int>
    where T : UIntIdTable, T : SoftDeletable {
    if (ids.isEmpty()) return emptyMap()
    val count = id.count()
    return select(parentId, count)
        .where { (parentId inList ids) and active() }
        .groupBy(parentId)
        .map { it[parentId].value to it[count].toInt() }
        .toList()
        .toMap()
}

/** A client-supplied foreign key must name an ACTIVE row — else the uniform 400 (never a 404: the caller chose the id). */
suspend fun <T> T.requireActive(rowId: UInt, label: String)
    where T : UIntIdTable, T : SoftDeletable {
    val exists = select(id).where { (id eq rowId) and active() }.toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown or deleted $label id: $rowId")
}
