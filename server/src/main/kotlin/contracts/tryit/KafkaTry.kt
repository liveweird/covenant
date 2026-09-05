package ch.nokillswit.contracts.tryit

import ch.nokillswit.authz.BadGatewayException
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.environments.KafkaTarget
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.apache.kafka.common.header.internals.RecordHeader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The Kafka leg of try-it: publish ONE record onto a channel's topic (contract writers only — the
 * one try that mutates a real system with the environment's stored principal), or read the newest
 * records of it (bounded, no consumer group, nothing committed). The topic is the channel's
 * `address` (a templated address is refused in this cut); the message named — or the channel's only
 * one — gives the payload schema the conformance check measures against.
 */
object KafkaTry {
    const val DEFAULT_READ_LIMIT = 10
    const val MAX_READ_LIMIT = 50
    private const val MAX_HEADERS = 20
    private const val MAX_HEADER_LENGTH = 4096
    private const val MAX_RECORD_PAYLOAD_BYTES = 64 * 1024
    private const val SEND_TIMEOUT_SECONDS = 10L
    private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(2)
    private const val POLL_MS = 250L
    private const val READ_BUDGET_MS = 5_000L
    const val PAYLOAD_SENT_AS_JSON = "PAYLOAD_SENT_AS_JSON"

    class Prepared(val topic: String, val message: KafkaMessageSummary?)

    class Published(val metadata: RecordMetadata, val durationMs: Long)

    class Read(val records: List<ConsumerRecord<ByteArray, ByteArray>>, val reachedEnd: Boolean, val durationMs: Long)

    /** The channel must be declared, its address literal, and the message unambiguous. */
    fun prepare(root: JsonNode, channel: String, messageName: String?): Prepared {
        val summary = TryCatalog.build(ContractType.ASYNCAPI, root).kafka.firstOrNull { it.channel == channel }
            ?: throw BadRequestException("The document declares no channel '$channel'")
        if (summary.address.contains('{')) {
            throw BadRequestException("Channel '$channel' has a templated address (${summary.address}) — not supported yet")
        }
        val message = when {
            messageName != null -> summary.messages.firstOrNull { it.name == messageName }
                ?: throw BadRequestException("Channel '$channel' declares no message '$messageName'")
            summary.messages.size > 1 -> throw BadRequestException("Channel '$channel' declares several messages — name one")
            else -> summary.messages.firstOrNull()
        }
        return Prepared(summary.address, message)
    }

    fun validateHeaders(headers: Map<String, String>) {
        if (headers.size > MAX_HEADERS) throw BadRequestException("At most $MAX_HEADERS headers")
        headers.forEach { (k, v) ->
            if (k.isBlank() || k.length > MAX_HEADER_LENGTH || v.length > MAX_HEADER_LENGTH) {
                throw BadRequestException("Header names and values must be 1-$MAX_HEADER_LENGTH characters")
            }
        }
    }

    /** The payload measured BEFORE it is sent — the record goes out regardless (the point is to observe). */
    fun assessPublish(root: JsonNode, schemas: DocumentSchemas, prepared: Prepared, payload: String): ConformanceReport {
        val message = prepared.message
        val findings: List<Finding> =
            PayloadConformance.assess(root, schemas, message?.payloadPointer, message?.schemaFormat, payload, "/payload")
        val avroNote = if (message?.schemaFormat?.lowercase()?.contains("avro") == true) {
            listOf(Conformance.info(PAYLOAD_SENT_AS_JSON, "Avro payloads are sent as their JSON encoding, not Avro binary", "/payload"))
        } else {
            emptyList()
        }
        return ConformanceReport.of(findings + avroNote, message?.payloadPointer)
    }

    suspend fun publish(target: KafkaTarget, prepared: Prepared, request: TryKafkaPublishRequest): Published = withContext(
        Dispatchers.IO,
    ) {
        val bytes = request.payload.toByteArray()
        if (bytes.size > KafkaClients.MAX_PUBLISH_BYTES) throw PayloadTooLargeException(KafkaClients.MAX_PUBLISH_BYTES.toLong())
        val started = System.nanoTime()
        val record = ProducerRecord<ByteArray, ByteArray>(prepared.topic, request.key?.toByteArray(), bytes)
        request.headers.forEach { (k, v) -> record.headers().add(RecordHeader(k, v.toByteArray())) }
        try {
            val producer = KafkaProducer<ByteArray, ByteArray>(KafkaClients.producer(target))
            try {
                val metadata = producer.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Published(metadata, elapsedMs(started))
            } finally {
                producer.close(CLOSE_TIMEOUT) // bounded: a wedged connection must not hold the request thread
            }
        } catch (e: ExecutionException) {
            throw BadGatewayException(classify(e.cause ?: e))
        } catch (e: java.util.concurrent.TimeoutException) {
            throw BadGatewayException(classify(e))
        } catch (e: KafkaException) {
            throw BadGatewayException(classify(e))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BadGatewayException(classify(e))
        }
    }

