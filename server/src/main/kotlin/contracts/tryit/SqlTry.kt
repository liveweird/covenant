package ch.nokillswit.contracts.tryit

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.environments.PostgresTarget
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.util.Base64
import java.util.Properties

/**
 * The SQL leg of try-it: `SELECT * FROM <dataset> LIMIT n` over a read-only JDBC connection to
 * the environment's PostgreSQL target, the result's metadata measured against the ODCS dataset's
 * declared properties. **No user-written SQL**: the dataset must be one the document declares
 * (a table or a view), its identifier is validated against a strict grammar and quoted, the limit
 * is bound; the connection is read-only at three levels (driver `readOnly`, `readOnlyMode=always`,
 * `SET TRANSACTION READ ONLY`), capped by `statement_timeout`, and rolled back in every case.
 */
object SqlTry {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    private const val MAX_CELL_CHARS = 4096
    private const val MAX_SAMPLE_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = "5"
    private const val SOCKET_TIMEOUT_SECONDS = "20"
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]{0,62}")
    private val TABLE_LIKE = setOf("table", "view")

    class Prepared(
        val schema: String?,
        val table: String,
        val limit: Int,
        val declared: List<DeclaredColumn>,
        /** The dataset's declared `physicalType` (lower-case) — `table`, `view` or null when undeclared. */
        val physicalType: String?,
    ) {
        /** The statement as executed, LIMIT inlined for display (the execution binds it). */
        val statement: String get() = "SELECT * FROM ${quoted()} LIMIT $limit"
        fun quoted() = (schema?.let { "\"$it\"." } ?: "") + "\"$table\""
    }

    class Sample(
        val columns: List<SqlColumnMeta>,
        val rows: List<List<String?>>,
        val truncated: Boolean,
        val nullSeen: Set<String>,
        val durationMs: Long,
        val datasetMissing: Boolean,
    )

    /** The static rules: a well-formed identifier naming a table-like dataset the document declares, a limit in range. */
    fun prepare(request: TrySqlRequest, root: JsonNode): Prepared {
        if (request.limit !in 1..MAX_LIMIT) throw BadRequestException("limit must be 1-$MAX_LIMIT")
        val parts = request.dataset.split('.')
        if (parts.size > 2 || parts.any { !IDENTIFIER.matches(it) }) {
            throw BadRequestException("The dataset must be an identifier, optionally schema-qualified")
        }
        val asked = parts.last()
        val schema = parts.takeIf { it.size == 2 }?.first()
        val datasets = root.path("schema").takeIf { it.isArray }?.toList().orEmpty()
        val index = datasets.indexOfFirst { d ->
            d.path("name").asText().equals(asked, ignoreCase = true) || d.path("physicalName").asText().equals(asked, ignoreCase = true)
        }
        if (index < 0) throw BadRequestException("The document declares no dataset '${request.dataset}'")
        // The relation read is the DECLARED one (physicalName, else name) — never the caller's spelling.
        val table = datasets[index].path("physicalName").textOrNull() ?: datasets[index].path("name").asText()
        if (!IDENTIFIER.matches(table)) throw BadRequestException("The declared dataset name '$table' is not a valid identifier")
        val physicalType = datasets[index].path("physicalType").textOrNull()?.lowercase()
        if (physicalType != null && physicalType !in TABLE_LIKE) {
            throw BadRequestException("Dataset '${request.dataset}' is a $physicalType, not a table or a view")
        }
        val declared = datasets[index].path("properties").takeIf { it.isArray }?.mapIndexedNotNull { j, p ->
            val name = p.path("name").textOrNull() ?: return@mapIndexedNotNull null
            DeclaredColumn(
                name = name,
                physicalName = p.path("physicalName").textOrNull(),
                logicalType = p.path("logicalType").textOrNull(),
                required = p.path("required").asBoolean(false),
                pointer = "/schema/$index/properties/$j",
            )
        }.orEmpty()
        return Prepared(schema, table, request.limit, declared, physicalType)
    }

    /** Runs the sample; a missing relation is an observation (`datasetMissing`), everything else that fails is a 502. */
    suspend fun execute(prepared: Prepared, target: PostgresTarget): Sample = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            connect(target).use { connection -> sample(connection, prepared, started) }
        } catch (e: SQLException) {
            when {
                e.sqlState == UNDEFINED_TABLE ->
                    Sample(emptyList(), emptyList(), false, emptySet(), elapsedMs(started), datasetMissing = true)
                else -> throw BadGatewayException(classify(e))
            }
        }
    }

    /** The declared columns against the observed metadata — the ODCS type families, nullability, the sample's NULLs. */
    fun assess(prepared: Prepared, sample: Sample): ConformanceReport {
        if (sample.datasetMissing) {
            val message = "Relation ${prepared.quoted()} does not exist in the environment's database"
            return ConformanceReport.of(listOf(Conformance.error(OdcsTypes.DATASET_MISSING, message, "/schema")))
        }
        // Only a TABLE's catalog says which columns are NOT NULL; a view's columns all read as nullable.
        val nullabilityKnown = prepared.physicalType == "table"
        val findings: List<Finding> = OdcsTypes.assess(prepared.declared, sample.columns, sample.nullSeen, nullabilityKnown)
        return ConformanceReport.of(findings)
    }

    /** The response's column list — each observed column with the logical type it was measured against. */
    fun columns(prepared: Prepared, sample: Sample): List<SqlColumn> {
        val byPhysical = prepared.declared.associateBy { (it.physicalName ?: it.name).lowercase() }
        return sample.columns.map { c -> SqlColumn(c.name, c.dbType, c.nullable, byPhysical[c.name.lowercase()]?.logicalType) }
    }

    private fun connect(target: PostgresTarget): Connection {
        val props = Properties().apply {
            setProperty("user", target.username)
            target.password?.let { setProperty("password", it) }
            setProperty("readOnly", "true")
            setProperty("readOnlyMode", "always")
            setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS)
            setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
            setProperty("loginTimeout", CONNECT_TIMEOUT_SECONDS)
            setProperty("ApplicationName", "covenant-try")
        }
        return DriverManager.getConnection(target.jdbcUrl, props)
    }

    private fun sample(connection: Connection, prepared: Prepared, started: Long): Sample {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '15s'") }
            return connection.prepareStatement("SELECT * FROM ${prepared.quoted()} LIMIT ?").use { statement ->
                statement.setInt(1, prepared.limit)
                statement.executeQuery().use { rs -> read(rs, started) }
            }
        } finally {
            connection.rollback()
        }
    }

    private fun read(rs: ResultSet, started: Long): Sample {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { i ->
            SqlColumnMeta(meta.getColumnName(i), meta.getColumnTypeName(i), meta.isNullable(i) != ResultSetMetaData.columnNoNulls)
        }
        val rows = mutableListOf<List<String?>>()
        val nullSeen = mutableSetOf<String>()
        var bytes = 0
        var truncated = false
        while (rs.next()) {
            val row = (1..meta.columnCount).map { i -> cell(rs, meta, i)?.also { bytes += it.length } }
            row.forEachIndexed { i, v -> if (v == null) nullSeen += columns[i].name }
            if (bytes > MAX_SAMPLE_BYTES) {
                truncated = true
                break
            }
            rows += row
        }
        return Sample(columns, rows, truncated, nullSeen, elapsedMs(started), datasetMissing = false)
    }

    private fun cell(rs: ResultSet, meta: ResultSetMetaData, i: Int): String? {
        val text = when (meta.getColumnType(i)) {
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rs.getBytes(i)?.let { Base64.getEncoder().encodeToString(it) }
            else -> rs.getString(i)
        } ?: return null
        return if (text.length > MAX_CELL_CHARS) text.take(MAX_CELL_CHARS) + "…" else text
    }

    private fun classify(e: SQLException): String = when {
        e.sqlState == INSUFFICIENT_PRIVILEGE -> "The environment's database role may not read this dataset"
        e.sqlState == QUERY_CANCELED -> "The read timed out in the environment's database"
        e.sqlState?.startsWith(INVALID_AUTHORIZATION_CLASS) == true -> "The environment's database refused the credentials"
        else -> "The environment's database could not be reached"
    }

    private const val UNDEFINED_TABLE = "42P01"
    private const val INSUFFICIENT_PRIVILEGE = "42501"
    private const val QUERY_CANCELED = "57014"
    private const val INVALID_AUTHORIZATION_CLASS = "28"

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual }?.asText()
}
