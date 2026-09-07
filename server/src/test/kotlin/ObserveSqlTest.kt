package ch.nokillswit

import ch.nokillswit.contracts.infer.ObserveRelationsRequest
import ch.nokillswit.contracts.infer.ObserveSqlRequest
import ch.nokillswit.contracts.infer.ObserveSqlResponse
import ch.nokillswit.contracts.infer.RelationListResponse
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

/**
 * The SQL observe leg (`contracts/infer/`) against PostgreSQL's own catalog — `to_regclass`,
 * `pg_attribute`, `pg_index`, never `SELECT *`. Shares `try_customers`/`try_customer_view` with
 * `TrySqlTest` (the reserved fixture names, `testing.md`) and adds `try_orders` for the primary
 * key case.
 */
class ObserveSqlTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun seedFixtures() {
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
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS try_orders (
                        id int4 PRIMARY KEY, customer_id uuid NOT NULL, total numeric NOT NULL, created_at timestamptz
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private suspend fun environment(systemId: UInt, password: String = PostgresTestSupport.password): UInt =
        TestEnvironments.service.create(
            EnvironmentRequest(
                systemId, name("pg"), null, null, null,
                PostgresTargetRequest(PostgresTestSupport.plainJdbcUrl, PostgresTestSupport.user, password),
            ),
        )

    private suspend fun HttpClient.observeSql(request: ObserveSqlRequest): HttpResponse =
        postJson("/api/v1/contracts/infer/observe/sql", request)

    private suspend fun HttpClient.listRelations(request: ObserveRelationsRequest): HttpResponse =
        postJson("/api/v1/contracts/infer/observe/sql/relations", request)

    @Test
    fun `a table describes its columns and primary key, a view carries the nullability note`() = testApplication {
        usePostgresTestcontainer()
        seedFixtures()
        val admin = seededClient("observesql", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("observesql")
        val env = environment(systemId)

        val capture = LogCapture("ch.nokillswit.audit")
        val customers = try {
            admin.observeSql(ObserveSqlRequest(env, "try_customers"))
        } finally {
            capture.detach()
        }
        assertEquals(HttpStatusCode.OK, customers.status, customers.bodyAsText())
        val sample = customers.body<ObserveSqlResponse>().sample
        assertEquals("try_customers", sample.name)
        assertEquals("table", sample.physicalType)
        val byName = sample.columns.associateBy { it.name }
        assertEquals("uuid", byName.getValue("customer_id").dbType)
        assertFalse(byName.getValue("customer_id").nullable)
        assertTrue(byName.getValue("email").nullable)
        val event = capture.events.single { it.message == "contract.observed_sql" }
        assertTrue(event.hasKeyValue("relation", "\"try_customers\"") && event.hasKeyValue("outcome", "described"), event.toString())
        assertTrue(event.hasKeyValue("columnCount", byName.size))

        val view = admin.observeSql(ObserveSqlRequest(env, "try_customer_view")).body<ObserveSqlResponse>()
        assertEquals("view", view.sample.physicalType)
        assertTrue(view.notes.any { it.code == "INFER_VIEW_NULLABILITY" }, view.notes.toString())

        val orders = admin.observeSql(ObserveSqlRequest(env, "try_orders")).body<ObserveSqlResponse>()
        assertEquals(1, orders.sample.columns.single { it.name == "id" }.primaryKeyPosition)
        assertNull(orders.sample.columns.single { it.name == "total" }.primaryKeyPosition)

        val qualified = admin.observeSql(ObserveSqlRequest(env, "public.try_customers")).body<ObserveSqlResponse>()
        assertEquals("try_customers", qualified.sample.name)

        val relations = admin.listRelations(ObserveRelationsRequest(env)).body<RelationListResponse>()
        val names = relations.relations.map { it.name }
        assertTrue("try_customers" in names && "try_customer_view" in names && "try_orders" in names, names.toString())
        assertTrue(relations.relations.none { it.schema == "pg_catalog" || it.schema == "information_schema" })
    }

    @Test
    fun `the 400, 404 and 502 matrix - malformed identifiers, an unknown relation, no PostgreSQL target, refused credentials`() =
        testApplication {
            usePostgresTestcontainer()
            seedFixtures()
            val admin = seededClient("observesql-bad", UserRole.ADMIN)
            val systemId = TestContracts.seedSystem("observesql-bad")
            val env = environment(systemId)
            suspend fun detail(request: ObserveSqlRequest, expected: HttpStatusCode = HttpStatusCode.BadRequest): String {
                val r = admin.observeSql(request)
                assertEquals(expected, r.status, r.bodyAsText())
                return r.body<ProblemDetail>().detail.orEmpty()
            }
            assertTrue(detail(ObserveSqlRequest(env, "try_customers; DROP TABLE x")).contains("identifier"))
            assertTrue(detail(ObserveSqlRequest(env, "a.b.c")).contains("identifier"))
            assertEquals(HttpStatusCode.NotFound, admin.observeSql(ObserveSqlRequest(env, "try_ghost")).status)
            assertEquals(HttpStatusCode.NotFound, admin.observeSql(ObserveSqlRequest(999_999u, "try_customers")).status)
            val noPg = TestEnvironments.service.create(EnvironmentRequest(systemId, name("http"), null, "http://gateway:8080", null, null))
            assertTrue(detail(ObserveSqlRequest(noPg, "try_customers")).contains("no PostgreSQL target"))
            val wrongPassword = environment(systemId, password = "not-the-password")
            assertTrue(
                detail(ObserveSqlRequest(wrongPassword, "try_customers"), HttpStatusCode.BadGateway).contains("refused the credentials"),
            )
            val unreachable = TestEnvironments.service.create(
                EnvironmentRequest(
                    systemId, name("down"), null, null, null,
                    PostgresTargetRequest("jdbc:postgresql://127.0.0.1:9/nowhere", "u", "p"),
                ),
            )
            assertTrue(
                detail(ObserveSqlRequest(unreachable, "try_customers"), HttpStatusCode.BadGateway).contains("could not be reached"),
            )
            assertEquals(HttpStatusCode.NotFound, admin.listRelations(ObserveRelationsRequest(999_999u)).status, "an unknown environment")
            val relationsUnreachable = admin.listRelations(ObserveRelationsRequest(unreachable))
            assertEquals(HttpStatusCode.BadGateway, relationsUnreachable.status, "port 9 answers nobody")
        }
}
