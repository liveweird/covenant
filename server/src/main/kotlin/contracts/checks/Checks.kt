package ch.nokillswit.contracts.checks

import io.ktor.server.application.*
import org.slf4j.LoggerFactory

/**
 * Publishes the check pipeline. Order: after Flyway, BEFORE `configureDatabase` (the composition
 * root hands the service to ContractVersionService). The checker client honours a [CheckerClientKey]
 * set by a test (resolved lazily — the seam runs after the config modules); otherwise
 * `checker.url` decides — blank means the sidecar is off (every check carries a report-only
 * `CHECKER_UNAVAILABLE` finding; production logs a WARN at boot, but never refuses to start:
 * the JVM's own syntax/schema gates remain).
 */
fun Application.configureChecks() {
    val log = LoggerFactory.getLogger("ch.nokillswit.contracts.checks")
    val url = environment.config.propertyOrNull("checker.url")?.getString()?.trim().orEmpty()
    val token = environment.config.propertyOrNull("checker.token")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
    val timeoutMs = environment.config.propertyOrNull("checker.timeoutMs")?.getString()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
    val configured: CheckerClient = if (url.isEmpty()) {
        val development = environment.config.propertyOrNull("ktor.development")?.getString()?.toBoolean() ?: false
        if (!development) log.warn("checker.url is blank: contract lint/semantic checks are OFF; every check reports CHECKER_UNAVAILABLE")
        DisabledCheckerClient
    } else {
        HttpCheckerClient(url, token, timeoutMs)
    }
    // Resolved at CALL time, not here: the test seam (`application { attributes.put(CheckerClientKey, stub) }`)
    // runs after the config modules, exactly like Toadie's ContractUrlFetcherKey.
    attributes.put(ChecksServiceKey, ChecksService { attributes.getOrNull(CheckerClientKey) ?: configured })
}

private const val DEFAULT_TIMEOUT_MS = 20_000L
