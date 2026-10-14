package ch.nokillswit.infra.db

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import io.ktor.server.application.*
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration

/** Bounded application-database connections, ported from Flow/Lettuce. Environment SQL uses separate JDBC connections. */
internal fun Application.connectPooled(): R2dbcDatabase {
    val config = environment.config
    val maxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    val initialSize = requireConfigInt(config, "postgres.pool.initialSize", min = 0, max = maxSize)
    val acquireSeconds = requireConfigLong(config, "postgres.pool.maxAcquireTimeSeconds", min = 1, max = 600)
    val idleSeconds = requireConfigLong(config, "postgres.pool.maxIdleTimeSeconds", min = 1, max = 86400)
    val options = ConnectionFactoryOptions.parse(config.property("postgres.r2dbcUrl").getString())
        .mutate()
        .option(ConnectionFactoryOptions.USER, config.property("postgres.user").getString())
        .option(ConnectionFactoryOptions.PASSWORD, config.property("postgres.password").getString())
        .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME,
            config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "covenant")
        // Long-lived connections must not retain an unbounded prepared-statement cache.
        .option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES, 256)
        .build()
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(ConnectionFactories.get(options))
            .maxSize(maxSize)
            .initialSize(initialSize)
            // Keep the pool's one acquire retry: it replaces a connection closed by a database restart.
            // A saturated caller may therefore wait up to twice this configured acquire interval.
            .maxAcquireTime(Duration.ofSeconds(acquireSeconds))
            .maxIdleTime(Duration.ofSeconds(idleSeconds))
            .build(),
    )
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        // Exposed derives the PostgreSQL dialect from these raw options, while traffic uses the pool.
        connectionFactoryOptions = options
        // Exposed retries every R2dbcException, including pool timeouts. One attempt deliberately
        // also exposes deadlocks/serialization failures instead of replaying a transaction body.
        defaultMaxAttempts = 1
    }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = databaseConfig)
}
