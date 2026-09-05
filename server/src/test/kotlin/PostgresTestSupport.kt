package ch.nokillswit

import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresTestSupport {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18-alpine").apply {
            withDatabaseName("covenant_test")
            withUsername("covenant")
            withPassword("covenant")
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val user: String get() = container.username
    val password: String get() = container.password
    /** The URL WITHOUT Testcontainers' driver parameters — the shape the environments registry admits. */
    val plainJdbcUrl: String
        get() = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
