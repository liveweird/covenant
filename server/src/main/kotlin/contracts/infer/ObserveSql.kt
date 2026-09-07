package ch.nokillswit.contracts.infer

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.contracts.tryit.PostgresRead
import ch.nokillswit.contracts.tryit.SqlTry
import ch.nokillswit.environments.PostgresTarget
import io.ktor.server.plugins.BadRequestException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The SQL observe leg: describe one relation's columns straight from PostgreSQL's own catalog —
 * `to_regclass`/`pg_attribute`/`pg_index`, never `SELECT *` — or list what the database offers to
 * describe. `to_regclass` resolves through the search path the same way `SELECT * FROM <rel>`
 * would, so a bare name finds the same relation a later query against it would.
 */
object ObserveSql {
    private val TABLE_KINDS = setOf("r", "p", "f")
    private val VIEW_KINDS = setOf("v", "m")
    private const val LIST_LIMIT = 500

    /** One identifier, optionally schema-qualified — the relation this leg reads and the audit records. */
    class Relation(val schema: String?, val name: String) {
        val qualified: String get() = (schema?.let { "\"$it\"." } ?: "") + "\"$name\""
    }

    /** Exactly `SqlTry.prepare`'s identifier rule — the same grammar, the same 400 message. */
    fun parseRelation(relation: String): Relation {
        val parts = relation.split('.')
        if (parts.size > 2 || parts.any { !SqlTry.IDENTIFIER.matches(it) }) {
            throw BadRequestException("The relation must be an identifier, optionally schema-qualified")
        }
        return Relation(parts.takeIf { it.size == 2 }?.first(), parts.last())
    }

    /** A missing relation (`to_regclass` NULL) is a 404; every other failure is a classified 502. */
    suspend fun describe(target: PostgresTarget, relation: Relation, notes: Notes): RelationSample = try {
        PostgresRead.readOnly(target) { connection -> describeIn(connection, relation, notes) }
    } catch (e: SQLException) {
        throw BadGatewayException(PostgresRead.classify(e))
    }

    suspend fun listRelations(target: PostgresTarget): List<RelationSummary> = try {
        PostgresRead.readOnly(target, ::listIn)
    } catch (e: SQLException) {
        throw BadGatewayException(PostgresRead.classify(e))
    }

    private fun describeIn(connection: Connection, relation: Relation, notes: Notes): RelationSample {
        val oid = connection.prepareStatement("SELECT to_regclass(?)::oid").use { st ->
            st.setString(1, relation.qualified)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getLong(1).takeUnless { rs.wasNull() }
            }
        } ?: throw NotFoundException("Relation not found")
        val kind = connection.prepareStatement("SELECT relkind FROM pg_class WHERE oid = ?").use { st ->
            st.setLong(1, oid)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        val physicalType = when (kind) {
            in TABLE_KINDS -> "table"
            in VIEW_KINDS -> "view"
            else -> null
        }
        val positions = primaryKeyPositions(connection, oid)
        val columns = columnsOf(connection, oid, positions)
        if (physicalType == "view") {
            notes.info("INFER_VIEW_NULLABILITY", "'${relation.name}' is a view — PostgreSQL reports every column as nullable")
        }
        return RelationSample(name = relation.name, physicalType = physicalType, columns = columns)
    }

    private fun columnsOf(connection: Connection, oid: Long, primaryKeyPositions: Map<String, Int>): List<RelationColumn> {
        val sql = """
            SELECT a.attname, t.typname, a.attnotnull, a.attnum
            FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid
            WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
        """.trimIndent()
        return connection.prepareStatement(sql).use { st ->
            st.setLong(1, oid)
            st.executeQuery().use { rs ->
                val columns = mutableListOf<RelationColumn>()
                while (rs.next()) {
                    val name = rs.getString(1)
                    columns += RelationColumn(
                        name = name,
                        dbType = rs.getString(2),
                        nullable = !rs.getBoolean(3),
                        primaryKeyPosition = primaryKeyPositions[name],
                    )
                }
                columns
            }
        }
    }

    private fun primaryKeyPositions(connection: Connection, oid: Long): Map<String, Int> {
        // A plain comma cross-join throughout (never mixed with an explicit JOIN): an explicit
        // JOIN's ON clause may only see its own two sides, and `a` needs both `i` and `k`.
        val sql = """
            SELECT a.attname, k.ord
            FROM pg_index i, unnest(i.indkey) WITH ORDINALITY k(attnum, ord), pg_attribute a
            WHERE i.indrelid = ? AND i.indisprimary AND a.attrelid = i.indrelid AND a.attnum = k.attnum
            ORDER BY k.ord
        """.trimIndent()
        return connection.prepareStatement(sql).use { st ->
            st.setLong(1, oid)
            st.executeQuery().use { rs ->
                val positions = mutableMapOf<String, Int>()
                while (rs.next()) positions[rs.getString(1)] = rs.getInt(2)
                positions
            }
        }
    }

    private fun listIn(connection: Connection): List<RelationSummary> {
        val sql = """
            SELECT n.nspname, c.relname, c.relkind
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT IN ('pg_catalog','information_schema')
              AND n.nspname NOT LIKE 'pg_toast%'
            ORDER BY 1, 2 LIMIT $LIST_LIMIT
        """.trimIndent()
        return connection.prepareStatement(sql).use { st -> st.executeQuery().use(::relationRows) }
    }

    private fun relationRows(rs: ResultSet): List<RelationSummary> {
        val relations = mutableListOf<RelationSummary>()
        while (rs.next()) relations += relationRow(rs)
        return relations
    }

    private fun relationRow(rs: ResultSet): RelationSummary {
        val kind = if (rs.getString(3) in TABLE_KINDS) "table" else "view"
        return RelationSummary(rs.getString(1), rs.getString(2), kind)
    }
}
