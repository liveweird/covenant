package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.tryit.HttpTry
import ch.nokillswit.contracts.tryit.KafkaRecordView
import io.ktor.server.plugins.BadRequestException

/**
 * The HTTP observe leg: the same static rules and the same `HttpTry.send` as try-it, minus a
 * document to check the operation against — a concrete method and a literal path are the whole
 * shape rule (`HttpTry.prepareRaw`). The sample it builds is the wire shape `POST /contracts/infer`
 * itself reads: header NAMES only, the `Authorization` value reduced to its scheme word, the
 * response body dropped once truncated.
 */
object ObserveHttp {
    private val AUTH_SCHEMES = setOf("bearer", "basic", "digest")

    fun prepare(request: ObserveHttpRequest, baseUrl: String): HttpTry.Prepared =
        HttpTry.prepareRaw(request.method, request.path, request.query, request.headers, request.contentType, request.body, baseUrl)

    fun sample(prepared: HttpTry.Prepared, query: Map<String, String>, observation: HttpTry.Observation, notes: Notes): HttpExchangeSample {
        val authValue = prepared.headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
        val scheme = authValue?.substringBefore(' ')?.lowercase()?.let { if (it in AUTH_SCHEMES) it else "other" }
        val truncated = observation.truncated
        if (truncated) {
            notes.info("INFER_BODY_SKIPPED", "The response body exceeded 1 MiB and was left out of the sample")
        }
        return HttpExchangeSample(
            method = prepared.method,
            url = prepared.uri.toString().substringBefore('?'),
            query = query,
            requestHeaders = prepared.headers.keys.map { it.lowercase() },
            authorizationScheme = scheme,
            requestContentType = prepared.contentType,
            requestBody = prepared.body,
            status = observation.status,
            responseHeaders = observation.headers.keys.toList(),
            responseContentType = observation.contentType,
            responseBody = if (truncated) null else observation.body,
        )
    }
}

/**
 * The Kafka observe leg: `KafkaTry.read` unchanged, reduced to the payloads worth learning a
 * schema from — a tombstone, a binary record or a truncated one carries nothing an inferencer can
 * use, so each is dropped and counted in one `INFER_RECORDS_SKIPPED` note rather than silently.
 */
object ObserveKafka {
    private val TOPIC = Regex("^[A-Za-z0-9._-]{1,249}$")

    fun validateTopic(topic: String) {
        if (!TOPIC.matches(topic)) throw BadRequestException("The topic name is not valid")
    }

    fun sample(topic: String, views: List<KafkaRecordView>, notes: Notes): MessageBatchSample {
        val kept = views.filter { it.payload != null && it.encoding == "utf8" && !it.truncated }
        val skipped = views.size - kept.size
        if (skipped > 0) {
            notes.info("INFER_RECORDS_SKIPPED", "$skipped of ${views.size} records skipped — binary or cut at 64 KiB")
        }
        return MessageBatchSample(topic, kept.map { it.payload!! })
    }
}
