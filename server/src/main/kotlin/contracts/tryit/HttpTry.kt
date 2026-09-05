package ch.nokillswit.contracts.tryit

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.contracts.checks.Finding
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The HTTP leg of try-it: ONE request against an operation the document declares, through the
 * environment's base URL (which REPLACES the document's `servers[]` — the document contributes
 * the method and the path template only). The trust boundary is the ADMIN-curated environment
 * registry, not a public-host guard (see security.md "Outbound connections to environments");
 * what stays load-bearing here: redirects are never followed (a 3xx is shown as the response),
 * bodies are bounded both ways, hop-by-hop and cookie headers never cross in either direction,
 * and the caller's own headers (credentials included) are forwarded but never stored or logged.
 */
object HttpTry {
    const val MAX_BODY_BYTES = 1024 * 1024
    private const val MAX_HEADERS = 20
    private const val MAX_QUERY_PARAMS = 50
    private const val MAX_HEADER_VALUE_LENGTH = 4096
    private const val MAX_RESPONSE_HEADERS = 50
    private const val TIMEOUT_SECONDS = 10L
    private val TEMPLATE_PARAM = Regex("\\{([^/{}]+)}")
    private val HEADER_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val REQUEST_HEADER_DENY = setOf(
        "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "keep-alive", "expect", "cookie",
    )
    private val RESPONSE_HEADER_DROP = setOf(
        "set-cookie", "set-cookie2", "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authenticate",
    )

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    /** What goes over the wire, after every static rule has passed. */
    class Prepared(val method: String, val uri: URI, val headers: Map<String, String>, val contentType: String?, val body: String?)

    /** The observation — what came back, bounded. */
    class Observation(
        val status: Int,
        val headers: Map<String, String>,
        val contentType: String?,
        val body: String?,
        val truncated: Boolean,
        val durationMs: Long,
    )

    /** Every 400-shaped rule: the operation must be declared, the template fully bound, headers clean, the body allowed. */
    fun prepare(request: TryHttpRequest, root: JsonNode, baseUrl: String): Prepared {
        val method = request.method.uppercase()
        val opPointer = "/paths/${TryCatalog.esc(request.path)}/${method.lowercase()}"
        if (method.lowercase() !in TryCatalog.METHODS || !root.at(opPointer).isObject) {
            throw BadRequestException("The document declares no $method ${request.path} operation")
        }
        val path = bindTemplate(request.path, request.pathParams)
        val query = buildQuery(request.query)
        val headers = validateHeaders(request.headers)
        val body = request.body?.takeIf { it.isNotEmpty() }
        if (body != null) {
            if (root.at("$opPointer/requestBody").isMissingNode) throw BadRequestException("The operation declares no request body")
            if (body.toByteArray().size > MAX_BODY_BYTES) throw PayloadTooLargeException(MAX_BODY_BYTES.toLong())
        }
        val uri = URI.create(baseUrl + path + query)
        return Prepared(method, uri, headers, request.contentType?.takeIf { it.isNotBlank() } ?: body?.let { "application/json" }, body)
    }

    /** Sends it — a target that cannot be reached is a 502; whatever the target answers is the observation. */
    suspend fun send(prepared: Prepared): Observation = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(prepared.uri).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        prepared.headers.forEach { (name, value) -> builder.header(name, value) }
        val publisher = prepared.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        if (prepared.body != null && prepared.contentType != null) builder.header("Content-Type", prepared.contentType)
        val started = System.nanoTime()
        val response = try {
            client.send(builder.method(prepared.method, publisher).build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: IOException) {
            throw BadGatewayException(UNREACHABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BadGatewayException(UNREACHABLE)
        }
        val bytes = response.body().use { it.readNBytes(MAX_BODY_BYTES + 1) }
        val truncated = bytes.size > MAX_BODY_BYTES
        val text = String(if (truncated) bytes.copyOf(MAX_BODY_BYTES) else bytes, Charsets.UTF_8)
        Observation(
            status = response.statusCode(),
            headers = collectHeaders(response.headers().map()),
            contentType = response.headers().firstValue("content-type").orElse(null),
            body = text.takeIf { bytes.isNotEmpty() },
            truncated = truncated,
            durationMs = elapsedMs(started),
        )
    }

    /** The request measured before it goes out (WARNs) plus the response measured against the declared shape. */
    fun assess(
        request: TryHttpRequest,
        prepared: Prepared,
        observation: Observation,
        root: JsonNode,
        schemas: DocumentSchemas,
    ): ConformanceReport {
        val before: List<Finding> =
            HttpConformance.assessRequest(root, schemas, prepared.method, request.path, prepared.contentType, prepared.body)
        val body = if (observation.truncated) null else observation.body
        val after = HttpConformance.assessResponse(
            root, schemas, prepared.method, request.path, observation.status, observation.contentType, body, observation.headers,
        )
        val truncatedNote = if (observation.truncated) {
            listOf(Conformance.info(Conformance.BODY_NOT_VALIDATED, "The response body exceeded 1 MiB and was not validated"))
        } else {
            emptyList()
        }
        return ConformanceReport.of(before + after.findings + truncatedNote, after.validatedAgainst)
    }

    private fun bindTemplate(template: String, params: Map<String, String>): String {
        val names = TEMPLATE_PARAM.findAll(template).map { it.groupValues[1] }.toSet()
        val missing = names - params.keys
        if (missing.isNotEmpty()) throw BadRequestException("Path parameter '${missing.first()}' is missing")
        val extra = params.keys - names
        if (extra.isNotEmpty()) throw BadRequestException("Path parameter '${extra.first()}' is not in the template")
        return TEMPLATE_PARAM.replace(template) { m -> encode(params.getValue(m.groupValues[1])) }
    }

    private fun buildQuery(query: Map<String, String>): String {
        if (query.isEmpty()) return ""
        if (query.size > MAX_QUERY_PARAMS) throw BadRequestException("At most $MAX_QUERY_PARAMS query parameters")
        query.forEach { (k, v) ->
            if (k.isBlank() || k.length > MAX_HEADER_VALUE_LENGTH || v.length > MAX_HEADER_VALUE_LENGTH) {
                throw BadRequestException("Query parameter names and values must be 1-$MAX_HEADER_VALUE_LENGTH characters")
            }
        }
        return "?" + query.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    private fun validateHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.size > MAX_HEADERS) throw BadRequestException("At most $MAX_HEADERS headers")
        headers.forEach { (name, value) ->
            if (!HEADER_TOKEN.matches(name)) throw BadRequestException("Header name '$name' is not a valid token")
            if (name.lowercase() in REQUEST_HEADER_DENY || name.lowercase().startsWith("proxy-")) {
                throw BadRequestException("Header '$name' is set by the server and cannot be overridden")
            }
            if (value.length > MAX_HEADER_VALUE_LENGTH || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                throw BadRequestException("Header '$name' has an invalid value")
            }
        }
        return headers
    }

    private fun collectHeaders(raw: Map<String, List<String>>): Map<String, String> =
        raw.entries.asSequence()
            .filter { (name, _) -> name != null && name.lowercase() !in RESPONSE_HEADER_DROP && !name.startsWith(":") }
            .take(MAX_RESPONSE_HEADERS)
            .associate { (name, values) -> name.lowercase() to values.joinToString(", ") }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    const val UNREACHABLE = "The environment could not be reached"
}
