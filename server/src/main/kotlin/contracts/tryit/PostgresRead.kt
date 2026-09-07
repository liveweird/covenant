package ch.nokillswit.contracts.tryit

import ch.nokillswit.environments.PostgresTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * The read-only PostgreSQL connection every leg that reads an environment's database shares
 * (`SqlTry`'s dataset sample, `contracts/infer/ObserveSql.kt`'s catalog describe/list): connect
 * with the driver's own read-only flags, `SET TRANSACTION READ ONLY` + a 15 s
 * `statement_timeout`, run [block], roll back in every case (a read never commits, successful or
 * not). [classify] turns a `SQLException` into the one detail line a `502` may show — never the
 * URL or the credentials.
 */
object PostgresRead {
    private const val CONNECT_TIMEOUT_SECONDS = "5"
    private const val SOCKET_TIMEOUT_SECONDS = "20"
    private const val STATEMENT_TIMEOUT_SQL = "SET LOCAL statement_timeout = '15s'"
    private const val INSUFFICIENT_PRIVILEGE = "42501"
    private const val QUERY_CANCELED = "57014"
    private const val INVALID_AUTHORIZATION_CLASS = "28"

    suspend fun <T> readOnly(target: PostgresTarget, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        connect(target).use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
                connection.createStatement().use { it.execute(STATEMENT_TIMEOUT_SQL) }
                block(connection)
            } finally {
                connection.rollback()
            }
        }
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

    fun classify(e: SQLException): String = when {
        e.sqlState == INSUFFICIENT_PRIVILEGE -> "The environment's database role may not read this dataset"
        e.sqlState == QUERY_CANCELED -> "The read timed out in the environment's database"
        e.sqlState?.startsWith(INVALID_AUTHORIZATION_CLASS) == true -> "The environment's database refused the credentials"
        else -> "The environment's database could not be reached"
    }
}
