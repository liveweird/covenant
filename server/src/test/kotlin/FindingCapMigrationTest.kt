package ch.nokillswit

import ch.nokillswit.contracts.checks.Finding
import ch.nokillswit.contracts.checks.FindingSource
import ch.nokillswit.contracts.checks.Severity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the V25 repair against an isolated pre-migration database. */
class FindingCapMigrationTest {
    @Test
    fun `V25 caps legacy snapshots and recomputes finding counts`() {
        val databaseName = "finding_cap_${UUID.randomUUID().toString().replace("-", "")}"
        val serverUrl = PostgresTestSupport.jdbcUrl.substringBefore('?').substringBeforeLast('/')
        val isolatedUrl = "$serverUrl/$databaseName"
        createDatabase(databaseName)
        try {
            flyway(isolatedUrl, "24").migrate()
            seedLegacySnapshot(isolatedUrl)
            flyway(isolatedUrl).migrate()

            DriverManager.getConnection(isolatedUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT findings, check_errors, check_warnings, check_infos FROM contract_versions WHERE id = 925001",
                    ).use { row ->
                        row.next()
                        val findings = Json.decodeFromString(
                            ListSerializer(Finding.serializer()),
                            row.getString("findings"),
                        )
                        assertEquals(500, findings.size)
                        assertEquals("FINDINGS_TRUNCATED", findings.last().code)
                        assertEquals(1, findings.count { it.code == "FINDINGS_TRUNCATED" })
                        assertEquals(1, row.getInt("check_errors"))
                        assertEquals(498, row.getInt("check_warnings"))
                        assertEquals(1, row.getInt("check_infos"))
                    }
                }
            }
        } finally {
            dropDatabase(databaseName)
        }
    }

    private fun flyway(url: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(url, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun createDatabase(name: String) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE DATABASE \"$name\"") }
        }
    }

    private fun dropDatabase(name: String) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP DATABASE \"$name\" WITH (FORCE)") }
        }
    }

    private fun seedLegacySnapshot(url: String) {
        val findings = listOf(Finding(Severity.ERROR, FindingSource.SCHEMA, "error", "error")) +
            (1..499).map { Finding(Severity.WARN, FindingSource.LINT, "warn-$it", "warn") } +
            Finding(Severity.INFO, FindingSource.SYSTEM, "FINDINGS_TRUNCATED", "1 further finding was not stored (cap 500)")
        val encoded = Json.encodeToString(ListSerializer(Finding.serializer()), findings)
        DriverManager.getConnection(url, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO domains (id, name, created_at, updated_at) VALUES (925001, 'Cap', 1, 1)")
                statement.executeUpdate(
                    "INSERT INTO systems (id, domain_id, name, created_at, updated_at) VALUES (925001, 925001, 'Cap', 1, 1)",
                )
                statement.executeUpdate(
                    """
                    INSERT INTO contracts
                        (id, system_id, type, name, owner_user_id, created_by, created_at, updated_at)
                    VALUES
                        (925001, 925001, 'OPENAPI', 'Cap',
                         (SELECT id FROM users WHERE email = 'admin@covenant.local'),
                         (SELECT id FROM users WHERE email = 'admin@covenant.local'), 1, 1)
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                """
                INSERT INTO contract_versions
                    (id, contract_id, version, semver_major, semver_minor, semver_patch, lifecycle,
                     format, content, content_sha256, findings, check_errors, check_warnings,
                     check_infos, created_by, created_at, updated_at)
                VALUES
                    (925001, 925001, '1.0.0', 1, 0, 0, 'DRAFT', 'yaml', 'openapi: 3.1.0',
                     repeat('a', 64), ?, 99, 99, 99,
                     (SELECT id FROM users WHERE email = 'admin@covenant.local'), 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, encoded)
                statement.executeUpdate()
            }
        }
    }
}
