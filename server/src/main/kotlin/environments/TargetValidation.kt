package ch.nokillswit.environments

import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import java.net.URI
import java.net.URISyntaxException

/**
 * The environment rules — enforced by the route AND re-checked by the service. Static checks
 * only: no DNS, no connection attempt (that is the try's job). The registry is ADMIN-only, so the
 * public-host guard of the URL fetch does NOT apply — internal hosts are the point — but the
 * shapes that turn a stored target into something else are refused: userinfo in a URL, a JDBC
 * URL parameter outside the allow-list (driver-side code loading), a link-local (cloud-metadata)
 * HTTP host literal.
 */
const val ENVIRONMENT_NEEDS_TARGET = "An environment needs at least one target (HTTP, Kafka or PostgreSQL)"
const val HTTP_BASE_URL_INVALID = "httpBaseUrl must be an absolute http(s) URL without credentials, query or fragment"
const val BOOTSTRAP_SERVERS_INVALID = "kafka.bootstrapServers must be 1-20 comma-separated host:port entries"
const val JDBC_URL_INVALID = "postgres.jdbcUrl must start with jdbc:postgresql:// and name a host and database"

private val HOST_PORT = Regex("^[A-Za-z0-9.\\-]+:(\\d{1,5})$")
private val JDBC_PARAM_ALLOW_LIST = setOf("ssl", "sslmode", "currentSchema", "ApplicationName")
private const val MAX_BOOTSTRAP_ENTRIES = 20
private const val MAX_PORT = 65535

fun sanitizedEnvironmentRequest(request: EnvironmentRequest): EnvironmentRequest = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    httpBaseUrl = request.httpBaseUrl?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/'),
    kafka = request.kafka?.let { k ->
        k.copy(
            bootstrapServers = k.bootstrapServers.replace(" ", ""),
            username = k.username?.trim()?.takeIf { it.isNotBlank() },
            password = k.password?.takeIf { it.isNotEmpty() },
        )
    },
    postgres = request.postgres?.let { p ->
        p.copy(jdbcUrl = p.jdbcUrl.trim(), username = p.username.trim(), password = p.password?.takeIf { it.isNotEmpty() })
    },
)

/**
 * @param passwordRequired whether a target declaring credentials must carry a password NOW — a
 *   create, or a PUT onto a row that has none stored (the service knows; the route passes `false`).
 */
fun validateEnvironmentRequest(request: EnvironmentRequest, passwordRequired: Boolean) {
    requireNameAndDescription(request.name, request.description, MAX_ENVIRONMENT_NAME_LENGTH, MAX_ENVIRONMENT_DESCRIPTION_LENGTH)
    if (request.httpBaseUrl == null && request.kafka == null && request.postgres == null) {
        throw BadRequestException(ENVIRONMENT_NEEDS_TARGET)
    }
    request.httpBaseUrl?.let { validateHttpBaseUrl(it) }
    request.kafka?.let { validateKafka(it, passwordRequired) }
    request.postgres?.let { validatePostgres(it, passwordRequired) }
}

private fun validateHttpBaseUrl(raw: String) {
    if (raw.length > MAX_TARGET_URL_LENGTH) throw BadRequestException(HTTP_BASE_URL_INVALID)
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        throw BadRequestException(HTTP_BASE_URL_INVALID)
    }
    val scheme = uri.scheme?.lowercase()
    val ok = uri.isAbsolute && (scheme == "http" || scheme == "https") && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && !uri.host.isNullOrBlank()
    if (!ok) throw BadRequestException(HTTP_BASE_URL_INVALID)
    val host = uri.host.trim('[', ']').lowercase()
    if (host.startsWith("169.254.") || host.startsWith("fe80:")) {
        throw BadRequestException("httpBaseUrl must not point at a link-local address")
    }
}

private fun validateKafka(k: KafkaTargetRequest, passwordRequired: Boolean) {
    val entries = k.bootstrapServers.split(',').filter { it.isNotBlank() }
    val sized = entries.isNotEmpty() && entries.size <= MAX_BOOTSTRAP_ENTRIES && k.bootstrapServers.length <= MAX_BOOTSTRAP_SERVERS_LENGTH
    fun validPort(entry: String) = HOST_PORT.matchEntire(entry)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 1..MAX_PORT } == true
    val shapely = sized && entries.all(::validPort)
    if (!shapely) throw BadRequestException(BOOTSTRAP_SERVERS_INVALID)
    val sasl = k.securityProtocol == KafkaSecurityProtocol.SASL_PLAINTEXT || k.securityProtocol == KafkaSecurityProtocol.SASL_SSL
    if (sasl) {
        if (k.saslMechanism == null) throw BadRequestException("kafka.saslMechanism is required for a SASL security protocol")
        if (k.username.isNullOrBlank()) throw BadRequestException("kafka.username is required for a SASL security protocol")
        if (passwordRequired && k.password == null) throw BadRequestException("kafka.password is required for a SASL security protocol")
    } else if (k.saslMechanism != null || k.username != null || k.password != null) {
        throw BadRequestException("kafka.saslMechanism, username and password apply only to a SASL security protocol")
    }
    validateCredentialLengths(k.username, k.password)
}

private fun validatePostgres(p: PostgresTargetRequest, passwordRequired: Boolean) {
    if (!p.jdbcUrl.startsWith("jdbc:postgresql://") || p.jdbcUrl.length > MAX_TARGET_URL_LENGTH) throw BadRequestException(JDBC_URL_INVALID)
    if (org.postgresql.Driver.parseURL(p.jdbcUrl, null) == null) throw BadRequestException(JDBC_URL_INVALID)
    val query = p.jdbcUrl.substringAfter('?', missingDelimiterValue = "")
    query.split('&').filter { it.isNotBlank() }.map { it.substringBefore('=') }.forEach { key ->
        if (key !in JDBC_PARAM_ALLOW_LIST) throw BadRequestException("postgres.jdbcUrl parameter '$key' is not allowed")
    }
    if (p.username.isBlank()) throw BadRequestException("postgres.username is required")
    if (passwordRequired && p.password == null) throw BadRequestException("postgres.password is required")
    validateCredentialLengths(p.username, p.password)
}

private fun validateCredentialLengths(username: String?, password: String?) {
    if (username != null && username.length > MAX_TARGET_USERNAME_LENGTH) {
        throw BadRequestException("username must be at most $MAX_TARGET_USERNAME_LENGTH characters")
    }
    if (password != null && password.length > MAX_TARGET_PASSWORD_LENGTH) {
        throw BadRequestException("password must be at most $MAX_TARGET_PASSWORD_LENGTH characters")
    }
}
