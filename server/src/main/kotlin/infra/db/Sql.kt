package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikeEscapeOp
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringParam

/**
 * Case- and diacritics-insensitive contains-match: renders
 * `LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?)) ESCAPE '\'`, so "zolw" matches
 * "Żółw" and vice versa. Both sides fold through PG's unaccent (extension enabled in V4) — the
 * rules can't drift between query and stored text (ł→l, ß→ss, æ→ae, …), and unaccent never
 * touches ASCII `% _ \`, so [containsPattern]'s escaping survives the folding. unaccent runs
 * BEFORE LOWER on purpose: unaccent maps to same-case ASCII base letters, which LOWER folds
 * correctly under any DB locale — in a C-locale database (the postgres:18-alpine default),
 * `LOWER('Ż')` alone would leave the letter uppercase and uppercase-diacritic input would
 * silently stop matching. Every per-column substring filter MUST use this — never hand-roll
 * `lowerCase() like`.
 */
fun Expression<out String?>.containsNormalized(raw: String): Op<Boolean> {
    val pattern = containsPattern(raw)
    return LikeEscapeOp(
        LowerCase(unaccent(this)),
        LowerCase(unaccent(stringParam(pattern.pattern))),
        like = true,
        escapeChar = pattern.escapeChar,
    )
}

// Schema-qualified so resolution never depends on search_path. Accepts nullable string
// expressions too: a NULL value never LIKE-matches, which is exactly what a substring filter
// over an optional column should do.
private fun unaccent(expr: Expression<*>): CustomFunction<String> =
    CustomFunction("public.unaccent", TextColumnType(), expr)

/**
 * Case-insensitive contains-match pattern with SQL LIKE metacharacters escaped — the escaping
 * is correctness-sensitive and must not drift. Used only via [containsNormalized]; internal so
 * no filter site can bypass the diacritics folding.
 */
internal fun containsPattern(raw: String): LikePattern {
    val escaped = raw.lowercase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return LikePattern("%$escaped%", escapeChar = '\\')
}

/**
 * Existence test over a JSON ARRAY of small objects nested inside a TEXT column holding a JSON
 * document — the errors report's read over `contract_versions.findings` (`Finding[]`): renders
 * `EXISTS (SELECT 1 FROM jsonb_array_elements(CAST(col AS jsonb)) e WHERE TRUE AND (e->>'field')
 * IN (?, …) …)`, one `IN` clause per non-empty [clauses] pair, every value bound via
 * [stringParam]. An EMPTY value list LIFTS its own clause (matches on every element for that
 * field) rather than matching nothing — the "no filter selected on this dimension" case a caller
 * with `severities = emptyList()` needs; passing NO clauses at all answers "the array has at
 * least one element". Field names are compile-time constants by contract (`require`d — they land
 * inside a SQL literal, never a bound parameter, exactly like the removed `jsonArrayContains`'s path
 * segments before it). The per-row `CAST` is a seq-scan cost accepted at this scale — the day a
 * filter here needs an index is the day `findings` gets a denormalized column instead.
 */
fun Expression<String>.jsonArrayHasElementWhere(vararg clauses: Pair<String, List<String>>): Op<Boolean> {
    clauses.forEach { (field, _) ->
        require(field.matches(Regex("[A-Za-z0-9_]+"))) { "jsonArrayHasElementWhere field must be a simple identifier" }
    }
    return object : Op<Boolean>() {
        // Chained single-arg appends on purpose — the vararg overload is absent from the
        // QueryBuilder this resolves against in a cold (Docker) build (the idiom the checkup's
        // four JSON helpers established; see git show 26a9a3d^:server/.../infra/db/Sql.kt).
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("EXISTS (SELECT 1 FROM jsonb_array_elements(CAST(")
            queryBuilder.append(this@jsonArrayHasElementWhere)
            queryBuilder.append(" AS jsonb)) e WHERE TRUE")
            clauses.forEach { (field, values) ->
                if (values.isEmpty()) return@forEach
                queryBuilder.append(" AND (e->>'$field') IN (")
                values.forEachIndexed { index, value ->
                    if (index > 0) queryBuilder.append(", ")
                    queryBuilder.append(stringParam(value))
                }
                queryBuilder.append(")")
            }
            queryBuilder.append(")")
        }
    }
}

/**
 * Post-commit read-back guard: the create/transition already committed (Location set, audit
 * emitted), so a missing re-read is a server-side anomaly (500 via [error]), never a client
 * 404. Reads `X.read(id).orVanished("Domain", id)`; transitions pass a phase like
 * "after opening".
 */
fun <T> T?.orVanished(resource: String, id: Any, phase: String = "between create and re-read"): T =
    this ?: error("$resource $id vanished $phase")
