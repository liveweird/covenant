package ch.nokillswit.plugins

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.plugins.bodylimit.*

/**
 * Global request-body ceiling: a memory-DoS backstop, not a business rule — every payload
 * field already carries its own maxLength, and the largest legitimate body (a bulk
 * catalog-file import) sits orders of magnitude below this. Exceeding it answers 413
 * (problem body via ErrorHandling.kt).
 */
const val MAX_REQUEST_BODY_BYTES: Long = 10L * 1024 * 1024

fun Application.configureHttp() {
    install(RequestBodyLimit) {
        bodyLimit { MAX_REQUEST_BODY_BYTES }
    }
    install(CachingHeaders) {
        options { call, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                // The SPA's static assets are content-hashed by Vite, so a day-long cache is
                // safe for both stylesheets and scripts (both JS media types — the served one
                // depends on the container's mime mapping). index.html stays uncached: it is
                // the un-hashed entry point that must pick up new asset names on deploy.
                ContentType.Text.CSS,
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
                else -> null
            }
        }
    }
    // CORS is installed only when a cross-origin caller actually exists (an explicit allow-list
    // via CORS_ALLOWED_HOSTS). Production is single-origin (Ktor serves the SPA) and local dev
    // goes through the Vite proxy, so the default is: no CORS plugin, browsers enforce
    // same-origin, and no Access-Control-* headers are emitted.
    val corsHosts = environment.config.propertyOrNull("http.corsHosts")?.getString()
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        .orEmpty()
    if (corsHosts.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }
    // Behind a TLS-terminating reverse proxy / ingress, trust X-Forwarded-* so the client IP
    // (rate-limit buckets) and scheme (HTTPS redirect) are the real client's, not the proxy's.
    // Off by default: honoring these headers from direct clients would let them spoof both.
    if (environment.config.propertyOrNull("http.behindProxy")?.getString()?.toBoolean() == true) {
        val proxyHops = requireConfigInt(environment.config, "http.proxyHops", min = 1)
        // A proxy may append its own X-Forwarded-For line instead of merging it with one the
        // client sent. Ktor reads only the first physical line, so fold all lines before choosing
        // the trusted hop from the end of the chain.
        intercept(ApplicationCallPipeline.Setup) {
            resolveForwardedForOrigin(call, proxyHops)
        }
        install(XForwardedHeaders) {
            // Trust only the canonical headers the proxy contract overwrites. Ktor's aliases may
            // otherwise pass through from an untrusted client.
            hostHeaders.clear()
            hostHeaders.add(HttpHeaders.XForwardedHost)
            protoHeaders.clear()
            protoHeaders.add(HttpHeaders.XForwardedProto)
            httpsFlagHeaders.clear()
            // The redirect uses its configured HTTPS port. Ignoring forwarded ports also avoids
            // Ktor throwing on a client-supplied non-numeric value.
            portHeaders.clear()
            // Resolved above after folding every physical header line.
            forHeaders.clear()
        }
    }
    install(Compression)
    install(DefaultHeaders)
    if (!developmentMode) {
        install(HSTS) {
            includeSubDomains = true
        }
        install(HttpsRedirect) {
            // The port to redirect to. By default 443, the default HTTPS port.
            sslPort = 443
            // 301 Moved Permanently, or 302 Found redirect.
            permanentRedirect = true
            // Probes are deliberately public and status-only. Let direct loopback/container
            // checks reach the actual handlers even when no trusted proxy is configured.
            exclude { call ->
                call.request.path() == "/api/v1/health" || call.request.path() == "/api/v1/ready"
            }
        }
    }
    // Swagger UI + the full spec are an API roadmap for anyone who can reach the host, so they
    // are served only in development mode — or when explicitly re-enabled for a trusted
    // environment via HTTP_EXPOSE_OPENAPI=true. (Bearer-token auth cannot protect a
    // browser-loaded UI: page loads carry no Authorization header.)
    val exposeOpenApi = environment.config.propertyOrNull("http.exposeOpenApi")?.getString()
        ?.takeIf { it.isNotBlank() }?.toBoolean()
        ?: developmentMode
    if (exposeOpenApi) {
        // swaggerUI serves both the UI page and the spec (GET /openapi/documentation.yaml).
        routing {
            swaggerUI(path = "openapi")
        }
    }
}

/**
 * Fold every physical X-Forwarded-For line and select the hop reported immediately before the
 * configured number of trusted proxies. If the chain is shorter than configured, use its last
 * value, matching Ktor's safe fallback rather than indexing outside the list.
 */
private fun resolveForwardedForOrigin(call: ApplicationCall, proxyHops: Int) {
    val hops = call.request.headers.getAll(HttpHeaders.XForwardedFor)
        ?.flatMap { it.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: return
    val chosen = hops.getOrNull(hops.size - proxyHops) ?: hops.last()
    val origin = call.mutableOriginConnectionPoint
    origin.remoteHost = chosen
    // Match Ktor's distinction between a hostname and an address. The helper used by Ktor is
    // internal, so retain the same observable rule here.
    if (chosen.contains(':') || chosen.none { it.isLetter() }) origin.remoteAddress = chosen
}
