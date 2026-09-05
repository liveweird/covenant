package ch.nokillswit.contracts.checks

import ch.nokillswit.contracts.ContractType
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The server's client of the checker sidecar (`checker/`, `.claude/docs/checker.md`): one
 * `POST /check` per document. Published under [CheckerClientKey] by `configureChecks` — unless
 * a test pre-set the key (the `ContractUrlFetcherKey` seam), which is how the suite installs a
 * stub and how `HttpCheckerClientTest` points the real client at a 127.0.0.1 fixture.
 * Every failure is [CheckerUnavailableException]: the caller degrades, never fails.
 */
interface CheckerClient {
    suspend fun check(type: ContractType, content: String, previousContent: String? = null): CheckerResponse
}

val CheckerClientKey = AttributeKey<CheckerClient>("CheckerClient")

@Serializable
data class CheckerRequest(val type: ContractType, val content: String, val previousContent: String? = null)

@Serializable
data class EngineVersion(val name: String, val version: String)

@Serializable
data class CheckerResponse(val findings: List<Finding>, val engine: List<EngineVersion> = emptyList())

class CheckerUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The sidecar is switched off (blank `checker.url`): every call is "unavailable" by configuration. */
object DisabledCheckerClient : CheckerClient {
    override suspend fun check(type: ContractType, content: String, previousContent: String?): CheckerResponse =
        throw CheckerUnavailableException("The checker sidecar is not configured (checker.url is blank)")
}

class HttpCheckerClient(
    private val baseUrl: String,
    private val token: String?,
    timeoutMs: Long,
) : CheckerClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(Java) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = minOf(timeoutMs, CONNECT_TIMEOUT_MS)
        }
        expectSuccess = false
    }

    override suspend fun check(type: ContractType, content: String, previousContent: String?): CheckerResponse {
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/check") {
                contentType(ContentType.Application.Json)
                token?.let { header(TOKEN_HEADER, it) }
                setBody(CheckerRequest(type, content, previousContent))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckerUnavailableException("The checker could not be reached: ${e::class.simpleName}", e)
        }
        if (response.status.value != OK) {
            throw CheckerUnavailableException("The checker answered HTTP ${response.status.value}")
        }
        // Decoded by hand, not by content type: a proxy that drops the header must not turn a
        // perfectly good answer into "unavailable".
        return try {
            json.decodeFromString<CheckerResponse>(response.bodyAsText())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckerUnavailableException("The checker answered a malformed body", e)
        }
    }

    private companion object {
        const val TOKEN_HEADER = "X-Checker-Token"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val OK = 200
    }
}
