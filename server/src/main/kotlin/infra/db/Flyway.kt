package ch.nokillswit.infra.db

import io.ktor.server.application.*
import java.net.URI
import org.flywaydb.core.Flyway

fun Application.configureFlyway() {
    val url = environment.config.property("postgres.jdbcUrl").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()

    log.info("Running Flyway migrations against ${jdbcTarget(url)}")
    val result = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    log.info("Flyway applied ${result.migrationsExecuted} migration(s); schema at version ${result.targetSchemaVersion}")
}

/** A credential-free host[:port]/database target suitable for startup logs. */
internal fun jdbcTarget(url: String): String {
    val fallback = "jdbc:…"
    if (!url.startsWith("jdbc:")) return fallback
    val uri = runCatching { URI(url.removePrefix("jdbc:")) }.getOrNull() ?: return fallback
    // A missing host covers opaque and multi-host JDBC forms as well as malformed userinfo.
    // Falling back to the scheme is safer than attempting to redact ambiguous delimiters.
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return fallback
    val displayHost = when {
        host.startsWith('[') && host.endsWith(']') -> host
        ':' in host -> "[$host]"
        else -> host
    }
    val target = if (uri.port >= 0) "$displayHost:${uri.port}" else displayHost
    val database = uri.rawPath.orEmpty().removePrefix("/").substringBefore(';')
    return if (database.isEmpty()) target else "$target/$database"
}
