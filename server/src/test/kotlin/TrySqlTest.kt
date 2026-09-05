package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.contracts.checks.Severity
import ch.nokillswit.contracts.tryit.OdcsTypes
import ch.nokillswit.contracts.tryit.TrySqlRequest
import ch.nokillswit.contracts.tryit.TrySqlResponse
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.PostgresTargetRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SQL try leg over a fixture view in the shared test container — registered as an environment's PostgreSQL target. */
class TrySqlTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val contract = """
        apiVersion: v3.1.0
        kind: DataContract
        id: 7b1b6f2e-0d2c-4a8e-9a1b-4a0e0c1d2e3f
        version: 1.0.0
        status: active
        name: try_customers
        schema:
          - name: try_customer_view
            physicalType: view
            logicalType: object
            properties:
              - { name: customer_id, logicalType: string, physicalType: uuid, required: true }
              - { name: email, logicalType: string, required: true }
              - { name: total, logicalType: integer, required: true }
              - { name: tags, logicalType: array }
              - { name: payload, logicalType: object }
              - { name: created_at, logicalType: date }
              - { name: missing_col, logicalType: string }
          - name: customers_table
            physicalName: try_customers
            physicalType: table
            logicalType: object
            properties:
              - { name: customer_id, logicalType: string, required: true }
              - { name: email, logicalType: string, required: true }
              - { name: total, logicalType: string, required: true }
          - name: ghost
            physicalName: try_ghost
            physicalType: table
            logicalType: object
          - name: events
            physicalType: topic
            logicalType: object
    """.trimIndent()

    private fun seedFixtureView() {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS try_customers (
                        customer_id uuid NOT NULL, email text, total int4 NOT NULL, tags text[], payload jsonb,
                        created_at timestamptz, note text, blob bytea
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE OR REPLACE VIEW try_customer_view AS SELECT * FROM try_customers")
                st.execute("DELETE FROM try_customers")
                st.execute(
                    """
                    INSERT INTO try_customers VALUES
                      ('11111111-1111-1111-1111-111111111111', 'a@example.com', 1, ARRAY['x','y'], '{"k": 1}', now(), 'n', '\xDEADBEEF'),
                      ('22222222-2222-2222-2222-222222222222', NULL, 2, NULL, NULL, NULL, NULL, NULL),
                      ('33333333-3333-3333-3333-333333333333', 'c@example.com', 3, ARRAY[]::text[], '[]', now(), 'n', NULL)
                    """.trimIndent(),
                )
            }
        }
    }

    private suspend fun HttpClient.odcs(prefix: String, systemId: UInt): Pair<ContractResponse, VersionResponse> {
        val teamId = TestTeams.seed(name("t"))
        val c: ContractResponse = postJson(
            "/api/v1/contracts", ContractCreateRequest(systemId, ContractType.ODCS, name(prefix), null, ownerTeamId = teamId),
        ).body()
        val v = postJson("/api/v1/contracts/${c.id}/versions?allowInvalid=true", VersionCreateRequest("1.0.0", contract))
        assertEquals(HttpStatusCode.Created, v.status, v.bodyAsText())
        return c to v.body()
    }

    private suspend fun environment(systemId: UInt, password: String = PostgresTestSupport.password): UInt =
        TestEnvironments.service.create(
            EnvironmentRequest(
                systemId, name("pg"), null, null, null,
                PostgresTargetRequest(PostgresTestSupport.plainJdbcUrl, PostgresTestSupport.user, password),
            ),
        )

    private suspend fun HttpClient.trySql(c: ContractResponse, v: VersionResponse, request: TrySqlRequest): HttpResponse =
        postJson("/api/v1/contracts/${c.id}/versions/${v.id}/try/sql", request)

    @Test
    fun `a sample comes back with its columns, cells as text, and the declared columns measured`() = testApplication {
        usePostgresTestcontainer()
        seedFixtureView()
        val admin = seededClient("trysql", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("trysql")
        val (c, v) = admin.odcs("trysql", systemId)
        val env = environment(systemId)

        val capture = LogCapture("ch.nokillswit.audit")
        val response = try {
            admin.trySql(c, v, TrySqlRequest(env, "try_customer_view"))
        } finally {
            capture.detach()
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val sample = response.body<TrySqlResponse>()
        assertEquals("""SELECT * FROM "try_customer_view" LIMIT 50""", sample.statement)
        assertEquals(3, sample.rows.size)
        assertFalse(sample.truncated)
        val byName = sample.columns.associateBy { it.name }
        assertEquals("uuid", byName.getValue("customer_id").dbType)
        assertEquals("string", byName.getValue("customer_id").declaredLogicalType)
        assertEquals("_text", byName.getValue("tags").dbType)
        assertNull(byName.getValue("note").declaredLogicalType, "an undeclared column carries no logical type")
        val first = sample.rows.first()
        assertEquals("11111111-1111-1111-1111-111111111111", first[0])
        assertEquals("{x,y}", first[3])
        assertEquals("3q2+7w==", first[7], "bytea as base64")
        assertNull(sample.rows[1][1], "NULL stays null")

        val codes = sample.conformance.findings.groupBy { it.code }.mapValues { it.value.size }
        assertEquals(1, codes[OdcsTypes.COLUMN_MISSING], "missing_col")
        assertEquals(2, codes[OdcsTypes.COLUMN_EXTRA], "note and blob")
        assertNull(codes[OdcsTypes.COLUMN_NULLABLE_MISMATCH], "a view's columns all read as nullable - the rule stays quiet")
        assertEquals(1, codes[OdcsTypes.REQUIRED_VALUE_NULL], "email was NULL in the sample")
        assertNull(codes[OdcsTypes.COLUMN_TYPE_MISMATCH], sample.conformance.findings.toString())
        val table = admin.trySql(c, v, TrySqlRequest(env, "customers_table")).body<TrySqlResponse>()
        val tableCodes = table.conformance.findings.groupBy { it.code }.mapValues { it.value.size }
        assertEquals(1, tableCodes[OdcsTypes.COLUMN_NULLABLE_MISMATCH], "email: required, but the TABLE allows NULL")
        assertEquals(1, tableCodes[OdcsTypes.COLUMN_TYPE_MISMATCH], "total is int4, declared string")
        val mismatch = table.conformance.findings.single { it.code == OdcsTypes.COLUMN_TYPE_MISMATCH }
        assertEquals("/schema/1/properties/2/logicalType", mismatch.path)
        assertEquals("""SELECT * FROM "try_customers" LIMIT 50""", table.statement)
        assertEquals("/schema/0/properties/6", sample.conformance.findings.single { it.code == OdcsTypes.COLUMN_MISSING }.path)
        assertEquals(1, sample.conformance.errors)
        val event = capture.events.single { it.message == "contract.tried_sql" }
        assertTrue(event.hasKeyValue("dataset", "\"try_customer_view\"") && event.hasKeyValue("rowCount", 3), event.toString())
        assertTrue(event.hasKeyValue("outcome", "sampled"))

        val one = admin.trySql(c, v, TrySqlRequest(env, "TRY_CUSTOMER_VIEW", limit = 1)).body<TrySqlResponse>()
        assertEquals(1, one.rows.size, "the dataset matches case-insensitively, the limit binds")
        assertEquals("""SELECT * FROM "try_customer_view" LIMIT 1""", one.statement, "the declared spelling, not the caller's")

        val ghost = admin.trySql(c, v, TrySqlRequest(env, "ghost")).body<TrySqlResponse>()
        assertEquals(listOf(OdcsTypes.DATASET_MISSING), ghost.conformance.findings.map { it.code }, "declared, but not in this database")
        assertEquals(Severity.ERROR, ghost.conformance.findings.single().severity)
        assertTrue(ghost.rows.isEmpty() && ghost.columns.isEmpty())
        assertEquals("""SELECT * FROM "try_ghost" LIMIT 50""", ghost.statement, "the physicalName is the relation")
        val qualified = admin.trySql(c, v, TrySqlRequest(env, "public.try_customer_view", limit = 2)).body<TrySqlResponse>()
        assertEquals(2, qualified.rows.size)
    }

    @Test
    fun `the 400 and 502 matrix - identifiers, undeclared or non-table datasets, limits, targets, credentials`() = testApplication {
        usePostgresTestcontainer()
        seedFixtureView()
        val admin = seededClient("trysql-bad", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("trysql-bad")
        val (c, v) = admin.odcs("trysql-bad", systemId)
        val env = environment(systemId)
        suspend fun detail(request: TrySqlRequest, expected: HttpStatusCode = HttpStatusCode.BadRequest): String {
            val r = admin.trySql(c, v, request)
            assertEquals(expected, r.status, r.bodyAsText())
            return r.body<ProblemDetail>().detail.orEmpty()
        }
        assertTrue(detail(TrySqlRequest(env, "try_customer_view; DROP TABLE x")).contains("identifier"))
        assertTrue(detail(TrySqlRequest(env, "a.b.c")).contains("identifier"))
        assertTrue(detail(TrySqlRequest(env, "\"quoted\"")).contains("identifier"))
        assertTrue(detail(TrySqlRequest(env, "try_nothing")).contains("declares no dataset"))
        assertTrue(detail(TrySqlRequest(env, "events")).contains("is a topic"))
        assertTrue(detail(TrySqlRequest(env, "try_customer_view", limit = 0)).contains("limit"))
        assertTrue(detail(TrySqlRequest(env, "try_customer_view", limit = 201)).contains("limit"))
        val noPg = TestEnvironments.service.create(EnvironmentRequest(systemId, name("http"), null, "http://gateway:8080", null, null))
        assertTrue(detail(TrySqlRequest(noPg, "try_customer_view")).contains("no PostgreSQL target"))
        val wrongPassword = environment(systemId, password = "not-the-password")
        assertTrue(detail(TrySqlRequest(wrongPassword, "try_customer_view"), HttpStatusCode.BadGateway).contains("refused the credentials"))
        val unreachable = TestEnvironments.service.create(
            EnvironmentRequest(
                systemId, name("down"), null, null, null,
                PostgresTargetRequest("jdbc:postgresql://127.0.0.1:9/nowhere", "u", "p"),
            ),
        )
        assertTrue(detail(TrySqlRequest(unreachable, "try_customer_view"), HttpStatusCode.BadGateway).contains("could not be reached"))
        assertEquals(HttpStatusCode.NotFound, admin.trySql(c, v, TrySqlRequest(999_999u, "try_customer_view")).status)
    }
}
