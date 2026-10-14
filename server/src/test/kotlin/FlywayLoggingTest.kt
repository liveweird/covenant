package ch.nokillswit

import ch.nokillswit.infra.db.jdbcTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class FlywayLoggingTest {
    @Test
    fun `the Flyway target strips query semicolon and authority credentials`() {
        assertEquals("postgres:5432/covenant", jdbcTarget("jdbc:postgresql://postgres:5432/covenant"))
        assertEquals(
            "db.internal:5432/covenant",
            jdbcTarget("jdbc:postgresql://db.internal:5432/covenant?user=app&password=hunter2&sslmode=require"),
        )
        assertEquals(
            "db.internal:5432/covenant",
            jdbcTarget("jdbc:postgresql://db.internal:5432/covenant;user=app;password=hunter2"),
        )
        assertEquals(
            "db/data",
            jdbcTarget("jdbc:postgresql://db/data;password=user@secret"),
        )
        assertEquals("db:5432/covenant", jdbcTarget("jdbc:postgresql://app:hunter2@db:5432/covenant"))
        assertEquals("db:5432/covenant", jdbcTarget("jdbc:postgresql://app:pa;ss@db:5432/covenant"))
        assertEquals(
            "[2001:db8::1]:5432/covenant",
            jdbcTarget("jdbc:postgresql://[2001:db8::1]:5432/covenant"),
        )
        assertEquals("localhost", jdbcTarget("jdbc:postgresql://localhost"))
        assertEquals("jdbc:…", jdbcTarget("jdbc:postgresql://app:pa/ss@db:5432/covenant"))
        assertEquals("jdbc:…", jdbcTarget("jdbc:postgresql://db-a,db-b/covenant"))
        assertEquals("jdbc:…", jdbcTarget("jdbc:postgresql:covenant"))
        assertEquals("jdbc:…", jdbcTarget("password=hunter2"))
    }
}
