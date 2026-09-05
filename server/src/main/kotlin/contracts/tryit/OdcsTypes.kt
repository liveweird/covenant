package ch.nokillswit.contracts.tryit

import ch.nokillswit.contracts.checks.Finding

/** One column as the database describes it (`ResultSetMetaData`). */
data class SqlColumnMeta(val name: String, val dbType: String, val nullable: Boolean)

/** A declared ODCS property the SQL leg checks a column against. */
data class DeclaredColumn(val name: String, val physicalName: String?, val logicalType: String?, val required: Boolean, val pointer: String)

/**
 * The ODCS leg: the dataset's declared properties against the columns a `SELECT` returned. The
 * `logicalType` → PostgreSQL type-name families below are the whole mapping; a type name outside
 * every family is an INFO (never a mismatch), so an exotic column type stays honest rather than red.
 */
object OdcsTypes {
    const val DATASET_MISSING = "DATASET_MISSING"
    const val COLUMN_MISSING = "COLUMN_MISSING"
    const val COLUMN_EXTRA = "COLUMN_EXTRA"
    const val COLUMN_TYPE_MISMATCH = "COLUMN_TYPE_MISMATCH"
    const val COLUMN_TYPE_UNKNOWN = "COLUMN_TYPE_UNKNOWN"
    const val COLUMN_NULLABLE_MISMATCH = "COLUMN_NULLABLE_MISMATCH"
    const val REQUIRED_VALUE_NULL = "REQUIRED_VALUE_NULL"

    private val FAMILIES: Map<String, Set<String>> = mapOf(
        "string" to setOf(
            "varchar", "text", "bpchar", "char", "name", "uuid", "citext", "inet", "cidr", "macaddr", "xml", "interval",
            "character varying", "character",
        ),
        "integer" to setOf("int2", "int4", "int8", "smallint", "integer", "bigint", "serial", "bigserial", "oid", "smallserial"),
        "number" to setOf("numeric", "decimal", "float4", "float8", "real", "double precision", "money"),
        "boolean" to setOf("bool", "boolean"),
        "date" to setOf(
            "date", "timestamp", "timestamptz", "time", "timetz", "timestamp without time zone", "timestamp with time zone",
            "time without time zone", "time with time zone",
        ),
        "object" to setOf("json", "jsonb", "hstore", "record", "struct"),
    )

    private val KNOWN_TYPES: Set<String> = FAMILIES.values.flatten().toSet()

    /** Whether a PostgreSQL type name belongs to the logical type's family; null = the type name is unknown to the table. */
    fun matches(logicalType: String, dbType: String): Boolean? {
        val t = dbType.lowercase().trim()
        val array = t.startsWith("_") || t.endsWith("[]") || t == "array"
        if (logicalType == "array") return array
        if (array) return false
        if (t !in KNOWN_TYPES) return null
        return FAMILIES[logicalType]?.contains(t) ?: false
    }

    /** @param nullSeen the declared property names for which the sample returned at least one NULL */
    fun assess(properties: List<DeclaredColumn>, columns: List<SqlColumnMeta>, nullSeen: Set<String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        val byName = columns.associateBy { it.name.lowercase() }
        properties.forEach { p ->
            val column = byName[(p.physicalName ?: p.name).lowercase()]
            if (column == null) {
                findings += Conformance.error(COLUMN_MISSING, "Declared column '${p.name}' is missing from the dataset", p.pointer)
                return@forEach
            }
            findings += typeFindings(p, column)
            if (p.required && column.nullable) {
                val message = "Column '${p.name}' is declared required but the database allows NULL"
                findings += Conformance.warn(COLUMN_NULLABLE_MISMATCH, message, "${p.pointer}/required")
            }
            if (p.required && p.name in nullSeen) {
                val message = "Column '${p.name}' is declared required but the sample holds a NULL"
                findings += Conformance.warn(REQUIRED_VALUE_NULL, message, "${p.pointer}/required")
            }
        }
        val declared = properties.map { (it.physicalName ?: it.name).lowercase() }.toSet()
        columns.filter { it.name.lowercase() !in declared }.forEach { c ->
            findings += Conformance.warn(COLUMN_EXTRA, "Column '${c.name}' (${c.dbType}) is not declared in the contract")
        }
        return findings
    }

    private fun typeFindings(p: DeclaredColumn, column: SqlColumnMeta): List<Finding> {
        val logical = p.logicalType ?: return emptyList()
        return when (matches(logical, column.dbType)) {
            true -> emptyList()
            false -> listOf(
                Conformance.error(
                    COLUMN_TYPE_MISMATCH,
                    "Column '${p.name}' is declared $logical but the database type is ${column.dbType}",
                    "${p.pointer}/logicalType",
                ),
            )
            null -> listOf(
                Conformance.info(
                    COLUMN_TYPE_UNKNOWN,
                    "Column '${p.name}' has the database type ${column.dbType}, which Covenant cannot map to '$logical'",
                    "${p.pointer}/logicalType",
                ),
            )
        }
    }
}
