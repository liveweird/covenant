package ch.nokillswit

import ch.nokillswit.contracts.SemVer
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Exercises the production upgrade path against an isolated database, never the suite's shared schema. */
class ReleaseLineMigrationTest {
    @Test
    fun `V16 backfills active contracts and installs SemVer precedence uniqueness`() {
        val databaseName = "release_lines_${UUID.randomUUID().toString().replace("-", "")}"
        val serverUrl = PostgresTestSupport.jdbcUrl.substringBefore('?').substringBeforeLast('/')
        val isolatedUrl = "$serverUrl/$databaseName"
        createDatabase(databaseName)
        try {
            flyway(isolatedUrl, "15").migrate()
            seedV15Catalog(isolatedUrl)
            flyway(isolatedUrl).migrate()

            DriverManager.getConnection(isolatedUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT major, support_status, support_ends_on, support_policy, recommended_version_id,
                               deprecates_on, replacement_contract_id, replacement_major, migration_guide
                        FROM contract_release_lines
                        ORDER BY major
                        """.trimIndent(),
                    ).use { rows ->
                        val majors = mutableListOf<Int>()
                        while (rows.next()) {
                            majors += rows.getInt("major")
                            assertEquals("UNSPECIFIED", rows.getString("support_status"))
                            assertNull(rows.getString("support_ends_on"))
                            assertNull(rows.getString("support_policy"))
                            assertNull(rows.getObject("recommended_version_id"))
                            assertNull(rows.getString("deprecates_on"))
                            assertNull(rows.getObject("replacement_contract_id"))
                            assertNull(rows.getObject("replacement_major"))
                            assertNull(rows.getString("migration_guide"))
                        }
                        assertEquals(listOf(1, 2), majors, "deleted parents and deleted versions are excluded")
                    }

                    val duplicate = assertFailsWith<SQLException> {
                        statement.executeUpdate(versionInsert(contractId = 910001, version = "1.2.3+two", major = 1, patch = 3))
                    }
                    assertEquals("23505", duplicate.sqlState, "build metadata does not distinguish SemVer precedence")
                    assertEquals(
                        1,
                        statement.executeUpdate(versionInsert(contractId = 910001, version = "1.2.4+two", major = 1, patch = 4)),
                        "a different precedence remains valid",
                    )
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

    private fun seedV15Catalog(url: String) {
        DriverManager.getConnection(url, PostgresTestSupport.user, PostgresTestSupport.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO domains (id, name, created_at, updated_at) VALUES (910001, 'Upgrade', 1, 1)")
                statement.executeUpdate(
                    "INSERT INTO systems (id, domain_id, name, created_at, updated_at) VALUES (910001, 910001, 'Upgrade', 1, 1)",
                )
                statement.executeUpdate(contractInsert(910001, "Backfilled", deleted = false))
                statement.executeUpdate(contractInsert(910002, "Deleted parent", deleted = true))
                statement.executeUpdate(contractInsert(910003, "Deleted versions", deleted = false))
                statement.executeUpdate(versionInsert(910001, "1.2.3+one", major = 1, patch = 3))
                statement.executeUpdate(versionInsert(910001, "2.0.0-rc.1", major = 2, patch = 0, prerelease = "rc.1"))
                statement.executeUpdate(versionInsert(910001, "9.0.0", major = 9, patch = 0, deleted = true))
                statement.executeUpdate(versionInsert(910002, "7.0.0", major = 7, patch = 0))
                statement.executeUpdate(versionInsert(910003, "8.0.0", major = 8, patch = 0, deleted = true))
            }
        }
    }

    private fun contractInsert(id: Int, name: String, deleted: Boolean) =
        """
        INSERT INTO contracts
            (id, system_id, type, name, owner_user_id, created_by, created_at, updated_at, marked_as_deleted)
        VALUES
            ($id, 910001, 'OPENAPI', '$name',
             (SELECT id FROM users WHERE email = 'admin@covenant.local'),
             (SELECT id FROM users WHERE email = 'admin@covenant.local'), 1, 1, $deleted)
        """.trimIndent()

    private fun versionInsert(
        contractId: Int,
        version: String,
        major: Int,
        patch: Int,
        prerelease: String? = null,
        deleted: Boolean = false,
    ): String {
        val minor = SemVer.parse(version).minor
        val prereleaseSql = prerelease?.let { "'$it'" } ?: "NULL"
        return """
            INSERT INTO contract_versions
                (contract_id, version, semver_major, semver_minor, semver_patch, semver_prerelease,
                 lifecycle, format, content, content_sha256, created_by, created_at, updated_at, marked_as_deleted)
            VALUES
                ($contractId, '$version', $major, $minor, $patch, $prereleaseSql,
                 'DRAFT', 'yaml', 'openapi: 3.1.0', repeat('a', 64),
                 (SELECT id FROM users WHERE email = 'admin@covenant.local'), 1, 1, $deleted)
        """.trimIndent()
    }
}