    suspend fun read(target: KafkaTarget, prepared: Prepared, limit: Int): Read = withContext(Dispatchers.IO) {
        if (limit !in 1..MAX_READ_LIMIT) throw BadRequestException("limit must be 1-$MAX_READ_LIMIT")
        val started = System.nanoTime()
        try {
            val consumer = KafkaConsumer<ByteArray, ByteArray>(KafkaClients.consumer(target, limit))
            try {
                tail(consumer, prepared.topic, limit, started)
            } finally {
                consumer.close(CLOSE_TIMEOUT)
            }
        } catch (e: KafkaException) {
            throw BadGatewayException(classify(e))
        }
    }

    /** Every record measured against the message's payload schema, paths under `/messages/{i}/payload`. */
    fun assessRead(root: JsonNode, schemas: DocumentSchemas, prepared: Prepared, views: List<KafkaRecordView>): ConformanceReport {
        val message = prepared.message
        val findings = views.flatMapIndexed { i, view ->
            val prefix = "/messages/$i/payload"
            when {
                view.payload == null -> listOf(Conformance.warn(Conformance.PAYLOAD_NOT_JSON, "A tombstone - no payload", prefix))
                view.encoding != "utf8" -> listOf(Conformance.info(Conformance.PAYLOAD_NOT_VALIDATED, "Binary - not validated", prefix))
                view.truncated -> listOf(Conformance.info(Conformance.PAYLOAD_NOT_VALIDATED, "The payload was cut at 64 KiB", prefix))
                else -> PayloadConformance.assess(root, schemas, message?.payloadPointer, message?.schemaFormat, view.payload, prefix)
            }
        }
        return ConformanceReport.of(findings, message?.payloadPointer)
    }

    fun view(record: ConsumerRecord<ByteArray, ByteArray>): KafkaRecordView {
        val value = record.value()
        val cut = value != null && value.size > MAX_RECORD_PAYLOAD_BYTES
        val shown = if (cut) value.copyOf(MAX_RECORD_PAYLOAD_BYTES) else value
        val text = shown?.let { utf8OrNull(it) }
        return KafkaRecordView(
            partition = record.partition(),
            offset = record.offset(),
            timestamp = record.timestamp(),
            key = record.key()?.let { utf8OrNull(it) ?: Base64.getEncoder().encodeToString(it) },
            headers = record.headers().associate { it.key() to (utf8OrNull(it.value()) ?: Base64.getEncoder().encodeToString(it.value())) },
            payload = text ?: shown?.let { Base64.getEncoder().encodeToString(it) },
            encoding = if (shown == null || text != null) "utf8" else "base64",
            truncated = cut,
        )
    }

    private fun tail(consumer: KafkaConsumer<ByteArray, ByteArray>, topic: String, limit: Int, started: Long): Read {
        val partitions = consumer.partitionsFor(topic).orEmpty().map { TopicPartition(topic, it.partition()) }
        if (partitions.isEmpty()) throw BadGatewayException("Topic '$topic' does not exist in the environment's cluster")
        consumer.assign(partitions)
        val ends = consumer.endOffsets(partitions)
        val begins = consumer.beginningOffsets(partitions)
        partitions.forEach { p -> consumer.seek(p, maxOf(begins.getValue(p), ends.getValue(p) - limit)) }
        val wanted = partitions.sumOf { p -> ends.getValue(p) - maxOf(begins.getValue(p), ends.getValue(p) - limit) }
        val collected = mutableListOf<ConsumerRecord<ByteArray, ByteArray>>()
        val deadline = System.nanoTime() + READ_BUDGET_MS * NANOS_PER_MILLI
        while (collected.size < wanted && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(POLL_MS)).forEach { collected += it }
        }
        val reachedEnd = partitions.all { p -> consumer.position(p) >= ends.getValue(p) }
        val newestFirst = collected.sortedWith(
            compareByDescending<ConsumerRecord<ByteArray, ByteArray>> { it.timestamp() }.thenByDescending { it.offset() },
        )
        return Read(newestFirst.take(limit), reachedEnd, elapsedMs(started))
    }

    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun classify(e: Throwable): String = when (e) {
        is AuthenticationException -> "The environment's cluster refused the credentials"
        is AuthorizationException -> "The environment's principal may not access this topic"
        is UnknownTopicOrPartitionException -> "The topic does not exist in the environment's cluster"
        is RecordTooLargeException -> "The cluster refused the record as too large"
        is TimeoutException, is java.util.concurrent.TimeoutException -> "The environment's cluster could not be reached in time"
        else -> "The environment's cluster could not be reached"
    }
}
