package ch.nokillswit

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.SemVer
import ch.nokillswit.contracts.checks.AvroBreaking
import ch.nokillswit.contracts.checks.Baseline
import ch.nokillswit.contracts.checks.BreakingChanges
import ch.nokillswit.contracts.checks.CheckerClient
import ch.nokillswit.contracts.checks.CheckerResponse
import ch.nokillswit.contracts.checks.CheckerUnavailableException
import ch.nokillswit.contracts.checks.ChecksService
import ch.nokillswit.contracts.checks.CompatibilityVerdict
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.ParseOutcome
import ch.nokillswit.contracts.checks.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvroBreakingTest {
    @Test
    fun `receive accepts promotion and a defaulted reader field but rejects a required field`() {
        val old = record(field("id", "int"))
        val compatible = record(field("id", "long"), field("note", "string", default = "\"\""))
        assertEquals(emptyList(), compare(document("receive", old), document("receive", compatible)))

        val required = record(field("id", "long"), field("approvalCode", "string"))
        val facts = compare(document("receive", old), document("receive", required))
        assertTrue(facts.any { it.code == AvroBreaking.CODE_INCOMPATIBLE && it.message.contains("approvalCode") }, facts.toString())
    }

    @Test
    fun `send protects old readers while receive allows numeric promotion`() = runBlocking {
        val old = record(field("value", "int"))
        val promoted = record(field("value", "long"))
        assertEquals(emptyList(), compare(document("receive", old), document("receive", promoted)))
        assertTrue(compare(document("send", old), document("send", promoted)).any { it.code == AvroBreaking.CODE_INCOMPATIBLE })

        val service = ChecksService(EmptyChecker)
        val receive = service.compatibility(
            ContractType.ASYNCAPI,
            Baseline(SemVer.parse("1.0.0"), document("receive", old)),
            SemVer.parse("1.1.0"),
            document("receive", promoted),
        )
        assertEquals(CompatibilityVerdict.BACKWARD, receive.verdict)
        val send = service.compatibility(
            ContractType.ASYNCAPI,
            Baseline(SemVer.parse("1.0.0"), document("send", old)),
            SemVer.parse("1.1.0"),
            document("send", promoted),
        )
        assertEquals(CompatibilityVerdict.FORWARD, send.verdict)
    }

    @Test
    fun `enum defaults and union coverage follow Avro reader rules`() {
        val writerEnum = record(field("state", """{"type":"enum","name":"State","symbols":["A","B"]}"""))
        val readerWithDefault = record(
            field("state", """{"type":"enum","name":"State","symbols":["A"],"default":"A"}"""),
        )
        assertEquals(emptyList(), compare(document("receive", writerEnum), document("receive", readerWithDefault)))
        val readerWithoutDefault = record(field("state", """{"type":"enum","name":"State","symbols":["A"]}"""))
        assertTrue(compare(document("receive", writerEnum), document("receive", readerWithoutDefault)).isNotEmpty())

        val scalar = record(field("value", "string"))
        val union = record(field("value", "[\"null\",\"string\"]", default = "null"))
        assertEquals(emptyList(), compare(document("receive", scalar), document("receive", union)))
        assertTrue(compare(document("send", scalar), document("send", union)).isNotEmpty())
    }

    @Test
    fun `logical type and decimal parameter changes are native breaking facts`() {
        val date = record(field("value", """{"type":"int","logicalType":"date"}"""))
        val time = record(field("value", """{"type":"int","logicalType":"time-millis"}"""))
        assertTrue(compare(document("receive", date), document("receive", time)).any { it.code == AvroBreaking.CODE_LOGICAL_TYPE })

        val decimalTwo = record(field("value", decimal(8, 2)))
        val decimalThree = record(field("value", decimal(8, 3)))
        assertTrue(compare(document("receive", decimalTwo), document("receive", decimalThree)).any {
            it.code == AvroBreaking.CODE_DECIMAL
        })
    }

    @Test
    fun `recursive records terminate and retain compatible evolution`() {
        val old = """{"type":"record","name":"Node","fields":[{"name":"next","type":["null","Node"],"default":null}]}"""
        val evolved = """{"type":"record","name":"Node","fields":[""" +
            """{"name":"next","type":["null","Node"],"default":null},""" +
            """{"name":"label","type":"string","default":""}]}"""
        assertEquals(emptyList(), compare(document("receive", old), document("receive", evolved)))
    }

    @Test
    fun `same Avro site matches from AsyncAPI 2 publish to AsyncAPI 3 receive`() {
        val old = document2(record(field("id", "int")))
        val new = document("receive", record(field("id", "long")))
        assertEquals(emptyList(), compare(old, new))
    }

    @Test
    fun `stable AsyncAPI 3 message keys survive mutable message identity changes`() {
        val old = documentMessages(
            "first" to message("Old first", record(field("id", "int"))),
            "second" to message("Old second", record(field("id", "int"))),
        )
        val new = documentMessages(
            "first" to message("Renamed first", record(field("id", "int"), field("required", "string"))),
            "second" to message("Renamed second", record(field("id", "int"))),
        )
        val facts = compare(old, new)
        assertTrue(facts.any { it.code == AvroBreaking.CODE_INCOMPATIBLE && it.path?.contains("/first/") == true }, facts.toString())
        assertFalse(facts.any { it.code == AvroBreaking.CODE_SKIPPED }, facts.toString())
    }

    @Test
    fun `sole unnamed AsyncAPI 2 primitive payload uses bounded channel fallback without a false skip`() {
        assertEquals(emptyList(), compare(document2Primitive("int"), document2Primitive("long")))
    }

    @Test
    fun `many stable message keys are matched independently`() {
        val old = (1..128).map { index -> "message$index" to message("old-$index", record(field("id", "int"))) }
        val new = (1..128).map { index -> "message$index" to message("new-$index", record(field("id", "long"))) }
        assertEquals(emptyList(), compare(documentMessages(*old.toTypedArray()), documentMessages(*new.toTypedArray())))
    }

    @Test
    fun `operation message references limit roles to their selected channel messages`() {
        val old = documentMessagesSelecting(
            "first",
            "first" to message("First", record(field("id", "int"))),
            "second" to message("Second", record(field("id", "int"))),
        )
        val new = documentMessagesSelecting(
            "first",
            "first" to message("First", record(field("id", "int"), field("required", "string"))),
            "second" to message("Second", record(field("id", "int"), field("alsoRequired", "string"))),
        )
        val facts = compare(old, new)
        assertTrue(facts.any { it.code == AvroBreaking.CODE_INCOMPATIBLE && it.path?.contains("/first/") == true })
        assertTrue(facts.any { it.code == AvroBreaking.CODE_SKIPPED && it.path?.contains("/second/") == true })
    }

    @Test
    fun `many unnamed AsyncAPI 2 channels use indexed fallback groups`() {
        assertEquals(emptyList(), compare(document2Channels(128, "int"), document2Channels(128, "long")))
    }

    @Test
    fun `ambiguous unnamed oneOf messages and cyclic schema refs are explicit skips`() {
        val oneOf = document2OneOf("int", "string")
        assertTrue(compare(oneOf, document2OneOf("long", "bytes")).any { it.code == AvroBreaking.CODE_SKIPPED })

        val cyclic = documentWithCyclicSchemaRef()
        assertTrue(compare(cyclic, cyclic.replace("version\":\"1.0.0", "version\":\"1.1.0")).any {
            it.code == AvroBreaking.CODE_SKIPPED && it.message.contains("cycle")
        })
    }

    @Test
    fun `logical type checks descend through arrays and maps`() {
        val old = record(
            field("items", """{"type":"array","items":{"type":"int","logicalType":"date"}}"""),
            field("lookup", """{"type":"map","values":{"type":"int","logicalType":"date"}}"""),
        )
        val changed = old.replace("\"logicalType\":\"date\"", "\"logicalType\":\"time-millis\"")
        assertEquals(2, compare(document("receive", old), document("receive", changed)).count {
            it.code == AvroBreaking.CODE_LOGICAL_TYPE
        })
    }

    @Test
    fun `invalid schemas unresolved refs and unknown direction are explicit skips`() = runBlocking {
        val valid = document("receive", record(field("id", "int")))
        val invalid = document("receive", """{"type":"record","name":"Event","fields":[{"name":"id","type":"missing"}]}""")
        assertTrue(compare(valid, invalid).any { it.code == AvroBreaking.CODE_SKIPPED })

        val dangling = valid.replace(record(field("id", "int")), """{"${'$'}ref":"#/components/schemas/Missing"}""")
        assertTrue(compare(valid, dangling).any { it.code == AvroBreaking.CODE_SKIPPED })

        val noOperation = documentNoOperation(record(field("id", "int")))
        val changedWithoutOperation = documentNoOperation(record(field("id", "long")))
        assertTrue(compare(noOperation, changedWithoutOperation).any { it.code == AvroBreaking.CODE_SKIPPED })
        val compatibility = ChecksService(EmptyChecker).compatibility(
            ContractType.ASYNCAPI,
            Baseline(SemVer.parse("1.0.0"), noOperation),
            SemVer.parse("1.1.0"),
            changedWithoutOperation,
        )
        assertEquals(CompatibilityVerdict.UNKNOWN, compatibility.verdict)
        assertFalse(compatibility.backward.compatible == true)
    }

    @Test
    fun `reply messages use the reverse of their parent operation`() {
        val old = documentWithReply(record(field("value", "int")))
        val promoted = documentWithReply(record(field("value", "long")))
        val facts = compare(old, promoted)
        assertTrue(facts.any { it.code == AvroBreaking.CODE_INCOMPATIBLE && it.message.contains("send (old reader / new writer)") })
    }

    @Test
    fun `differ and Avro skip notes never become SemVer breaking facts`() {
        val notes = listOf(
            Finding(Severity.INFO, FindingSource.BREAKING, BreakingChanges.CODE_ASYNCAPI_DIFF_SKIPPED, "skip"),
            Finding(Severity.INFO, FindingSource.BREAKING, AvroBreaking.CODE_SKIPPED, "skip"),
        )
        assertEquals(notes, BreakingChanges.settle(notes, Baseline(SemVer.parse("1.0.0"), ""), "1.1.0"))
    }

    @Test
    fun `proven Avro incompatibility wins over a differ skip and checker outage`() = runBlocking {
        val old = document("receive", record(field("id", "int")))
        val required = document("receive", record(field("id", "int"), field("required", "string")))
        val baseline = Baseline(SemVer.parse("1.0.0"), old)
        val skip = Finding(
            Severity.INFO,
            FindingSource.BREAKING,
            BreakingChanges.CODE_ASYNCAPI_DIFF_SKIPPED,
            "generic diff unavailable",
        )
        val mixed = ChecksService(StaticChecker(listOf(skip))).compatibility(
            ContractType.ASYNCAPI,
            baseline,
            SemVer.parse("1.1.0"),
            required,
        )
        assertEquals(false, mixed.backward.compatible)
        assertTrue(mixed.backward.findings.any { it.code == AvroBreaking.CODE_INCOMPATIBLE })

        val outage = ChecksService(FailingChecker).compatibility(
            ContractType.ASYNCAPI,
            baseline,
            SemVer.parse("1.1.0"),
            required,
        )
        assertEquals(false, outage.backward.compatible)
        assertFalse(outage.checkerAvailable)
        assertTrue(outage.backward.findings.any { it.code == AvroBreaking.CODE_INCOMPATIBLE })
        assertTrue(outage.backward.findings.any { it.source == FindingSource.SYSTEM })
    }

    private fun compare(old: String, new: String) = AvroBreaking.compare(root(old), root(new)).also { findings ->
        assertTrue(findings.all { it.source == FindingSource.BREAKING })
    }

    private fun root(content: String) = (DocumentParser.parse(content) as ParseOutcome.Parsed).root

    private fun document(action: String, schema: String): String =
        PREFIX + """"channels":{"events":{"address":"events","messages":{"event":{"name":"Event","payload":{""" +
            """"schemaFormat":"$AVRO_FORMAT","schema":$schema}}}}},""" +
            """"operations":{"handle":{"action":"$action","channel":{"${'$'}ref":"#/channels/events"}}}}"""

    private fun document2(schema: String): String =
        """{"asyncapi":"2.6.0","info":{"title":"Events","version":"1.0.0"},""" +
            """"channels":{"events":{"publish":{"message":{"name":"Event","schemaFormat":"$AVRO_FORMAT",""" +
            """"payload":$schema}}}}}"""

    private fun document2Primitive(type: String): String =
        """{"asyncapi":"2.6.0","info":{"title":"Events","version":"1.0.0"},""" +
            """"channels":{"events":{"publish":{"message":{"schemaFormat":"$AVRO_FORMAT","payload":"$type"}}}}}"""

    private fun document2Channels(count: Int, type: String): String =
        """{"asyncapi":"2.6.0","info":{"title":"Events","version":"1.0.0"},"channels":{${
            (1..count).joinToString { index ->
                "\"events.$index\":{\"publish\":{\"message\":{\"schemaFormat\":\"$AVRO_FORMAT\",\"payload\":\"$type\"}}}"
            }
        }}}"""

    private fun document2OneOf(first: String, second: String): String =
        """{"asyncapi":"2.6.0","info":{"title":"Events","version":"1.0.0"},""" +
            """"channels":{"events":{"publish":{"message":{"oneOf":[""" +
            """{"schemaFormat":"$AVRO_FORMAT","payload":"$first"},""" +
            """{"schemaFormat":"$AVRO_FORMAT","payload":"$second"}]}}}}}"""

    private fun documentWithCyclicSchemaRef(): String =
        PREFIX + """"channels":{"events":{"address":"events","messages":{"event":{"name":"Event","payload":{""" +
            """"schemaFormat":"$AVRO_FORMAT","schema":{"${'$'}ref":"#/components/schemas/Loop"}}}}}},""" +
            """"operations":{"handle":{"action":"receive","channel":{"${'$'}ref":"#/channels/events"}}},""" +
            """"components":{"schemas":{"Loop":{"${'$'}ref":"#/components/schemas/Loop"}}}}"""

    private fun documentMessages(vararg messages: Pair<String, String>): String =
        PREFIX + """"channels":{"events":{"address":"events","messages":{${messages.joinToString { (key, value) ->
            "\"$key\":$value"
        }}}}},"operations":{"handle":{"action":"receive","channel":{"${'$'}ref":"#/channels/events"}}}}"""

    private fun documentMessagesSelecting(selected: String, vararg messages: Pair<String, String>): String =
        PREFIX + """"channels":{"events":{"address":"events","messages":{${messages.joinToString { (key, value) ->
            "\"$key\":$value"
        }}}}},"operations":{"handle":{"action":"receive","channel":{"${'$'}ref":"#/channels/events"},""" +
            """"messages":[{"${'$'}ref":"#/channels/events/messages/$selected"}]}}}"""

    private fun message(name: String, schema: String) =
        """{"name":"$name","payload":{"schemaFormat":"$AVRO_FORMAT","schema":$schema}}"""

    private fun documentNoOperation(schema: String): String =
        PREFIX + """"channels":{"events":{"address":"events","messages":{"event":{"name":"Event","payload":{""" +
            """"schemaFormat":"$AVRO_FORMAT","schema":$schema}}}}}}"""

    private fun documentWithReply(schema: String): String =
        PREFIX + """"channels":{"request":{"address":"request","messages":{"request":{"payload":{"type":"string"}}}},""" +
            """"events":{"address":"events","messages":{"event":{"name":"Event","payload":{""" +
            """"schemaFormat":"$AVRO_FORMAT","schema":$schema}}}}},""" +
            """"operations":{"handle":{"action":"receive","channel":{"${'$'}ref":"#/channels/request"},""" +
            """"reply":{"channel":{"${'$'}ref":"#/channels/events"}}}}}"""

    private fun record(vararg fields: String) = """{"type":"record","name":"Event","fields":[${fields.joinToString()}]}"""
    private fun field(name: String, type: String, default: String? = null): String {
        val encodedType = if (type.startsWith('{') || type.startsWith('[')) type else "\"$type\""
        return """{"name":"$name","type":$encodedType${default?.let { ",\"default\":$it" } ?: ""}}"""
    }

    private fun decimal(precision: Int, scale: Int) =
        """{"type":"bytes","logicalType":"decimal","precision":$precision,"scale":$scale}"""

    private object EmptyChecker : CheckerClient {
        override suspend fun check(type: ContractType, content: String, previousContent: String?) = CheckerResponse(emptyList())
    }

    private class StaticChecker(private val findings: List<Finding>) : CheckerClient {
        override suspend fun check(type: ContractType, content: String, previousContent: String?) = CheckerResponse(findings)
    }

    private object FailingChecker : CheckerClient {
        override suspend fun check(type: ContractType, content: String, previousContent: String?): CheckerResponse {
            throw CheckerUnavailableException("down")
        }
    }

    private companion object {
        const val AVRO_FORMAT = "application/vnd.apache.avro;version=1.12.0"
        const val PREFIX = """{"asyncapi":"3.0.0","info":{"title":"Events","version":"1.0.0"},"""
    }
}
